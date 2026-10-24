import XCTest
@testable import Pulse

private final class ScheduledProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "scheduled.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        if mode == "lost-response" {
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
            return
        }
        var requestData = request.httpBody ?? Data()
        if requestData.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                requestData.append(contentsOf: buffer.prefix(count))
            }
        }
        let submitted = (try? JSONSerialization.jsonObject(with: requestData)) as? [String: Any] ?? [:]
        let paging = request.value(forHTTPHeaderField: "X-Paging") == "true"
        let second = URLComponents(url:request.url!,resolvingAgainstBaseURL:false)?.queryItems?.contains(where:{$0.name == "cursor"}) == true
        let row: [String:Any] = ["id":paging ? (second && mode != "duplicate" ? "second" : "first") : "queued",
            "conversationId":mode == "wrong" ? "other" : "room","content":"Hello","scheduledAt":"2030-01-01T09:00:00.000Z"]
        let more = paging && (!second || mode == "repeated")
        var body: [String:Any] = ["scheduleVersion":mode == "version" ? 0 : 1,"userId":mode == "owner" ? "other" : "me",
            "conversationId":mode == "room" ? "" : "room","item":row,"items":[row],"hasMore":more,
            "nextCursor":more ? "next" as Any : NSNull(),"ok":true,"cancelled":true,"scheduledId":mode == "wrong" ? "other" : "queued"]
        body["clientId"] = submitted["clientId"] as? String ?? ""
        body["replayed"] = false
        if mode == "empty" { body = [:] }
        if mode == "missing" { body.removeValue(forKey:"items");body.removeValue(forKey:"item");body.removeValue(forKey:"cancelled") }
        let status = second && mode == "failure" ? 503 : 200
        client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:nil,headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self,didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class ScheduledAcceptanceTests: XCTestCase {
    func testScheduledReadsAndActionsRequireOwnedExactReceipts() async throws {
        for action in ["list","create","cancel"] {
            for mode in ["valid","empty","version","owner","room","missing","wrong"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [ScheduledProtocol.self];config.httpAdditionalHeaders = ["X-Mode":mode]
                let session = URLSession(configuration:config);defer{session.invalidateAndCancel()}
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                defer { try? FileManager.default.removeItem(at: directory) }
                let api = PulseAPIClient(baseURL:URL(string:"https://scheduled.test")!,userId:"me",session:session,
                    creationOperations: PulseWalletOperations(directory: directory))
                do {
                    switch action {
                    case "list": _ = try await api.scheduledMessages(conversationId:"room")
                    case "create": _ = try await api.scheduleMessage(conversationId:"room",content:"Hello",scheduledAtIso:"2030-01-01T09:00:00.000Z")
                    default: try await api.cancelScheduled("queued")
                    }
                    XCTAssertEqual(mode,"valid","\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode,"valid","\(action):\(error)") }
            }
        }
    }
    func testPaginationNeverAppliesAPartialList() async throws {
        for mode in ["valid","failure","duplicate","repeated"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [ScheduledProtocol.self];config.httpAdditionalHeaders = ["X-Mode":mode,"X-Paging":"true"]
            let session = URLSession(configuration:config);defer{session.invalidateAndCancel()}
            let api = PulseAPIClient(baseURL:URL(string:"https://scheduled.test")!,userId:"me",session:session)
            do {
                let rows = try await api.scheduledMessages(conversationId:"room")
                XCTAssertEqual(mode,"valid");XCTAssertEqual(rows.map(\.id),["first","second"])
            } catch {XCTAssertNotEqual(mode,"valid","\(error)")}
        }
    }
    func testTomorrowAlwaysUsesTheNextLocalCalendarDay() throws {
        var calendar = Calendar(identifier:.gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier:"Asia/Dhaka"))
        let beforeNine = try XCTUnwrap(PulseFormat.date("2026-10-23T02:00:00Z"))
        XCTAssertEqual(PulseScheduledChip.tomorrowMorning(now:beforeNine,calendar:calendar),PulseFormat.date("2026-10-24T03:00:00Z"))
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier:"America/New_York"))
        let beforeFallBack = try XCTUnwrap(PulseFormat.date("2026-10-31T12:00:00Z"))
        XCTAssertEqual(PulseScheduledChip.tomorrowMorning(now:beforeFallBack,calendar:calendar),PulseFormat.date("2026-11-01T14:00:00Z"))
    }
    func testCancellationDoesNotInventSuccessAfterALostResponseAndAcceptsAConfirmedRetry() async throws {
        for mode in ["lost-response", "valid"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [ScheduledProtocol.self]
            config.httpAdditionalHeaders = ["X-Mode": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://scheduled.test")!, userId: "me", session: transport)
            do {
                try await api.cancelScheduled("queued")
                XCTAssertEqual(mode, "valid")
            } catch { XCTAssertEqual(mode, "lost-response", "\(error)") }
        }
    }
    func testPresetSnapshotAndExpiredRetryKeepTheOriginalDeadline() throws {
        let now = try XCTUnwrap(PulseFormat.date("2026-10-31T12:00:00.123Z"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let choices = PulseScheduledChip.presetDates(now: now, calendar: calendar)
        XCTAssertEqual(choices[0], now.addingTimeInterval(3600))
        XCTAssertEqual(choices[1], PulseFormat.date("2026-11-01T14:00:00Z"))
        XCTAssertEqual(choices[2], now.addingTimeInterval(7 * 86400))
        let selected = try XCTUnwrap(choices[0])
        let attempt = PulseScheduledChip.Attempt(text: "Hello", date: selected)
        let later = now.addingTimeInterval(7200)
        XCTAssertTrue(PulseScheduledChip.canSubmit(text: " Hello ", date: selected, now: later, previous: attempt))
        XCTAssertFalse(PulseScheduledChip.canSubmit(text: "Hello", date: selected, now: later, previous: nil))
        XCTAssertFalse(PulseScheduledChip.canSubmit(text: "Changed", date: selected, now: later, previous: attempt))
        XCTAssertFalse(PulseScheduledChip.canSubmit(text: "Hello", date: selected.addingTimeInterval(1), now: later, previous: attempt))
        XCTAssertFalse(PulseScheduledChip.canSubmit(text: " ", date: selected, now: now, previous: nil))
    }
    func testScheduleDialogStaysOpenOnFailureAndDismissesOnlyAfterConfirmedRetry() async throws {
        let gate = PulseRoomOperations()
        var dismissals = 0
        let failed = try XCTUnwrap(gate.run(key: "schedule", operation: { false }, onSuccess: { dismissals += 1 }))
        XCTAssertNil(gate.run(key: "schedule", operation: { true }, onSuccess: { dismissals += 1 }))
        await failed.value
        XCTAssertEqual(dismissals, 0)
        XCTAssertTrue(gate.pending.isEmpty)
        let retry = try XCTUnwrap(gate.run(key: "schedule", operation: { true }, onSuccess: { dismissals += 1 }))
        await retry.value
        XCTAssertEqual(dismissals, 1)
    }
}

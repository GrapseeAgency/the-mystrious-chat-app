import XCTest
@testable import Pulse

private final class ScheduleCreationReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix("schedule-retry.test") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                data.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let id = body["clientId"] as? String ?? "missing"
        let mode = request.value(forHTTPHeaderField: "X-Schedule-Fixture") ?? "success"
        let room = request.url!.path.split(separator: "/").dropFirst(2).first.map { String($0) } ?? ""
        var row: [String: Any] = ["id": "scheduled-" + id, "conversationId": room,
            "content": (body["content"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
            "scheduledAt": body["scheduledAt"] ?? ""]
        if mode == "sent" { row["sentAt"] = body["scheduledAt"] }
        if mode == "cancelled" { row["cancelledAt"] = body["scheduledAt"] }
        var payload: [String: Any] = ["scheduleVersion": 1, "userId": body["senderId"] ?? "", "conversationId": room,
            "clientId": id, "replayed": true, "item": row]
        if mode == "bad" { payload["clientId"] = "wrong-receipt" }
        if mode == "legacy" { payload.removeValue(forKey: "clientId") }
        if mode == "string-replay" { payload["replayed"] = "true" }
        if mode == "missing-replay" { payload.removeValue(forKey: "replayed") }
        let status = !request.url!.path.hasSuffix("/scheduled/create-v2") || UUID(uuidString: id) == nil ? 400 : mode == "old-server" ? 404 : mode == "lost" ? 503 : mode == "finished" ? 410 : 200
        if status != 200 { payload = ["error": id] }
        if status == 410 { payload["code"] = "schedule_finished"; payload["clientId"] = id }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ScheduledCreationRetryTests: XCTestCase {
    private let when = "2030-01-01T09:00:00.000Z"
    private func client(_ directory: URL, mode: String = "success", host: String = "one.schedule-retry.test", owner: String = "me") -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ScheduleCreationReplyProtocol.self]
        config.httpAdditionalHeaders = ["X-Schedule-Fixture": mode]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://" + host)!, userId: owner, session: session,
            creationOperations: PulseWalletOperations(directory: directory)), session)
    }
    func testLostResponseRetainsScheduleIdentityAcrossReopenAndStaleAcceptanceCannotRetireANewSend() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (lost, s1) = client(dir, mode: "lost"); defer { s1.invalidateAndCancel() }
        var firstID: String?
        do { _ = try await lost.scheduleMessage(conversationId: "room", content: " Hello ", scheduledAtIso: when); XCTFail("Expected failure") }
        catch let failure as PulseAPIClient.Failure { firstID = failure.message; XCTAssertEqual(failure.status, 503) }
        let (api, s2) = client(dir); defer { s2.invalidateAndCancel() }
        let first = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: "2030-01-01T10:00:00+01:00")
        XCTAssertEqual(first.creationClientId, firstID)
        let unaccepted = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
        XCTAssertEqual(unaccepted.creationClientId, firstID)
        try await api.acknowledgeScheduledCreation(first)
        let next = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
        XCTAssertNotEqual(next.creationClientId, firstID)
        try await api.acknowledgeScheduledCreation(first)
        let afterStaleAck = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
        XCTAssertEqual(afterStaleAck.creationClientId, next.creationClientId)
    }
    func testMalformedOrTerminalReceiptDoesNotReleaseTheOriginalRequestIdentity() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (api, session) = client(dir); defer { session.invalidateAndCancel() }
        let first = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
        for mode in ["bad", "legacy", "string-replay", "missing-replay", "sent", "cancelled", "finished", "old-server"] {
            let (bad, s) = client(dir, mode: mode); defer { s.invalidateAndCancel() }
            do { _ = try await bad.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when); XCTFail(mode) }
            catch { }
            let retained = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
            XCTAssertEqual(retained.creationClientId, first.creationClientId, mode)
        }
        let cancelledAck = Task { try await api.acknowledgeScheduledCreation(first) }
        cancelledAck.cancel()
        do { try await cancelledAck.value; XCTFail("Cancelled acceptance retired the request") } catch { }
        let retained = try await api.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
        XCTAssertEqual(retained.creationClientId, first.creationClientId)
    }
    func testScheduleIdentitySeparatesGatewayOwnerRoomTextAndTime() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (api, s) = client(dir); defer { s.invalidateAndCancel() }
        var ids = Set<String>()
        for (room, text, time) in [("room", "Hello", when), ("other", "Hello", when), ("room", "Changed", when), ("room", "Hello", "2030-01-01T09:00:01Z")] {
            let row = try await api.scheduleMessage(conversationId: room, content: text, scheduledAtIso: time)
            XCTAssertTrue(ids.insert(try XCTUnwrap(row.creationClientId)).inserted)
        }
        for (host, owner) in [("two.schedule-retry.test", "me"), ("one.schedule-retry.test", "other")] {
            let (other, session) = client(dir, host: host, owner: owner); defer { session.invalidateAndCancel() }
            let row = try await other.scheduleMessage(conversationId: "room", content: "Hello", scheduledAtIso: when)
            XCTAssertTrue(ids.insert(try XCTUnwrap(row.creationClientId)).inserted)
        }
    }
}

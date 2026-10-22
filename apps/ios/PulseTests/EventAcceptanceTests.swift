import XCTest
@testable import Pulse

private final class EventReceiptProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "event-receipt.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Test-Mode") ?? "valid"
        let action = request.value(forHTTPHeaderField: "X-Test-Action") ?? "checkin"
        let nested = action == "checkin" ? "checkIn" : "rsvp"
        var row: [String: Any] = ["id": "r", "eventId": "e", "userId": "me", "status": "going", "checkedInAt": "2026-10-22T12:00:00Z"]
        var body: [String: Any] = ["eventReceiptVersion": 1, "eventId": "e", "userId": "me", "ok": true,
            "counts": ["going": 1, "maybe": 0, "no": 0], "xpAwarded": true, "alreadyCheckedIn": false, "checkedInCount": 1]
        switch mode {
        case "version": body["eventReceiptVersion"] = 0
        case "event": body["eventId"] = "other"
        case "owner": body["userId"] = "other"
        case "false-ok": body["ok"] = false
        case "bonus": body["alreadyCheckedIn"] = true
        case "count": body["checkedInCount"] = 0
        case "counts": body["counts"] = ["going": 0, "maybe": 0, "no": 0]
        case "nested-event": row["eventId"] = "other"
        case "nested-owner": row["userId"] = "other"
        case "status": row["status"] = "no"
        case "bad-stamp": row["checkedInAt"] = "invalid"
        default: break
        }
        body[nested] = mode == "missing-stamp" ? NSNull() : row as Any
        if action == "create" {
            body = ["event": ["id": mode == "blank" ? "" : "e", "title": "Meet",
                "createdById": mode == "owner" ? "other" : "me", "startsAt": "2026-10-22T12:00:00Z"]]
        }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class EventAcceptanceTests: XCTestCase {
    func testEventSuccessRequiresCanonicalIdentityAndActualAcceptance() async throws {
        for action in ["checkin", "rsvp", "delete", "create"] {
            var modes = ["valid", "empty", "version", "event", "owner"]
            switch action {
            case "delete": modes += ["false-ok"]
            case "checkin": modes += ["missing-stamp", "bad-stamp", "nested-event", "nested-owner", "bonus", "count"]
            case "rsvp": modes += ["nested-event", "nested-owner", "status", "counts"]
            default: modes = ["valid", "empty", "owner", "blank"]
            }
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [EventReceiptProtocol.self]
                config.httpAdditionalHeaders = ["X-Test-Mode": mode, "X-Test-Action": action]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://event-receipt.test")!, userId: "me", session: transport)
                do {
                    switch action {
                    case "checkin": _ = try await api.checkinEvent("e")
                    case "rsvp": _ = try await api.rsvpEvent("e", status: "going")
                    case "delete": try await api.deleteEvent("e")
                    default: _ = try await api.createEvent(conversationId: "room", title: "Meet", startsAtIso: "2026-10-22T12:00:00Z", description: nil, location: nil)
                    }
                    XCTAssertEqual(mode, "valid", "\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode, "valid", "\(action) valid receipt failed: \(error)") }
            }
        }
    }
}

import XCTest
@testable import Pulse

private final class ReadStateProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "read-state.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Read-Test") ?? "valid"
        let visible = request.value(forHTTPHeaderField: "X-Read-Intent") == "visible"
        var body: [String: Any] = ["ok": mode != "false", "readReceiptVersion": mode == "version" ? 0 : 1,
            "conversationId": mode == "room" ? "other" : "room", "userId": mode == "owner" ? "other" : "me",
            "mode": mode == "intent" ? "wrong" : visible ? "visible" : "all",
            "lastReadAt": mode == "date" ? "invalid" : mode == "behind" ? "2026-10-22T00:00:00Z" : "2026-10-22T00:00:10Z"]
        if visible || mode == "anchor" { body["messageId"] = mode == "anchor" ? "other" : "anchor" }
        if mode == "legacy" { body = ["ok": true] }
        // A legacy-path fallback cannot return a valid versioned receipt here.
        if request.url?.path != "/api/conversations/room/read-state" { body = ["ok": true] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ReadStateAcknowledgementTests: XCTestCase {
    func testReadAcceptanceRequiresTheCommittedOwnerRoomIntentBoundaryAndTimestamp() async throws {
        for visible in [true, false] {
            for mode in ["valid", "legacy", "false", "version", "room", "owner", "anchor", "intent", "date", "behind"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [ReadStateProtocol.self]
                config.httpAdditionalHeaders = ["X-Read-Test": mode, "X-Read-Intent": visible ? "visible" : "all"]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://read-state.test")!, userId: "me", session: transport)
                do {
                    try await api.markRead(conversationId: "room", messageId: visible ? "anchor" : nil, minimumReadAt: "2026-10-22T00:00:05Z")
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "Valid acknowledgement failed: \(error)") }
            }
        }
    }
}

private final class ManualUnreadProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "manual-unread.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Unread-Test") ?? "valid"
        let on = request.value(forHTTPHeaderField: "X-Unread-On") == "true"
        var body: [String: Any] = ["ok": mode != "false", "unreadReceiptVersion": mode == "version" ? 0 : 1,
            "conversationId": mode == "room" ? "other" : "room", "userId": mode == "owner" ? "other" : "me",
            "manualUnread": mode == "opposite" ? !on : on]
        if mode == "missing" { body.removeValue(forKey: "manualUnread") }
        if mode == "null" { body["manualUnread"] = NSNull() }
        if mode == "string" { body["manualUnread"] = String(on) }
        if mode == "legacy" { body = ["ok": true] }
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.url?.path, "/api/conversations/room/mark-unread")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

extension ReadStateAcknowledgementTests {
    func testManualUnreadRequiresExactOwnerRoomVersionAndDesiredBoolean() async throws {
        for on in [true, false] {
            for mode in ["valid", "legacy", "false", "version", "room", "owner", "opposite", "missing", "null", "string"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [ManualUnreadProtocol.self]
                config.httpAdditionalHeaders = ["X-Unread-Test": mode, "X-Unread-On": String(on)]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://manual-unread.test")!, userId: "me", session: transport)
                do {
                    try await api.markUnread(conversationId: "room", on: on)
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "Valid acknowledgement failed: \(error)") }
            }
        }
    }
}

import XCTest
@testable import Pulse

private final class ReadStateProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "read-state.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Read-Test") ?? "valid"
        let visible = request.value(forHTTPHeaderField: "X-Read-Intent") == "visible"
        var body: [String: Any] = ["ok": mode != "false", "readReceiptVersion": mode == "version" ? 0 : 2,
            "readIntent": mode == "fence" ? "other-fence" : "fence", "conversationId": mode == "room" ? "other" : "room", "userId": mode == "owner" ? "other" : "me",
            "mode": mode == "intent" ? "wrong" : visible ? "visible" : "all",
            "lastReadAt": mode == "date" ? "invalid" : mode == "behind" ? "2026-10-22T00:00:00Z" : "2026-10-22T00:00:10Z"]
        if visible || mode == "anchor" { body["messageId"] = mode == "anchor" ? "other" : "anchor" }
        if mode == "legacy" { body = ["ok": true] }
        // A legacy-path fallback cannot return a valid versioned receipt here.
        if request.url?.path != "/api/conversations/room/read-state" { body = ["ok": true] }
        if request.url?.path.hasSuffix("/read-intent") == true {
            body = ["readIntentVersion": 1, "conversationId": "room", "userId": "me", "readIntent": "fence"]
            switch mode {
            case "prepare-missing": body = [:]
            case "prepare-owner": body["userId"] = "other"
            case "prepare-room": body["conversationId"] = "other"
            case "prepare-version": body["readIntentVersion"] = "1"
            case "prepare-token": body["readIntent"] = 1
            case "prepare-blank": body["readIntent"] = " "
            default: break
            }
        } else { XCTAssertFalse(mode.hasPrefix("prepare-"), "Invalid intent must not dispatch a write") }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ReadStateAcknowledgementTests: XCTestCase {
    func testInvalidPreparationNeverDispatchesAReadWrite() async throws {
        for mode in ["prepare-missing", "prepare-owner", "prepare-room", "prepare-version", "prepare-token", "prepare-blank"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [ReadStateProtocol.self]
            config.httpAdditionalHeaders = ["X-Read-Test": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://read-state.test")!, userId: "me", session: transport)
            do { try await api.markRead(conversationId: "room", messageId: "anchor"); XCTFail(mode) }
            catch { /* Refused before dispatch. */ }
        }
    }

    func testReadAcceptanceRequiresTheCommittedOwnerRoomIntentBoundaryAndTimestamp() async throws {
        for visible in [true, false] {
            for mode in ["valid", "legacy", "false", "version", "room", "owner", "anchor", "intent", "date", "behind", "fence"] {
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
        var body: [String: Any] = ["ok": mode != "false", "unreadReceiptVersion": mode == "version" ? 0 : 2,
            "readIntent": mode == "fence" ? "other-fence" : "fence", "conversationId": mode == "room" ? "other" : "room", "userId": mode == "owner" ? "other" : "me",
            "manualUnread": mode == "opposite" ? !on : on]
        if mode == "missing" { body.removeValue(forKey: "manualUnread") }
        if mode == "null" { body["manualUnread"] = NSNull() }
        if mode == "string" { body["manualUnread"] = String(on) }
        if mode == "legacy" { body = ["ok": true] }
        if request.url?.path.hasSuffix("/read-intent") != true {
            XCTAssertEqual(request.httpMethod, "PATCH")
            XCTAssertEqual(request.url?.path, "/api/conversations/room/mark-unread")
        }
        if request.url?.path.hasSuffix("/read-intent") == true {
            body = ["readIntentVersion": 1, "conversationId": "room", "userId": "me", "readIntent": "fence"]
        }
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
            for mode in ["valid", "legacy", "false", "version", "room", "owner", "opposite", "missing", "null", "string", "fence"] {
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

import XCTest
@testable import Pulse

private final class RoomControlProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "controls.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let action = request.value(forHTTPHeaderField: "X-Action") ?? "list"
        var rule: [String: Any] = ["id": mode == "wrong" ? "other" : "rule", "conversationId": mode == "wrong" ? "other" : "room",
            "trigger": "Hello", "reply": "Welcome", "hits": 0, "enabled": action != "patch"]
        var hook: [String: Any] = ["id": "hook", "name": "Build bot", "createdBy": "me", "token": mode == "wrong" ? "" : "capability", "url": "/api/webhooks/capability"]
        if mode == "missing" { rule.removeValue(forKey: "enabled"); hook.removeValue(forKey: "token") }
        var body: [String: Any] = ["automation": rule, "automations": [rule], "webhooks": [hook], "ok": true, "deleted": true,
            "userId": mode == "wrong" ? "other" : "me", "automationId": "rule", "token": "capability"]
        if mode == "missing" { body.removeValue(forKey: "automations"); body.removeValue(forKey: "webhooks"); body.removeValue(forKey: "deleted") }
        if action == "hookCreate" { body = hook }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class RoomControlAcceptanceTests: XCTestCase {
    func testInvalidListsRowsAndDeletionReceiptsNeverConfirmControls() async throws {
        for action in ["list", "create", "patch", "delete", "hooks", "hookCreate", "hookDelete"] {
            for mode in ["valid", "empty", "wrong", "missing"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [RoomControlProtocol.self]
                config.httpAdditionalHeaders = ["X-Mode": mode, "X-Action": action]
                let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://controls.test")!, userId: "me", session: session)
                do {
                    switch action {
                    case "list": _ = try await api.automations(conversationId: "room")
                    case "create": _ = try await api.createAutomation(conversationId: "room", trigger: "Hello", reply: "Welcome")
                    case "patch": _ = try await api.patchAutomation("rule", enabled: false)
                    case "delete": try await api.deleteAutomation("rule")
                    case "hooks": _ = try await api.webhooks(conversationId: "room")
                    case "hookCreate": _ = try await api.createWebhook(conversationId: "room", name: "Build bot")
                    default: try await api.deleteWebhook(token: "capability")
                    }
                    XCTAssertEqual(mode, "valid", "\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode, "valid", "\(action):\(error)") }
            }
        }
    }
}

import XCTest
@testable import Pulse

private final class MessageActionAcknowledgementProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "message-action.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Action-Mode") ?? "valid"
        let deleting = request.httpMethod == "DELETE"
        var message: [String: Any] = ["id": mode == "foreign" ? "other" : "m", "conversationId": "room",
            "senderId": mode == "owner" ? "other" : "me", "kind": "text", "content": mode == "content" ? "old" : "edited",
            "createdAt": "2026-10-22T00:00:00Z"]
        let pinning = request.url?.path.hasSuffix("/pin-state") == true
        if pinning && mode != "pin-row" { message["pinnedAt"] = "2026-10-22T00:01:00Z" }
        if mode != "stamp" { message["editedAt"] = "2026-10-22T00:01:00Z" }
        if (deleting && mode != "live") || mode == "deleted" { message["deletedAt"] = "2026-10-22T00:02:00Z" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        var envelope: [String: Any] = ["message": message]
        if pinning && mode != "pin-missing" {
            envelope["pin"] = ["messageId": mode == "pin-id" ? "other" : "m", "pinned": mode != "pin-state"]
        }
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: envelope))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class MessageActionAcknowledgementTests: XCTestCase {
    func testActionsRequireTheRequestedMessageAndTheirActualCommittedState() async throws {
        for action in ["edit", "pin", "delete"] {
            let modes = action == "edit" ? ["valid", "foreign", "owner", "content", "stamp", "deleted"] :
                action == "delete" ? ["valid", "foreign", "owner", "live"] : ["valid", "foreign", "deleted", "pin-missing", "pin-id", "pin-state", "pin-row"]
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [MessageActionAcknowledgementProtocol.self]
                config.httpAdditionalHeaders = ["X-Action-Mode": mode]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://message-action.test")!, userId: "me", session: transport)
                do {
                    switch action {
                    case "edit": _ = try await api.editMessage(id: "m", userId: "me", content: "edited")
                    case "pin": _ = try await api.pinMessage(id: "m", userId: "me")
                    default: _ = try await api.deleteOwnMessage(id: "m")
                    }
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "Valid \(action) receipt failed: \(error)") }
            }
        }
    }
}

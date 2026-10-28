import XCTest
@testable import Pulse

private final class MessageWindowProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "message-window.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-User-Id") ?? "valid"
        // The per-test mode is encoded into the base path, avoiding mutable global test state.
        let variant = request.url!.path.split(separator: "/").first.map(String.init) ?? mode
        let codes: [String: (Int, String)] = ["denied": (403, "thread_access"), "gone": (410, "thread_gone"),
            "missing": (404, "message_missing"), "reply-gone": (410, "message_gone"), "proxy": (403, "proxy")]
        var body: [String: Any]; var status = 200
        if let error = codes[variant] { status = error.0; body = ["error": "unavailable", "code": error.1] }
        else {
            func row(_ id: String, parent: Bool = true) -> [String: Any] {
                var value: [String: Any] = ["id": id, "conversationId": "room", "senderId": "peer", "content": "body", "kind": "text", "createdAt": "2026-10-22T00:00:00Z"]
                if parent { value["parentId"] = "root" }; return value
            }
            let ids = variant == "duplicate" ? ["a", "a"] : variant == "reversed" ? ["b", "a"] : ["a", "b"]
            body = ["messageWindowVersion": 1, "conversationId": "room", "threadId": "root", "parent": row("root", parent: false),
                "messages": ids.map { row($0) }, "hasOlder": true, "hasNewer": true,
                "olderCursor": ["id": "a", "createdAt": "2026-10-22T00:00:00Z"],
                "newerCursor": ["id": "b", "createdAt": "2026-10-22T00:00:00Z"]]
            if variant == "cursor" { body.removeValue(forKey: "olderCursor") }
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class MessageWindowTests: XCTestCase {
    func testMalformedWindowsAndReplyFailuresCannotBecomeThreadAuthority() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MessageWindowProtocol.self]
        let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
        for (mode, expected) in [("valid", 0), ("duplicate", -1), ("reversed", -1), ("cursor", -1),
            ("denied", 403), ("gone", 410), ("missing", 409), ("reply-gone", 409), ("proxy", 409)] {
            let api = PulseAPIClient(baseURL: URL(string: "https://message-window.test/\(mode)")!, userId: "owner", session: transport)
            do {
                let page = try await api.messageWindow(conversationId: "room", threadId: "root")
                XCTAssertEqual(expected, 0); XCTAssertEqual(page.messages.map(\.id), ["a", "b"])
            } catch let error as PulseAPIClient.Failure {
                XCTAssertNotEqual(expected, 0); XCTAssertEqual(error.status ?? -1, expected)
            }
        }
    }
}

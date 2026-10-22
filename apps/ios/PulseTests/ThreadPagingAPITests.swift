import XCTest
@testable import Pulse

private final class ThreadPagingProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "thread-page.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Thread-Mode") ?? "valid"
        let next = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "cursor" } == true
        let status: Int
        let code: String
        switch mode {
        case "old": status = 404; code = "unrelated"
        case "missing": status = 404; code = "thread_missing"
        case "denied": status = 403; code = "thread_access"
        case "proxy": status = 403; code = "unrelated"
        case "gone": status = 410; code = "thread_gone"
        case "interrupted": status = next ? 503 : 200; code = "unrelated"
        default: status = 200; code = ""
        }
        var object: [String: Any] = ["error": "failure", "code": code]
        if status == 200 {
            let root: [String: Any] = ["id": "root", "conversationId": "room", "senderId": "me", "content": "Hi", "createdAt": "2026-10-22T00:00:00Z"]
            var reply = root; reply["id"] = next ? "b" : "a"; reply["parentId"] = "root"
            object = ["parent": root, "replies": [reply], "threadPageVersion": 1, "hasMore": !next]
            if !next { object["nextCursor"] = "next" }
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: object))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ThreadPagingAPITests: XCTestCase {
    func testCompletePaginationAndAccessDenialClassification() async throws {
        for mode in ["valid", "old", "missing", "denied", "proxy", "gone", "interrupted"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [ThreadPagingProtocol.self]
            config.httpAdditionalHeaders = ["X-Thread-Mode": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://thread-page.test")!, userId: "me", session: transport)
            do {
                let result = try await api.thread(rootId: "root", userId: "me")
                XCTAssertEqual(mode, "valid"); XCTAssertEqual(result.replies.map(\.id), ["a", "b"])
            } catch let failure as PulseAPIClient.Failure {
                XCTAssertNotEqual(mode, "valid")
                let expected = ["old": 409, "missing": 404, "denied": 403, "proxy": 409, "gone": 410, "interrupted": 503][mode]
                XCTAssertEqual(failure.status, expected)
            }
        }
    }
}

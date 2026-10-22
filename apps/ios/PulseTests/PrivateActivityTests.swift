import XCTest
@testable import Pulse

private final class PrivateActivityProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "private-activity.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Test-Mode") ?? "valid"
        let owner = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "userId" })?.value
        let user: Any = mode == "orphan" ? NSNull() : ["id": mode == "row-owner" ? "other" : "me"] as Any
        let logs: [[String: Any]] = mode.hasPrefix("empty") ? [] : [["id": "a", "kind": "message", "message": "private text", "user": user]]
        var body: [String: Any] = ["activityVersion": mode == "version" ? 0 : 1, "userId": mode == "owner" ? "other" : "me", "logs": logs]
        if mode.hasSuffix("legacy") { body = ["logs": logs] }
        let status = owner == "me" ? 200 : 400
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class PrivateActivityTests: XCTestCase {
    func testOldGlobalFeedAndForeignRowsCannotReplacePrivateHistory() async throws {
        for mode in ["valid", "empty-valid", "legacy", "empty-legacy", "version", "owner", "row-owner", "orphan"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [PrivateActivityProtocol.self]
            config.httpAdditionalHeaders = ["X-Test-Mode": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://private-activity.test")!, userId: "me", session: transport)
            do {
                _ = try await api.hubLogs(limit: 80, kind: "message")
                XCTAssertTrue(["valid", "empty-valid"].contains(mode), mode)
            } catch { XCTAssertFalse(["valid", "empty-valid"].contains(mode), "Valid private history failed: \(error)") }
        }
    }
}

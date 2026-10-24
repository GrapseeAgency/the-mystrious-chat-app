import XCTest
@testable import Pulse

private final class SavedStateProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "saved-state.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Test-Mode") ?? "valid"
        let saved = request.value(forHTTPHeaderField: "X-Test-Saved") == "true"
        var body: [String: Any] = ["savedReceiptVersion": mode == "version" ? 0 : 1,
            "userId": mode == "owner" ? "other" : "me", "messageId": mode == "message" ? "other" : "m",
            "saved": mode == "state" ? !saved : saved]
        if saved || mode == "stamp" { body["savedAt"] = mode == "stamp" ? "invalid" : "2026-10-22T00:00:00Z" }
        if mode == "legacy" || request.url?.path != "/api/messages/m/save-state" { body = ["saved": saved] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class SavedStateAcknowledgementTests: XCTestCase {
    func testSaveAndRemoveNeedExactIdentityStateAndTimestampReceipts() async throws {
        for saved in [true, false] {
            for mode in ["valid", "legacy", "version", "owner", "message", "state", "stamp"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [SavedStateProtocol.self]
                config.httpAdditionalHeaders = ["X-Test-Mode": mode, "X-Test-Saved": saved ? "true" : "false"]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://saved-state.test")!, userId: "me", session: transport)
                do {
                    _ = try await api.setMessageSaved(id: "m", userId: "me", saved: saved)
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "Valid receipt failed: \(error)") }
            }
        }
    }
}

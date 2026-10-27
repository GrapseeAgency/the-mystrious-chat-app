import XCTest
@testable import Pulse

private final class DraftProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "draft.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = String(request.url!.path.split(separator: "/")[0])
        let text = mode == "clear" ? "" : String(repeating: "🌿", count: 1000)
        var receipt: [String: Any] = ["ok": true, "draftReceiptVersion": 1, "userId": "owner", "conversationId": "room", "draft": text]
        switch mode {
        case "legacy": receipt.removeValue(forKey: "draftReceiptVersion")
        case "version": receipt["draftReceiptVersion"] = "1"
        case "owner": receipt["userId"] = "other"
        case "room": receipt["conversationId"] = "other"
        case "clipped": receipt["draft"] = String(repeating: "🌿", count: 999)
        case "ok": receipt["ok"] = "true"
        default: break
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: receipt))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class DraftReceiptTests: XCTestCase {
    func testMirrorAcknowledgementRequiresTheCompleteOwnerBoundDraft() async {
        for mode in ["valid", "clear", "legacy", "version", "owner", "room", "clipped", "ok"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [DraftProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://draft.test/\(mode)")!, userId: "owner", session: transport)
            do {
                try await api.setDraft(conversationId: "room", userId: "owner", draft: mode == "clear" ? "" : String(repeating: "🌿", count: 1000))
                XCTAssertTrue(["valid", "clear"].contains(mode), mode)
            } catch { XCTAssertFalse(["valid", "clear"].contains(mode), "\(mode): \(error)") }
            do {
                try await api.setDraft(conversationId: "room", userId: "owner", draft: String(repeating: "a", count: 1999) + "🌿")
                XCTFail("Oversized mirror must not dispatch")
            } catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.kind, .validation) }
            catch { XCTFail("Wrong rejection: \(error)") }
        }
    }
}

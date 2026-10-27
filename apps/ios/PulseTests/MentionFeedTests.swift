import XCTest
@testable import Pulse

private final class MentionProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "mentions.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = String(request.url!.path.split(separator: "/")[0])
        let rows: [[String: Any]] = [
            ["messageId": "live", "conversationId": "room", "expiresAt": NSNull()],
            ["messageId": "expired", "conversationId": "room", "expiresAt": "1970-01-01T00:00:01Z"],
            ["messageId": "malformed", "conversationId": "room", "expiresAt": "invalid"],
            ["messageId": "", "conversationId": "room"],
        ]
        let valid: [String: Any] = request.url!.path.hasSuffix("/search") ? ["messages": [], "total": 0] : ["items": rows]
        let body: [String: Any] = mode == "missing" ? [:] : valid
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: mode == "error" ? 503 : 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class MentionFeedTests: XCTestCase {
    func testUnavailableFeedIsNotAnEmptySuccessAndExpiredRowsDoNotCount() async throws {
        for mode in ["valid", "missing", "error"] {
            let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MentionProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://mentions.test/\(mode)")!, userId: "owner", session: transport)
            do {
                let rows = try await api.mentions()
                XCTAssertEqual(mode, "valid"); XCTAssertEqual(rows.map(\.id), ["live"])
            } catch { XCTAssertNotEqual(mode, "valid", "\(error)") }
            let count = await api.mentionsCount()
            XCTAssertEqual(count, mode == "valid" ? 1 : nil)
        }
    }
    func testSearchDistinguishesEmptyMissingAndUnavailableAndRejectsOversizedQuery() async throws {
        for mode in ["valid", "missing", "error"] {
            let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MentionProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://mentions.test/\(mode)")!, userId: "owner", session: transport)
            do {
                let page = try await api.searchMessages("term")
                XCTAssertEqual(mode, "valid"); XCTAssertEqual(page.messages?.count, 0)
            } catch { XCTAssertNotEqual(mode, "valid", "\(error)") }
            do {
                _ = try await api.searchMessages(String(repeating: "🌿", count: 33))
                XCTFail("Oversized search was dispatched")
            } catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .validation) }
        }
    }

    func testExpiryIsReevaluatedAtDisplayAndTapTime() throws {
        let row = try JSONDecoder().decode(WireMentionEntry.self, from: Data(#"{"messageId":"m","conversationId":"r","expiresAt":"1970-01-01T00:00:01Z"}"#.utf8))
        XCTAssertTrue(row.isVisible(now: Date(timeIntervalSince1970: 0.999)))
        XCTAssertFalse(row.isVisible(now: Date(timeIntervalSince1970: 1)))
    }
}

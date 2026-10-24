import XCTest
@testable import Pulse

private final class StoryViewersProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "story-viewers.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let body = ["valid": #"{"viewers":[]}"#, "missing": "{}", "null": #"{"viewers":null}"#,
                    "blank": #"{"viewers":[{}]}"#, "duplicate": #"{"viewers":[{"userId":"a"},{"userId":"a"}]}"#][mode]!
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8)); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class StoryViewersTests: XCTestCase {
    func testMissingOrMalformedViewersCannotPretendToBeAnEmptyList() async throws {
        for mode in ["valid", "missing", "null", "blank", "duplicate"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StoryViewersProtocol.self]; config.httpAdditionalHeaders = ["X-Mode": mode]
            let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://story-viewers.test")!, userId: "owner", session: session)
            do {
                let rows = try await api.storyViewers(id: "story")
                XCTAssertEqual(mode, "valid"); XCTAssertTrue(rows.isEmpty)
            } catch { XCTAssertNotEqual(mode, "valid") }
        }
    }
    func testAccessDenialDiscardsViewersButNetworkFailureDoesNotMasqueradeAsRevocation() {
        for kind: PulseAPIClient.Failure.Kind in [.auth, .forbidden, .notFound] {
            XCTAssertTrue(StoriesSessionModel.discardsViewers(after: PulseAPIClient.Failure(kind: kind, message: nil)))
        }
        for kind: PulseAPIClient.Failure.Kind in [.network, .server, .rateLimited] {
            XCTAssertFalse(StoriesSessionModel.discardsViewers(after: PulseAPIClient.Failure(kind: kind, message: nil)))
        }
    }
}

import XCTest
@testable import Pulse

private final class StoryFeedProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "story-feed.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let status = Int(request.value(forHTTPHeaderField: "X-Status") ?? "200") ?? 200
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((request.value(forHTTPHeaderField: "X-Body") ?? "{}").utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class StoryFeedTests: XCTestCase {
    private func client(_ body: String, status: Int = 200) -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StoryFeedProtocol.self]
        config.httpAdditionalHeaders = ["X-Body": body, "X-Status": String(status)]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://story-feed.test")!, userId: "owner", session: session), session)
    }
    func testFeedRequiresAnExplicitUniqueIdentitySnapshot() async throws {
        for body in ["{}", #"{"groups":null}"#, #"{"groups":[{}]}"#,
                     #"{"groups":[{"user":{"id":"u","name":"User"}}]}"#,
                     #"{"groups":[{"user":{"id":"u","name":"User"},"stories":[{"id":"a"},{"id":"a"}]}]}"#] {
            let (api, session) = client(body); defer { session.invalidateAndCancel() }
            do { _ = try await api.storySnapshot(); XCTFail("Accepted malformed feed: \(body)") } catch { }
        }
        for body in [#"{"groups":[]}"#, #"{"groups":[{"user":{"id":"u","name":"User"},"stories":[{"id":"a"}]}]}"#] {
            let (api, session) = client(body); defer { session.invalidateAndCancel() }
            let page = try await api.storySnapshot(); XCTAssertNotNil(page.groups)
        }
    }
    func testViewMarkRequiresARealNonnegativeCount() async throws {
        for body in ["{}", #"{"viewCount":null}"#, #"{"viewCount":-1}"#, #"{"viewCount":"1"}"#, #"{"viewCount":true}"#] {
            let (api, session) = client(body); defer { session.invalidateAndCancel() }
            do { _ = try await api.markStoryViewed(id: "a"); XCTFail("Accepted malformed receipt: \(body)") } catch { }
        }
        let (api, session) = client(#"{"viewCount":0}"#); defer { session.invalidateAndCancel() }
        let count = try await api.markStoryViewed(id: "a"); XCTAssertEqual(count, 0)
    }
    func testFeedPreservesAccessFailureInsteadOfReturningNil() async {
        let (api, session) = client(#"{"error":"not allowed"}"#, status: 403); defer { session.invalidateAndCancel() }
        do { _ = try await api.storySnapshot(); XCTFail("Expected refusal") }
        catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.kind, .forbidden) }
        catch { XCTFail("Expected typed failure") }
    }
}

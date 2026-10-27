import XCTest
@testable import Pulse

private final class StoryDeleteProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "story-delete.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems
        let correct = request.httpMethod == "DELETE" && request.url?.path == "/api/stories/story" &&
            query?.first(where: { $0.name == "requesterId" })?.value == "me&peer" &&
            request.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-token"
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let body = ["valid": #"{"ok":true}"#, "empty": "{}", "false": #"{"ok":false}"#,
            "string": #"{"ok":"true"}"#, "numeric": #"{"ok":1}"#, "bad": "not json"][mode]!
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: correct ? 200 : 400,
            httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class StoryDeletionTests: XCTestCase {
    func testDeleteUsesEncodedOwnerQueryCredentialAndARealAcknowledgement() async throws {
        for mode in ["valid", "empty", "false", "string", "numeric", "bad"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StoryDeleteProtocol.self]
            config.httpAdditionalHeaders = ["X-Mode": mode]
            let session = URLSession(configuration: config)
            defer { session.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://story-delete.test")!, userId: "me&peer",
                authToken: "synthetic-token", session: session)
            do {
                try await api.deleteStory(id: "story")
                XCTAssertEqual(mode, "valid", "Unconfirmed deletion must fail")
            } catch {
                XCTAssertNotEqual(mode, "valid", "Valid owner query + acknowledgement should succeed: \(error)")
            }
        }
    }
}

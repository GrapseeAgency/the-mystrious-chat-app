import XCTest
@testable import Pulse

private final class PollAcknowledgementProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "poll.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let closing = request.url?.path.hasSuffix("/close") == true
        let mode = request.value(forHTTPHeaderField: "X-Poll-Mode") ?? "valid"
        let poll: [String: Any] = ["id": mode == "foreign" ? "other" : "poll", "question": "Question",
            "closed": closing && mode != "unconfirmed", "totalVotes": 1,
            "options": [["id": mode == "unconfirmed" ? "no" : "yes", "text": "Yes", "votedBy": ["me"], "voteCount": 1]]]
        var message: [String: Any] = ["id": "m", "conversationId": "room", "senderId": closing ? "me" : "peer",
            "kind": "poll", "content": "Question", "createdAt": "2026-10-22T00:00:00Z", "poll": poll]
        if mode == "deleted" { message["deletedAt"] = "2026-10-22T00:01:00Z" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: ["message": message]))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class PollAcknowledgementTests: XCTestCase {
    func testSuccessRequiresThisPollAndActualVoteOrClosureState() async throws {
        for closing in [true, false] {
            for mode in ["valid", "foreign", "unconfirmed", "deleted"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [PollAcknowledgementProtocol.self]
                config.httpAdditionalHeaders = ["X-Poll-Mode": mode]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://poll.test")!, userId: "me", session: transport)
                do {
                    if closing { _ = try await api.closePoll(pollId: "poll", userId: "me") }
                    else { _ = try await api.votePoll(pollId: "poll", userId: "me", optionId: "yes") }
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "Valid receipt failed: \(error)") }
            }
        }
    }
}

import XCTest
@testable import Pulse

private final class ReminderProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "reminder.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
        let owner = query.first { $0.name == "userId" }?.value
        let second = query.contains { $0.name == "cursor" }
        let code = owner == "fail" && second ? 503 : 200
        let body: String
        if owner == "loop" { body = #"{"items":[],"nextCursor":"again"}"# }
        else if code == 503 { body = #"{"error":"offline"}"# }
        else if second { body = #"{"items":[{"id":"b"}],"nextCursor":null}"# }
        else { body = #"{"items":[{"id":"a"}],"nextCursor":"next"}"# }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class ReminderPagingTests: XCTestCase {
    private func client(_ owner: String) -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ReminderProtocol.self]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://reminder.test")!, userId: owner, session: session), session)
    }
    func testAllPagesReachNativeCaller() async throws {
        let (api, session) = client("multi"); defer { session.invalidateAndCancel() }
        let result = try await api.reminders(dueOnly: false)
        XCTAssertEqual(result.items.map(\.id), ["a", "b"])
        XCTAssertNil(result.nextCursor)
    }
    func testLaterFailureCannotReturnFalseCompleteList() async {
        let (api, session) = client("fail"); defer { session.invalidateAndCancel() }
        do { _ = try await api.reminders(dueOnly: false); XCTFail("Must reject partial list") }
        catch { XCTAssertNotNil(error as? PulseAPIClient.Failure) }
    }
    func testRepeatedCursorIsRejected() async {
        let (api, session) = client("loop"); defer { session.invalidateAndCancel() }
        do { _ = try await api.reminders(dueOnly: false); XCTFail("Must reject looping cursor") }
        catch { XCTAssertEqual((error as? URLError)?.code, .badServerResponse) }
    }
}

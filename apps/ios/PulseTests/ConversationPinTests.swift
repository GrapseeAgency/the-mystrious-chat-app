import XCTest
@testable import Pulse

private final class ConversationPinProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "conversation-pin.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                data.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let expected = request.value(forHTTPHeaderField: "X-Expected-Pin") == "true"
        let valid = request.httpMethod == "PATCH" && request.url?.path == "/api/conversations/room/pin" &&
            body["userId"] as? String == "owner" && body["pinned"] as? Bool == expected
        let status = valid ? Int(request.value(forHTTPHeaderField: "X-Reply-Status") ?? "200")! : 400
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((status == 200 ? "{\"ok\":true}" : "{\"error\":\"Not confirmed\"}").utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ConversationPinTests: XCTestCase {
    func testBothPinIntentsCarryExplicitBooleanAndFailuresPropagate() async throws {
        for desired in [true, false] {
            for status in [200, 403, 503] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [ConversationPinProtocol.self]
                config.httpAdditionalHeaders = ["X-Expected-Pin": String(desired), "X-Reply-Status": String(status)]
                let session = URLSession(configuration: config)
                defer { session.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://conversation-pin.test")!, userId: "owner", session: session)
                do {
                    try await api.setPinned(conversationId: "room", pinned: desired)
                    XCTAssertEqual(status, 200)
                } catch let failure as PulseAPIClient.Failure {
                    XCTAssertEqual(failure.status, status)
                    XCTAssertNotEqual(status, 200)
                }
            }
        }
    }
}

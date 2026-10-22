import XCTest
@testable import Pulse

private final class ReactionStateProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "reaction.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.path, "/api/messages/m/react-state", "Never dispatch a legacy toggle fallback")
        var bytes = request.httpBody ?? Data()
        if bytes.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                bytes.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] ?? [:]
        let mode = request.value(forHTTPHeaderField: "X-Reaction-Mode") ?? "valid"
        let present = body["present"] as? Bool ?? false
        let confirmed = mode == "wrong-row" ? !present : present
        var result: [String: Any] = ["message": ["id": "m", "conversationId": "room", "senderId": "peer",
            "content": "Hi", "kind": "text", "createdAt": "2026-10-22T00:00:00Z",
            "reactions": confirmed ? [["emoji": "heart", "userIds": ["me"], "count": 1]] : []]]
        if mode != "missing" {
            result["reaction"] = ["messageId": mode == "wrong-id" ? "other" : "m", "emoji": "heart",
                "present": mode == "wrong-state" ? !present : present]
        }
        let valid = request.url?.path == "/api/messages/m/react-state" && body["present"] is Bool
        let status = valid && mode != "unsupported" ? 200 : 404
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: result))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ReactionStateTests: XCTestCase {
    private func transport(_ mode: String) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ReactionStateProtocol.self]
        config.httpAdditionalHeaders = ["X-Reaction-Mode": mode]
        return URLSession(configuration: config)
    }
    func testRetryPreservesDesiredStateAndRequiresMatchingReceiptAndRow() async throws {
        for present in [true, false] {
            for mode in ["valid", "missing", "wrong-state", "wrong-id", "wrong-row"] {
                let transport = transport(mode)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://reaction.test")!, userId: "me", session: transport)
                for _ in 0..<2 {
                    do {
                        let message = try await api.react(messageId: "m", emoji: "heart", present: present)
                        XCTAssertEqual(mode, "valid")
                        XCTAssertEqual((message.reactions ?? []).contains { $0.emoji == "heart" && $0.userIds.contains("me") }, present)
                    } catch {
                        XCTAssertNotEqual(mode, "valid", "Valid state response should decode: \(error)")
                    }
                }
            }
        }
    }
    func testUnsupportedGatewayRefusesWithoutToggleFallback() async throws {
        let transport = transport("unsupported")
        defer { transport.invalidateAndCancel() }
        let api = PulseAPIClient(baseURL: URL(string: "https://reaction.test")!, userId: "me", session: transport)
        do {
            _ = try await api.react(messageId: "m", emoji: "heart", present: true)
            XCTFail("Missing state route must refuse")
        } catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.status, 404) }
    }
}

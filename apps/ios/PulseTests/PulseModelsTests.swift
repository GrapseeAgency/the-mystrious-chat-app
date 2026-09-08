import XCTest
@testable import Pulse

/// Model round-trip test — JSON wire shapes must match the shared protocol
/// (packages/protocol) so web, Android and iOS parse identical payloads.
final class PulseModelsTests: XCTestCase {
    func testMessageDecodesSharedProtocolShape() throws {
        let json = """
        {"id":"m1","conversationId":"c1","authorId":"a1","authorName":"Alice Chen",
         "kind":"TEXT","body":"hello pulse","createdAt":"2025-01-01T00:00:00.000Z",
         "replyToId":null,"threadRootId":null,"pinnedAt":null,"viewedOnce":false}
        """
        let data = Data(json.utf8)
        let message = try JSONDecoder().decode(PulseMessage.self, from: data)
        XCTAssertEqual(message.body, "hello pulse")
        XCTAssertEqual(message.kind, .TEXT)
    }

    func testHTTPKindMappingMatchesAndroid() {
        XCTAssertEqual(PulseAPIClient.kind(for: 403), .forbidden)
        XCTAssertEqual(PulseAPIClient.kind(for: 429), .rateLimited)
        XCTAssertEqual(PulseAPIClient.kind(for: 502), .server)
    }
}

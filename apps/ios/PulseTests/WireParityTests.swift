import XCTest
@testable import Pulse

/// Wire-parity tests — the JSON here is the exact shape the live gateway
/// emits (captured from :81), mirrored by Android's LiveGatewayParityTest
/// and packages/protocol. iOS decodes it without loss.
final class WireParityTests: XCTestCase {
    func testConversationSummaryDecodesRealShape() throws {
        let json = """
        {"id":"cmtn1844z0000nhhcxv5i1f8p","isGroup":true,"name":"Bot & Webhook QA",
         "createdAt":"2026-09-04T14:11:18.131Z","updatedAt":"2026-09-07T16:01:51.367Z",
         "members":[{"id":"u1","name":"Alice Chen","username":"alicechen","color":"rose",
                     "avatar":null,"statusEmoji":null,"statusText":null,
                     "lastReadAt":"2026-09-07T20:12:21.163Z","role":"admin"}],
         "unreadCount":0,"pinnedAt":null,"mutedUntil":null,"archivedAt":null,
         "ttlSeconds":null,"broadcastMode":null,"isSelf":false,
         "myDraft":"","myStreak":{"count":3},"deadStreak":{"count":0},"lostStreak":null}
        """
        let conv = try JSONDecoder().decode(WireConversationSummary.self, from: Data(json.utf8))
        XCTAssertEqual(conv.toDomain().title, "Bot & Webhook QA")
        XCTAssertEqual(conv.toDomain().kind, .GROUP)
        XCTAssertEqual(conv.myStreak?.count, 3)
    }

    func testMessageRoundTripAndKindMapping() throws {
        let json = """
        {"id":"m1","conversationId":"c1","senderId":"a1","content":"hello pulse",
         "kind":"text","createdAt":"2026-09-07T12:26:36.991Z","deletedAt":null,
         "sender":{"id":"a1","name":"Bob","username":"bob","color":"emerald","avatar":null},
         "reactions":[],"replyTo":null,"imagePath":null,"audioPath":null,
         "durationMs":null,"filePath":null,"fileName":null,"fileSize":null,
         "linkPreview":null,"linkUrl":null,"parentId":null,"pinnedAt":null,
         "poll":null,"topicId":null,"transcript":null,"transcribedAt":null,
         "translations":null,"viaAutomation":false,"viewOnce":false,
         "viewedAt":null,"viewedBy":[],"anon":false,"anonAlias":null,"expiresAt":null,
         "editedAt":null}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        let domain = msg.toDomain()
        XCTAssertEqual(domain.body, "hello pulse")
        XCTAssertEqual(domain.kind, .TEXT)
        XCTAssertEqual(domain.authorName, "Bob")
    }

    func testHTTPKindMappingMatchesAndroid() {
        XCTAssertEqual(PulseAPIClient.kind(for: 403), .forbidden)
        XCTAssertEqual(PulseAPIClient.kind(for: 429), .rateLimited)
        XCTAssertEqual(PulseAPIClient.kind(for: 502), .server)
    }
}

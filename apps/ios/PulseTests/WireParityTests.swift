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

    // ── W1-DATA-B — Wave 1 wire additions ───────────────────

    /// A thread reply with every Wave 1 field populated + unknown keys the
    /// relay already sends (linkPreview/poll/transcripts/…) and keys from
    /// the FUTURE — decoding must stay lossless and tolerant.
    func testThreadReplyAndMediaFieldsDecodeWithUnknownKeys() throws {
        let json = """
        {"id":"m-r1","conversationId":"c1","senderId":"a1","content":"thread reply",
         "kind":"text","createdAt":"2026-09-07T12:26:36.991Z",
         "parentId":"m-root","replyTo":null,
         "imagePath":null,"audioPath":null,"durationMs":null,
         "filePath":"uploads/doc.pdf","fileName":"doc.pdf","fileSize":2048,
         "editedAt":"2026-09-07T13:00:00.000Z","deletedAt":null,
         "pinnedAt":"2026-09-07T14:00:00.000Z","viewOnce":false,
         "reactions":[{"emoji":"👍","userIds":["u1"],"count":1}],
         "linkPreview":null,"linkUrl":null,"poll":null,"topicId":null,
         "transcript":null,"transcribedAt":null,"translations":null,
         "viaAutomation":true,"viewedAt":null,"viewedBy":[],"anon":false,
         "anonAlias":null,"expiresAt":null,
         "someFutureField":{"nested":{"deep":[1,2,3]}}}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        XCTAssertEqual(msg.parentId, "m-root")
        XCTAssertEqual(msg.fileSize, 2048)
        XCTAssertEqual(msg.filePath, "uploads/doc.pdf")
        XCTAssertEqual(msg.fileName, "doc.pdf")
        XCTAssertEqual(msg.editedAt, "2026-09-07T13:00:00.000Z")
        XCTAssertEqual(msg.pinnedAt, "2026-09-07T14:00:00.000Z")
        XCTAssertEqual(msg.viewOnce, false)
        XCTAssertEqual(msg.reactions?.first?.emoji, "👍")
        XCTAssertEqual(msg.reactions?.first?.userIds, ["u1"])

        // Spec §2 row 5 — parentId decodes into the THREAD ROOT slot;
        // replyToId is reserved for the inline-quote snippet (nil here).
        let domain = msg.toDomain()
        XCTAssertEqual(domain.threadRootId, "m-root")
        XCTAssertNil(domain.replyToId)
    }

    /// An inline quote (replyTo) without parentId must NOT set threadRootId —
    /// the two concepts stay separated end to end.
    func testInlineQuoteDoesNotConflateWithThreadRoot() throws {
        let json = """
        {"id":"m-q","conversationId":"c1","senderId":"a1","content":"quoted reply",
         "kind":"text","createdAt":"2026-09-07T12:26:36.991Z","parentId":null,
         "replyTo":{"id":"m-quoted","content":"original","senderName":"Ada","deleted":false}}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        let domain = msg.toDomain()
        XCTAssertEqual(domain.replyToId, "m-quoted")
        XCTAssertNil(domain.threadRootId)
    }

    /// GET /api/messages/{id}/thread — the exact {parent, replies} page the
    /// route returns (replies asc on the wire).
    func testThreadPageDecodes() throws {
        let json = """
        {"parent":{"id":"m-root","conversationId":"c1","senderId":"a1",
                   "content":"root message","kind":"text",
                   "createdAt":"2026-09-07T12:00:00.000Z","reactions":[]},
         "replies":[{"id":"m-r1","conversationId":"c1","senderId":"a2",
                     "content":"first reply","kind":"text",
                     "createdAt":"2026-09-07T12:05:00.000Z","parentId":"m-root"},
                    {"id":"m-r2","conversationId":"c1","senderId":"a1",
                     "content":"second reply","kind":"text",
                     "createdAt":"2026-09-07T12:09:00.000Z","parentId":"m-root"}]}
        """
        let page = try JSONDecoder().decode(WireThreadPage.self, from: Data(json.utf8))
        XCTAssertEqual(page.parent.id, "m-root")
        XCTAssertEqual(page.replies.map(\.id), ["m-r1", "m-r2"])
        XCTAssertEqual(page.replies.first?.parentId, "m-root")
    }

    /// POST /api/messages/{id}/save → {saved} and POST /api/uploads →
    /// {filePath, imagePath} — small envelopes, tolerant of extra keys.
    func testSavedToggleAndUploadResultDecode() throws {
        let saved = try JSONDecoder().decode(
            WireSavedToggle.self,
            from: Data(#"{"saved":true,"extra":"ignored"}"#.utf8),
        )
        XCTAssertTrue(saved.saved)
        let unsaved = try JSONDecoder().decode(
            WireSavedToggle.self,
            from: Data(#"{"saved":false}"#.utf8),
        )
        XCTAssertFalse(unsaved.saved)

        let upload = try JSONDecoder().decode(
            WireUploadResult.self,
            from: Data(#"{"filePath":"uploads/abc.jpg","imagePath":"uploads/abc.jpg","mime":"image/jpeg"}"#.utf8),
        )
        XCTAssertEqual(upload.filePath, "uploads/abc.jpg")
        XCTAssertEqual(upload.imagePath, "uploads/abc.jpg")
    }
}

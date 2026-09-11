import XCTest
@testable import Pulse

/// Wave 2 data-layer wire tests (W2-DATA-B) — pure JSON decode checks against
/// the frozen backend contract (spec §0): poll/link-preview/saved/topic/
/// transcribe shapes, the Wave 2 fields on WireChatMessage (populated AND
/// absent — backward compatibility), and the `{message: null}` unfurl
/// envelope. No network, no mic.
final class Wave2WireTests: XCTestCase {
    // ── WirePoll — votedBy is the pick source, myOptionId is NOT ──

    /// Realistic relayed poll row (spec §0 shape) — myOptionId deliberately
    /// CONTRADICTS the votedBy arrays to prove the derivation ignores it.
    func testWirePollDecodesAndPickForIgnoresMyOptionId() throws {
        let json = """
        {"id":"poll-1","question":"Ship wave 2?","closed":false,
         "options":[{"id":"opt-a","text":"Yes","position":0,"voteCount":2,"votedBy":["u1","u2"]},
                    {"id":"opt-b","text":"Not yet","position":1,"voteCount":1,"votedBy":["u9"]}],
         "totalVotes":3,"myOptionId":"opt-b"}
        """
        let poll = try JSONDecoder().decode(WirePoll.self, from: Data(json.utf8))
        XCTAssertEqual(poll.id, "poll-1")
        XCTAssertEqual(poll.question, "Ship wave 2?")
        XCTAssertEqual(poll.closed, false)
        XCTAssertEqual(poll.totalVotes, 3)
        XCTAssertEqual(poll.options?.count, 2)
        XCTAssertEqual(poll.options?.first?.position, 0)
        XCTAssertEqual(poll.options?.first?.voteCount, 2)

        // Spec §1 row 2 — the pick comes from votedBy ONLY. myOptionId is
        // actor-relative on relayed rows (here it names the ACTOR's pick,
        // not u1's) so it must never leak into anyone's "my vote".
        XCTAssertEqual(poll.pickFor("u1"), "opt-a")
        XCTAssertEqual(poll.pickFor("u2"), "opt-a")
        XCTAssertEqual(poll.pickFor("u9"), "opt-b")
        XCTAssertNotEqual(poll.pickFor("u1"), poll.myOptionId)
        XCTAssertNil(poll.pickFor("u3")) // has not voted
        XCTAssertNil(poll.pickFor(nil)) // no viewer identity
    }

    func testWirePollToleratesAbsencesAndEmptyVotes() throws {
        // History GETs answer myOptionId null; older relays may omit
        // closed/totalVotes entirely — decode must stay lossless-tolerant.
        let json = """
        {"id":"poll-2","question":"Lunch?",
         "options":[{"id":"o1","text":"Ramen","votedBy":[]}]}
        """
        let poll = try JSONDecoder().decode(WirePoll.self, from: Data(json.utf8))
        XCTAssertNil(poll.closed)
        XCTAssertNil(poll.totalVotes)
        XCTAssertNil(poll.myOptionId)
        XCTAssertEqual(poll.options?.first?.votedBy, [])
        XCTAssertNil(poll.pickFor("u1"))
        XCTAssertNil(poll.pickFor(nil))
    }

    // ── WireLinkPreview — the Open-Graph card ────────────────

    func testWireLinkPreviewDecodesPopulatedAndNullish() throws {
        let json = """
        {"url":"https://example.com/","title":"Example Domain",
         "description":"This domain is for use in illustrative examples",
         "imageUrl":"https://example.com/og.png","siteName":"example.com"}
        """
        let preview = try JSONDecoder().decode(WireLinkPreview.self, from: Data(json.utf8))
        XCTAssertEqual(preview.url, "https://example.com/")
        XCTAssertEqual(preview.title, "Example Domain")
        XCTAssertEqual(preview.description, "This domain is for use in illustrative examples")
        XCTAssertEqual(preview.imageUrl, "https://example.com/og.png")
        XCTAssertEqual(preview.siteName, "example.com")

        // The unfurl route nulls whatever og:* it could not find.
        let nullish = try JSONDecoder().decode(
            WireLinkPreview.self,
            from: Data(#"{"url":"https://example.com","title":null,"description":null,"imageUrl":null,"siteName":null}"#.utf8),
        )
        XCTAssertEqual(nullish.url, "https://example.com")
        XCTAssertNil(nullish.title)
        XCTAssertNil(nullish.imageUrl)
    }

    // ── WireSavedItem — nested conversation + message ────────

    /// GET /api/users/{id}/saved item — the exact nesting the route emits
    /// (savedAt + resolved conversation display info + full message row).
    func testWireSavedItemDecodesNestedShapes() throws {
        let json = """
        {"savedAt":"2026-09-08T10:00:00.000Z",
         "conversation":{"id":"c1","isGroup":true,"name":"Wave 2 QA"},
         "message":{"id":"m1","conversationId":"c1","senderId":"a1","content":"",
                    "kind":"poll","createdAt":"2026-09-08T09:59:00.000Z",
                    "poll":{"id":"poll-1","question":"Ship?","closed":false,
                            "options":[{"id":"o1","text":"Yes","position":0,"voteCount":1,"votedBy":["u1"]}],
                            "totalVotes":1,"myOptionId":null}}}
        """
        let item = try JSONDecoder().decode(WireSavedItem.self, from: Data(json.utf8))
        XCTAssertEqual(item.savedAt, "2026-09-08T10:00:00.000Z")
        XCTAssertEqual(item.conversation.id, "c1")
        XCTAssertEqual(item.conversation.isGroup, true)
        XCTAssertEqual(item.conversation.name, "Wave 2 QA")
        XCTAssertEqual(item.message.kind, "poll")
        XCTAssertEqual(item.message.poll?.question, "Ship?")
        XCTAssertEqual(item.message.poll?.pickFor("u1"), "o1")

        // The page wrapper tolerates a missing/empty items array.
        let page = try JSONDecoder().decode(
            WireSavedPage.self,
            from: Data(#"{"items":[{"savedAt":"2026-09-08T10:00:00.000Z","conversation":{"id":"c1","isGroup":true,"name":"QA"},"message":{"id":"m1","conversationId":"c1","senderId":"a1","content":"x","kind":"text","createdAt":"2026-09-08T09:59:00.000Z"}}]}"#.utf8),
        )
        XCTAssertEqual(page.items?.count, 1)
        let empty = try JSONDecoder().decode(WireSavedPage.self, from: Data("{}".utf8))
        XCTAssertNil(empty.items)
    }

    // ── WireTopic — the Zulip-style rail row ─────────────────

    func testWireTopicPageAndEnvelopeDecode() throws {
        let topicJson = """
        {"id":"t1","name":"Design","emoji":"🎨","lastMessageAt":"2026-09-08T11:00:00.000Z","messageCount":12}
        """
        let topic = try JSONDecoder().decode(WireTopic.self, from: Data(topicJson.utf8))
        XCTAssertEqual(topic.id, "t1")
        XCTAssertEqual(topic.name, "Design")
        XCTAssertEqual(topic.emoji, "🎨")
        XCTAssertEqual(topic.lastMessageAt, "2026-09-08T11:00:00.000Z")
        XCTAssertEqual(topic.messageCount, 12)

        let page = try JSONDecoder().decode(
            WireTopicsPage.self,
            from: Data(#"{"topics":[{"id":"t1","name":"Design","emoji":"🎨","lastMessageAt":"2026-09-08T11:00:00.000Z","messageCount":12}]}"#.utf8),
        )
        XCTAssertEqual(page.topics?.count, 1)
        let empty = try JSONDecoder().decode(WireTopicsPage.self, from: Data("{}".utf8))
        XCTAssertNil(empty.topics)

        // POST …/topics answers { topic } on both 200-dedupe and 201-create;
        // the envelope unwraps it (bare-object fallback stays defensive).
        let wrapped = try WireTopicEnvelope.extract(
            from: Data(#"{"topic":{"id":"t2","name":"QA","emoji":"💬","lastMessageAt":"2026-09-08T11:30:00.000Z","messageCount":0}}"#.utf8),
        )
        XCTAssertEqual(wrapped.id, "t2")
        let bare = try WireTopicEnvelope.extract(
            from: Data(#"{"id":"t2","name":"QA","emoji":"💬","lastMessageAt":"2026-09-08T11:30:00.000Z","messageCount":0}"#.utf8),
        )
        XCTAssertEqual(bare.name, "QA")
    }

    // ── WireTranscribeResult + WireOk — small verdict envelopes ──

    func testWireTranscribeResultDecode() throws {
        let fresh = try JSONDecoder().decode(
            WireTranscribeResult.self,
            from: Data(#"{"transcript":"hello world","transcribedAt":"2026-09-08T11:30:00.000Z","cached":false}"#.utf8),
        )
        XCTAssertEqual(fresh.transcript, "hello world")
        XCTAssertEqual(fresh.transcribedAt, "2026-09-08T11:30:00.000Z")
        XCTAssertEqual(fresh.cached, false)

        // Cache-hit path (second call never re-bills the ASR service).
        let hit = try JSONDecoder().decode(
            WireTranscribeResult.self,
            from: Data(#"{"transcript":"cached text","transcribedAt":"2026-09-08T11:31:00.000Z","cached":true}"#.utf8),
        )
        XCTAssertEqual(hit.cached, true)

        // Minimal shape still decodes (optionals absent).
        let minimal = try JSONDecoder().decode(
            WireTranscribeResult.self,
            from: Data(#"{"transcript":"x"}"#.utf8),
        )
        XCTAssertNil(minimal.transcribedAt)
        XCTAssertNil(minimal.cached)
    }

    func testWireOkDecode() throws {
        let ok = try JSONDecoder().decode(WireOk.self, from: Data(#"{"ok":true,"extra":1}"#.utf8))
        XCTAssertEqual(ok.ok, true)
        let empty = try JSONDecoder().decode(WireOk.self, from: Data("{}".utf8))
        XCTAssertNil(empty.ok)
    }

    // ── WireChatMessage — Wave 2 fields populated AND absent ──

    /// Every Wave 2 field populated on one row (view-once burn stamp, cached
    /// transcript, topic filing, unfurl pair, poll card) — full-loss decode.
    func testWireChatMessageDecodesWave2FieldsPopulated() throws {
        let json = """
        {"id":"m-wave2","conversationId":"c1","senderId":"a1",
         "content":"check https://example.com","kind":"text",
         "createdAt":"2026-09-08T12:00:00.000Z","editedAt":null,"deletedAt":null,
         "sender":{"id":"a1","name":"Ada","username":"ada","color":"rose","avatar":null},
         "reactions":[],"replyTo":null,"parentId":null,"imagePath":null,
         "audioPath":null,"durationMs":null,"filePath":null,"fileName":null,
         "fileSize":null,"pinnedAt":null,"viewOnce":false,"anon":false,"anonAlias":null,
         "viewedAt":"2026-09-08T12:01:00.000Z","viewedBy":"u9",
         "transcript":"voice text","transcribedAt":"2026-09-08T12:02:00.000Z",
         "topicId":"t1","linkUrl":"https://example.com",
         "linkPreview":{"url":"https://example.com","title":"Example","description":"desc",
                        "imageUrl":"https://example.com/og.png","siteName":"example.com"},
         "poll":{"id":"poll-1","question":"Ship?","closed":false,
                 "options":[{"id":"o1","text":"Yes","position":0,"voteCount":1,"votedBy":["u1"]},
                            {"id":"o2","text":"No","position":1,"voteCount":0,"votedBy":[]}],
                 "totalVotes":1,"myOptionId":null}}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        XCTAssertEqual(msg.id, "m-wave2")
        XCTAssertEqual(msg.viewedAt, "2026-09-08T12:01:00.000Z")
        // viewedBy is a SINGLE userId string on this backend (prisma
        // Message.viewedBy String?) — never an array.
        XCTAssertEqual(msg.viewedBy, "u9")
        XCTAssertEqual(msg.transcript, "voice text")
        XCTAssertEqual(msg.transcribedAt, "2026-09-08T12:02:00.000Z")
        XCTAssertEqual(msg.topicId, "t1")
        XCTAssertEqual(msg.linkUrl, "https://example.com")
        XCTAssertEqual(msg.linkPreview?.siteName, "example.com")
        XCTAssertEqual(msg.poll?.pickFor("u1"), "o1")
        XCTAssertEqual(msg.poll?.pickFor("a1"), nil) // sender has not voted
        XCTAssertNil(msg.poll?.pickFor(nil))
    }

    /// Old-relay payload with NO Wave 2 keys at all — decode must succeed
    /// with every new field nil (additive wire, backward compatible).
    func testWireChatMessageBackwardCompatibleWithoutWave2Fields() throws {
        let json = """
        {"id":"m-old","conversationId":"c1","senderId":"a1","content":"hello",
         "kind":"text","createdAt":"2026-09-07T12:26:36.991Z",
         "sender":{"id":"a1","name":"Bob","username":"bob","color":"emerald","avatar":null}}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        XCTAssertEqual(msg.content, "hello")
        XCTAssertNil(msg.viewedAt)
        XCTAssertNil(msg.viewedBy)
        XCTAssertNil(msg.transcript)
        XCTAssertNil(msg.transcribedAt)
        XCTAssertNil(msg.topicId)
        XCTAssertNil(msg.linkUrl)
        XCTAssertNil(msg.linkPreview)
        XCTAssertNil(msg.poll)
    }

    /// Unknown future keys next to Wave 2 keys must stay tolerated (the wire
    /// carries translations/viaAutomation/expiresAt and friends today).
    func testWireChatMessageToleratesUnknownKeysBesideWave2Fields() throws {
        let json = """
        {"id":"m-future","conversationId":"c1","senderId":"a1","content":"hi",
         "kind":"text","createdAt":"2026-09-08T12:00:00.000Z",
         "topicId":null,"poll":null,"linkPreview":null,"linkUrl":null,
         "viewedAt":null,"viewedBy":null,"transcript":null,"transcribedAt":null,
         "translations":[],"viaAutomation":false,"expiresAt":null,
         "someFutureField":{"nested":[1,2,3]}}
        """
        let msg = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        XCTAssertEqual(msg.id, "m-future")
        XCTAssertNil(msg.poll)
        XCTAssertNil(msg.topicId)
    }

    // ── WireMessageEnvelope — the {message: null} unfurl shape ──

    /// POST /api/messages/{id}/unfurl answers `{ message: null }` when
    /// nothing was unfurled. The STRICT extract throws on that body (its
    /// bare-object fallback cannot decode the wrapper) — the LENIENT
    /// extractOptional returns nil, which is exactly what unfurl needs.
    func testMessageEnvelopeHandlesNullMessage() throws {
        let nullBody = Data(#"{"message":null}"#.utf8)
        XCTAssertNil(WireMessageEnvelope.extractOptional(from: nullBody))
        XCTAssertThrowsError(try WireMessageEnvelope.extract(from: nullBody))

        // Bare message object + wrapped shape both still work leniently.
        let bare = Data(
            #"{"id":"m1","conversationId":"c1","senderId":"a1","content":"hi","kind":"text","createdAt":"2026-09-08T12:00:00.000Z"}"#.utf8,
        )
        XCTAssertEqual(WireMessageEnvelope.extractOptional(from: bare)?.id, "m1")
        let wrapped = Data(
            #"{"message":{"id":"m2","conversationId":"c1","senderId":"a1","content":"yo","kind":"text","createdAt":"2026-09-08T12:00:00.000Z"},"xpAwarded":5}"#.utf8,
        )
        XCTAssertEqual(WireMessageEnvelope.extractOptional(from: wrapped)?.id, "m2")

        // Garbage degrades to nil — never a crash.
        XCTAssertNil(WireMessageEnvelope.extractOptional(from: Data("not json".utf8)))
    }
}

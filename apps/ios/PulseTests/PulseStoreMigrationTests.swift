import XCTest
@testable import Pulse

/// Wave 0 + Wave 1 — GRDB migration + offline read-path tests.
/// The v1 schema (conversation/message), the v2 additions (outbox/draft) and
/// the v3 additions (full-fidelity message columns) share one non-destructive
/// migrator; a fresh open applies all in order and a reopen must be a no-op
/// that keeps every row readable.
final class PulseStoreMigrationTests: XCTestCase {
    // ── fixture helpers ──────────────────────────────────────

    private func makeConversation(id: String) -> PulseConversation {
        PulseConversation(
            id: id,
            kind: .DM,
            title: "Ada Lovelace",
            avatar: nil,
            lastMessagePreview: "hello pulse",
            lastActivityAt: "2026-09-07T12:26:36.991Z",
            unreadCount: 2,
            isPinned: true,
            isMuted: false,
            isArchived: false,
        )
    }

    private func makeMessage(id: String, conversationId: String, content: String = "cached body") -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: "u1",
            content: content,
            kind: "text",
            createdAt: "2026-09-07T12:26:36.991Z",
            editedAt: nil,
            deletedAt: nil,
            sender: WireSender(id: "u1", name: "Ada", username: nil, color: nil, avatar: nil),
            reactions: nil,
            replyTo: nil,
            parentId: nil,
            imagePath: nil,
            audioPath: nil,
            durationMs: nil,
            filePath: nil,
            fileName: nil,
            fileSize: nil,
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: nil,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }

    /// Full-fidelity v3 row — every new column populated (thread reply +
    /// media + edit stamp + pin + grouped reactions + sender color).
    private func makeV3Message(id: String, conversationId: String, parentId: String?, createdAt: String) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: "u2",
            content: "v3 body \(id)",
            kind: "image",
            createdAt: createdAt,
            editedAt: "2026-09-07T13:00:00.000Z",
            deletedAt: nil,
            sender: WireSender(id: "u2", name: "Grace", username: nil, color: "amber", avatar: nil),
            reactions: [
                WireReactionGroup(emoji: "👍", userIds: ["u1", "u2"], count: 2),
                WireReactionGroup(emoji: "🎉", userIds: ["u9"], count: 1),
            ],
            replyTo: nil,
            parentId: parentId,
            imagePath: "uploads/pic.jpg",
            audioPath: nil,
            durationMs: nil,
            filePath: "uploads/doc.pdf",
            fileName: "doc.pdf",
            fileSize: 2048,
            pinnedAt: "2026-09-07T14:00:00.000Z",
            viewOnce: false,
            anon: false,
            anonAlias: nil,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: nil,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }

    /// Full-fidelity v4 row — a filed poll message with burn stamp, cached
    /// transcription and a link preview (every Wave 2 column populated).
    private func makeV4Message(id: String, conversationId: String, createdAt: String) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: "u3",
            content: "",
            kind: "poll",
            createdAt: createdAt,
            editedAt: nil,
            deletedAt: nil,
            sender: WireSender(id: "u3", name: "Linus", username: nil, color: "teal", avatar: nil),
            reactions: nil,
            replyTo: nil,
            parentId: nil,
            imagePath: nil,
            audioPath: nil,
            durationMs: nil,
            filePath: nil,
            fileName: nil,
            fileSize: nil,
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
            viewedAt: "2026-09-08T12:01:00.000Z",
            viewedBy: "u9",
            transcript: "spoken words",
            transcribedAt: "2026-09-08T12:02:00.000Z",
            topicId: "topic-1",
            linkUrl: "https://example.com",
            linkPreview: WireLinkPreview(
                url: "https://example.com",
                title: "Example Domain",
                description: "An example",
                imageUrl: "https://example.com/og.png",
                siteName: "example.com",
            ),
            poll: WirePoll(
                id: "poll-1",
                question: "Ship it?",
                closed: false,
                options: [
                    WirePollOption(id: "o1", text: "Yes", position: 0, voteCount: 1, votedBy: ["u1"]),
                    WirePollOption(id: "o2", text: "No", position: 1, voteCount: 0, votedBy: nil),
                ],
                totalVotes: 1,
                myOptionId: nil,
            ),
        )
    }

    // ── v2 round-trips ───────────────────────────────────────

    func testV2TablesRoundTripAlongsideV1Data() throws {
        let store = try PulseStore()

        // v1 write paths (already shipped).
        try store.upsert(conversations: [makeConversation(id: "c1")])
        try store.upsert(messages: [makeMessage(id: "m1", conversationId: "c1")])

        // v2 write paths (Wave 0).
        try store.appendOutbox(conversationId: "c1", clientId: "cid-1", content: "queued one", kind: "text")
        try store.saveDraft(conversationId: "c1", text: "hello draft")

        // v1 read paths survive the v2 migration.
        let cached = try store.messages(conversationId: "c1")
        XCTAssertEqual(cached.count, 1)
        XCTAssertEqual(cached.first?.content, "cached body")
        let convs = try store.cachedConversations()
        XCTAssertEqual(convs.count, 1)
        XCTAssertEqual(convs.first?.title, "Ada Lovelace")

        // Outbox round-trip.
        let rows = try store.outboxAll()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.conversationId, "c1")
        XCTAssertEqual(rows.first?.clientId, "cid-1")
        XCTAssertEqual(rows.first?.content, "queued one")
        XCTAssertEqual(rows.first?.kind, "text")
        XCTAssertEqual(rows.first?.attempts, 0)

        try store.bumpOutboxAttempts(id: try XCTUnwrap(rows.first?.id))
        XCTAssertEqual(try store.outboxAll().first?.attempts, 1)
        try store.deleteOutbox(id: try XCTUnwrap(rows.first?.id))
        XCTAssertEqual(store.countOutbox(), 0)

        // Draft round-trip.
        XCTAssertEqual(store.draft(conversationId: "c1"), "hello draft")
        try store.deleteDraft(conversationId: "c1")
        XCTAssertNil(store.draft(conversationId: "c1"))
    }

    func testDraftUpsertAndBlankClears() throws {
        let store = try PulseStore()
        try store.saveDraft(conversationId: "c1", text: "first")
        try store.saveDraft(conversationId: "c1", text: "second draft")
        XCTAssertEqual(store.draft(conversationId: "c1"), "second draft")
        XCTAssertEqual(store.allDrafts(), ["c1": "second draft"])

        // A blank draft clears the row — it must never shadow myDraft.
        try store.saveDraft(conversationId: "c1", text: "   ")
        XCTAssertNil(store.draft(conversationId: "c1"))
        XCTAssertEqual(store.allDrafts(), [:])
    }

    func testOutboxClientIdDedupes() throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "same", content: "one", kind: "text")
        XCTAssertThrowsError(try store.appendOutbox(conversationId: "c1", clientId: "same", content: "two", kind: "text"))
        XCTAssertEqual(store.countOutbox(), 1)
    }

    // ── migration ordering / reopen survival ────────────────

    func testReopenKeepsV1AndV2RowsReadable() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pulse-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("pulse.sqlite").path

        // First open — v1 + v2 migrations apply together, data is written.
        do {
            let store = try PulseStore(path: path)
            try store.upsert(conversations: [makeConversation(id: "c1")])
            try store.upsert(messages: [makeMessage(id: "m1", conversationId: "c1")])
            try store.appendOutbox(conversationId: "c1", clientId: "cid-1", content: "queued", kind: "text")
            try store.saveDraft(conversationId: "c1", text: "survives reopen")
        }

        // Second open — the migrator is idempotent (no destructive reset);
        // every row written before the reopen must still be readable.
        let reopened = try PulseStore(path: path)
        let convs = try reopened.cachedConversations()
        XCTAssertEqual(convs.map(\.id), ["c1"])
        XCTAssertEqual(try reopened.messages(conversationId: "c1").map(\.id), ["m1"])
        XCTAssertEqual(try reopened.outboxAll().map(\.clientId), ["cid-1"])
        XCTAssertEqual(reopened.draft(conversationId: "c1"), "survives reopen")
    }

    // ── v3 (W1-DATA-B) — full-fidelity cache + threads ──────

    /// The closest thing to a v2→v3 upgrade this suite can run without
    /// linking GRDB in the test target: rows written with every v3 column
    /// nil are byte-for-byte what a migrated v2 row looks like after the
    /// ALTERs (NULLs + reactionsJson default '[]'). They must survive a
    /// reopen alongside full-fidelity v3 rows, with every column usable.
    func testV3ColumnsRoundTripAndLegacyRowsSurviveReopen() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pulse-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("pulse.sqlite").path

        do {
            let store = try PulseStore(path: path)
            try store.upsert(conversations: [makeConversation(id: "c1")])
            // v2-era row: only v1/v2 columns populated.
            try store.upsert(messages: [makeMessage(id: "m-legacy", conversationId: "c1")])
            // Full-fidelity v3 rows: a thread root + a rich reply.
            try store.upsert(messages: [
                makeMessage(id: "t-root", conversationId: "c1"),
                makeV3Message(id: "t-r1", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:00.000Z"),
                makeV3Message(id: "t-r2", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:01.000Z"),
            ])
        }

        let reopened = try PulseStore(path: path)
        let rows = try reopened.messages(conversationId: "c1")
        // t-root and m-legacy share a createdAt stamp, so their relative
        // order is unspecified — assert membership, not sequence.
        XCTAssertEqual(rows.count, 4)
        XCTAssertEqual(Set(rows.map(\.id)), ["t-root", "t-r1", "t-r2", "m-legacy"])

        // Legacy row: exactly the v2 shape — v3 columns stay NULL/empty and
        // the old replyToId→parentId conflation does NOT resurrect.
        let legacy = try XCTUnwrap(rows.first { $0.id == "m-legacy" })
        XCTAssertEqual(legacy.content, "cached body")
        XCTAssertNil(legacy.parentId)
        XCTAssertNil(legacy.editedAt)
        XCTAssertNil(legacy.deletedAt)
        XCTAssertNil(legacy.imagePath)
        XCTAssertNil(legacy.reactions)

        // Full-fidelity row: every v3 column round-trips through GRDB.
        let rich = try XCTUnwrap(rows.first { $0.id == "t-r1" })
        XCTAssertEqual(rich.parentId, "t-root")
        XCTAssertEqual(rich.editedAt, "2026-09-07T13:00:00.000Z")
        XCTAssertEqual(rich.imagePath, "uploads/pic.jpg")
        XCTAssertEqual(rich.filePath, "uploads/doc.pdf")
        XCTAssertEqual(rich.fileName, "doc.pdf")
        XCTAssertEqual(rich.fileSize, 2048)
        XCTAssertEqual(rich.pinnedAt, "2026-09-07T14:00:00.000Z")
        XCTAssertEqual(rich.sender?.name, "Grace")
        XCTAssertEqual(rich.sender?.color, "amber")
        XCTAssertEqual(rich.reactions?.count, 2)
        XCTAssertEqual(rich.reactions?.first?.emoji, "👍")
        XCTAssertEqual(rich.reactions?.first?.userIds, ["u1", "u2"])
        XCTAssertEqual(rich.reactions?.last?.userIds, ["u9"])
    }

    func testThreadRepliesQueryAndReplyCounts() throws {
        let store = try PulseStore()
        try store.upsert(messages: [
            makeMessage(id: "t-root", conversationId: "c1"),
            makeV3Message(id: "t-r1", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:00.000Z"),
            makeV3Message(id: "t-r2", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:01.000Z"),
            // An unrelated river row and a different root's reply.
            makeMessage(id: "river", conversationId: "c1"),
            makeV3Message(id: "o-r1", conversationId: "c1", parentId: "other-root", createdAt: "2026-09-07T13:00:02.000Z"),
        ])

        let replies = try store.messages(threadRootId: "t-root")
        XCTAssertEqual(replies.map(\.id), ["t-r1", "t-r2"])
        XCTAssertTrue(replies.allSatisfy { $0.parentId == "t-root" })

        let counts = try store.threadReplyCounts()
        XCTAssertEqual(counts["t-root"], 2)
        XCTAssertEqual(counts["other-root"], 1)
        XCTAssertNil(counts["t-root-nope"])
        // Root + river rows are not counted anywhere.
        XCTAssertEqual(try store.messages(threadRootId: "missing").count, 0)

        // The main conversation read still returns every row (river rows
        // keep parentId nil; filtering happens in the UI layer).
        XCTAssertEqual(try store.messages(conversationId: "c1").count, 5)
    }

    func testReactionsJsonCodecRoundTrip() throws {
        let groups = [
            WireReactionGroup(emoji: "👍", userIds: ["u1", "u2"], count: 2),
            WireReactionGroup(emoji: "🎉", userIds: ["u9"], count: 1),
        ]
        let json = PulseStore.reactionsJsonData(groups)
        XCTAssertEqual(PulseStore.reactions(fromJson: json), groups)

        // nil/empty encode to the column default; default/garbage decode empty.
        XCTAssertEqual(PulseStore.reactionsJsonData(nil), "[]")
        XCTAssertEqual(PulseStore.reactionsJsonData([]), "[]")
        XCTAssertEqual(PulseStore.reactions(fromJson: "[]"), [])
        XCTAssertEqual(PulseStore.reactions(fromJson: nil), [])
        XCTAssertEqual(PulseStore.reactions(fromJson: "not json"), [])
    }

    func testUpsertOverwritesV3ColumnsOnConflict() throws {
        let store = try PulseStore()
        // First write: pinned, reacted, thread reply.
        try store.upsert(messages: [makeV3Message(id: "t-r1", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:00.000Z")])
        // Second write — an edit/unpin/unreact tombstone-swap arrives as a
        // fresh row for the same id; the upsert must overwrite ALL columns.
        let before = makeV3Message(id: "t-r1", conversationId: "c1", parentId: "t-root", createdAt: "2026-09-07T13:00:00.000Z")
        let edited = WireChatMessage(
            id: before.id, conversationId: before.conversationId, senderId: before.senderId,
            content: "edited body", kind: before.kind, createdAt: before.createdAt,
            editedAt: "2026-09-07T15:00:00.000Z", deletedAt: "2026-09-07T15:30:00.000Z",
            sender: before.sender, reactions: [], replyTo: nil,
            parentId: nil, imagePath: nil, audioPath: nil, durationMs: nil,
            filePath: nil, fileName: nil, fileSize: nil, pinnedAt: nil,
            viewOnce: before.viewOnce, anon: before.anon, anonAlias: before.anonAlias,
            viewedAt: nil, viewedBy: nil, transcript: nil, transcribedAt: nil,
            topicId: nil, linkUrl: nil, linkPreview: nil, poll: nil,
        )
        try store.upsert(messages: [edited])

        let rows = try store.messages(conversationId: "c1")
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.content, "edited body")
        XCTAssertEqual(row.editedAt, "2026-09-07T15:00:00.000Z")
        XCTAssertEqual(row.deletedAt, "2026-09-07T15:30:00.000Z")
        XCTAssertNil(row.parentId)
        XCTAssertNil(row.pinnedAt)
        XCTAssertNil(row.reactions) // reactionsJson reset to '[]'
        XCTAssertNil(row.imagePath)
    }

    // ── v4 (W2-DATA-B) — Wave 2 depth columns + topics + saved library ──

    /// The v3→v4 upgrade, same pattern the v2→v3 test uses (the migrator is
    /// private and GRDB is not linked into the test target): rows written
    /// with every v4 column nil are byte-for-byte what a migrated v3 row
    /// looks like after the ALTERs (NULLs). They must survive a reopen
    /// alongside full-fidelity v4 rows, with every column usable.
    func testV4ColumnsRoundTripAndLegacyRowsSurviveReopen() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pulse-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("pulse.sqlite").path

        do {
            let store = try PulseStore(path: path)
            try store.upsert(conversations: [makeConversation(id: "c1")])
            // v3-era row: only v1–v3 columns populated.
            try store.upsert(messages: [makeV3Message(id: "v3-row", conversationId: "c1", parentId: nil, createdAt: "2026-09-08T11:00:00.000Z")])
            // Full-fidelity v4 rows: a poll card + a transcript-only row.
            try store.upsert(messages: [
                makeV4Message(id: "v4-poll", conversationId: "c1", createdAt: "2026-09-08T12:00:00.000Z"),
                makeMessage(id: "v4-transcript", conversationId: "c1", content: "voice note"),
            ])
            try store.updateTranscription(
                messageId: "v4-transcript",
                transcript: "hello from the mic",
                transcribedAt: "2026-09-08T12:05:00.000Z",
            )
        }

        let reopened = try PulseStore(path: path)
        let rows = try reopened.messages(conversationId: "c1")
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(Set(rows.map(\.id)), ["v3-row", "v4-poll", "v4-transcript"])

        // Legacy v3 row: every v4 column stays NULL after the migration.
        let legacy = try XCTUnwrap(rows.first { $0.id == "v3-row" })
        XCTAssertNil(legacy.viewedAt)
        XCTAssertNil(legacy.viewedBy)
        XCTAssertNil(legacy.transcript)
        XCTAssertNil(legacy.transcribedAt)
        XCTAssertNil(legacy.topicId)
        XCTAssertNil(legacy.linkUrl)
        XCTAssertNil(legacy.linkPreview)
        XCTAssertNil(legacy.poll)

        // Full-fidelity row: every v4 COLUMN round-trips through GRDB.
        // viewedAt/transcript/transcribedAt/pollJson/linkPreviewJson/topicId
        // are real columns; viewedBy + linkUrl are NOT cached (they ride the
        // next authoritative wire row — v4 store contract, PulseStore.messageRow).
        let rich = try XCTUnwrap(rows.first { $0.id == "v4-poll" })
        XCTAssertEqual(rich.viewedAt, "2026-09-08T12:01:00.000Z")
        XCTAssertNil(rich.viewedBy)
        XCTAssertEqual(rich.transcript, "spoken words")
        XCTAssertEqual(rich.transcribedAt, "2026-09-08T12:02:00.000Z")
        XCTAssertEqual(rich.topicId, "topic-1")
        XCTAssertNil(rich.linkUrl)
        XCTAssertEqual(rich.linkPreview?.url, "https://example.com")
        XCTAssertEqual(rich.linkPreview?.title, "Example Domain")
        XCTAssertEqual(rich.linkPreview?.imageUrl, "https://example.com/og.png")
        let poll = try XCTUnwrap(rich.poll)
        XCTAssertEqual(poll.id, "poll-1")
        XCTAssertEqual(poll.question, "Ship it?")
        XCTAssertEqual(poll.closed, false)
        XCTAssertEqual(poll.totalVotes, 1)
        XCTAssertEqual(poll.options?.count, 2)
        XCTAssertEqual(poll.options?.first?.votedBy, ["u1"])
        XCTAssertEqual(poll.pickFor("u1"), "o1")
        XCTAssertNil(poll.pickFor("u2"))

        // updateTranscription patched the cached row pre-reopen; the strip
        // data survives the migration boundary too.
        let voiced = try XCTUnwrap(rows.first { $0.id == "v4-transcript" })
        XCTAssertEqual(voiced.transcript, "hello from the mic")
        XCTAssertEqual(voiced.transcribedAt, "2026-09-08T12:05:00.000Z")
    }

    func testUpsertOverwritesV4ColumnsOnConflict() throws {
        let store = try PulseStore()
        try store.upsert(messages: [makeV4Message(id: "m1", conversationId: "c1", createdAt: "2026-09-08T12:00:00.000Z")])
        // A plain follow-up row for the same id must clear the Wave 2
        // columns (envelope rows are authoritative — spec §0).
        let cleared = makeMessage(id: "m1", conversationId: "c1")
        try store.upsert(messages: [cleared])

        let row = try XCTUnwrap(try store.messages(conversationId: "c1").first)
        XCTAssertNil(row.poll)
        XCTAssertNil(row.linkPreview)
        XCTAssertNil(row.viewedAt)
        XCTAssertNil(row.transcript)
        XCTAssertNil(row.topicId)
    }

    func testTopicsUpsertPrunesAndOrdersByLastMessageAt() throws {
        let store = try PulseStore()
        let design = WireTopic(id: "t1", name: "Design", emoji: "🎨", lastMessageAt: "2026-09-08T12:00:00.000Z", messageCount: 12)
        let qa = WireTopic(id: "t2", name: "QA", emoji: nil, lastMessageAt: "2026-09-08T11:00:00.000Z", messageCount: 3)
        try store.upsert(topics: [design, qa], conversationId: "c1")

        // Newest activity first; nil emoji rehydrates from the column default.
        var topics = try store.topics(conversationId: "c1")
        XCTAssertEqual(topics.map(\.id), ["t1", "t2"])
        XCTAssertEqual(topics[0].emoji, "🎨")
        XCTAssertEqual(topics[0].messageCount, 12)
        XCTAssertEqual(topics[1].emoji, "💬") // column NOT NULL DEFAULT '💬'

        // A re-upsert without t2 prunes it (topic deleted by another member).
        try store.upsert(topics: [design], conversationId: "c1")
        topics = try store.topics(conversationId: "c1")
        XCTAssertEqual(topics.map(\.id), ["t1"])

        // Pruning is scoped to the conversation — c2 rows stay untouched.
        let other = WireTopic(id: "t3", name: "Ops", emoji: "🛠️", lastMessageAt: "2026-09-08T10:00:00.000Z", messageCount: 1)
        try store.upsert(topics: [other], conversationId: "c2")
        XCTAssertEqual(try store.topics(conversationId: "c2").map(\.id), ["t3"])
        XCTAssertEqual(try store.topics(conversationId: "c1").map(\.id), ["t1"])

        // Unknown room reads empty.
        XCTAssertEqual(try store.topics(conversationId: "c-missing").count, 0)
    }

    func testSavedLibraryUpsertReplaceAndDelete() throws {
        let store = try PulseStore()
        let first = WireSavedItem(
            savedAt: "2026-09-08T12:00:00.000Z",
            conversation: WireSavedConversation(id: "c1", isGroup: true, name: "Wave 2 QA"),
            message: makeMessage(id: "m1", conversationId: "c1"),
        )
        let second = WireSavedItem(
            savedAt: "2026-09-08T12:01:00.000Z",
            conversation: WireSavedConversation(id: "c2", isGroup: false, name: "Ada"),
            message: makeMessage(id: "m2", conversationId: "c2"),
        )
        try store.upsert(savedItems: [first, second])

        // Both markers AND both message rows landed (jump-open needs the row).
        XCTAssertEqual(try store.savedIds(), Set(["m1", "m2"]))
        XCTAssertEqual(try store.message(id: "m1")?.content, "cached body")
        XCTAssertEqual(try store.message(id: "m2")?.content, "cached body")

        // Re-upsert with a NEWER savedAt keeps the marker fresh.
        try store.upsert(savedItems: [WireSavedItem(
            savedAt: "2026-09-08T13:00:00.000Z",
            conversation: first.conversation,
            message: first.message,
        )])
        XCTAssertEqual(try store.savedIds(), Set(["m1", "m2"]))

        // Server list refresh → prune what is no longer saved (m2 unsaved
        // elsewhere); the message ROW cache is untouched by the prune.
        try store.replaceSaved(messageIds: ["m1"])
        XCTAssertEqual(try store.savedIds(), Set(["m1"]))
        XCTAssertEqual(try store.message(id: "m2")?.id, "m2")

        // Row action "Unsave".
        try store.deleteSaved(messageId: "m1")
        XCTAssertTrue(try store.savedIds().isEmpty)

        // Idempotent deletes never throw.
        try store.deleteSaved(messageId: "m1")
    }

    func testUpdateTranscriptionPatchesCachedRow() throws {
        let store = try PulseStore()
        try store.upsert(messages: [makeMessage(id: "m1", conversationId: "c1")])
        XCTAssertNil(try store.message(id: "m1")?.transcript)

        try store.updateTranscription(
            messageId: "m1",
            transcript: "hello world",
            transcribedAt: "2026-09-08T12:30:00.000Z",
        )
        let row = try XCTUnwrap(try store.message(id: "m1"))
        XCTAssertEqual(row.transcript, "hello world")
        XCTAssertEqual(row.transcribedAt, "2026-09-08T12:30:00.000Z")

        // Unknown ids are a silent no-op (UPDATE matches nothing).
        try store.updateTranscription(
            messageId: "missing",
            transcript: "nope",
            transcribedAt: "2026-09-08T12:31:00.000Z",
        )
        XCTAssertEqual(try store.message(id: "m1")?.transcript, "hello world")
    }

    func testPollAndLinkPreviewJsonCodecs() throws {
        let poll = WirePoll(
            id: "poll-1",
            question: "Ship it?",
            closed: true,
            options: [WirePollOption(id: "o1", text: "Yes", position: 0, voteCount: 2, votedBy: ["u1", "u2"])],
            totalVotes: 2,
            myOptionId: nil,
        )
        let json = PulseStore.pollJsonData(poll)
        XCTAssertEqual(PulseStore.poll(fromJson: json), poll)

        // nil → NULL column; empty/garbage → nil on the way back.
        XCTAssertNil(PulseStore.pollJsonData(nil))
        XCTAssertNil(PulseStore.poll(fromJson: nil))
        XCTAssertNil(PulseStore.poll(fromJson: ""))
        XCTAssertNil(PulseStore.poll(fromJson: "not json"))

        let preview = WireLinkPreview(
            url: "https://example.com",
            title: "Example",
            description: nil,
            imageUrl: nil,
            siteName: "example.com",
        )
        let previewJson = PulseStore.linkPreviewJsonData(preview)
        XCTAssertEqual(PulseStore.linkPreview(fromJson: previewJson), preview)
        XCTAssertNil(PulseStore.linkPreviewJsonData(nil))
        XCTAssertNil(PulseStore.linkPreview(fromJson: nil))
        XCTAssertNil(PulseStore.linkPreview(fromJson: "garbage"))
    }
}

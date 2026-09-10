import XCTest
@testable import Pulse

/// Wave 0 — GRDB migration + offline read-path tests.
/// The v1 schema (conversation/message) and the v2 additions (outbox/draft)
/// share one non-destructive migrator; a fresh open applies both in order
/// and a reopen must be a no-op that keeps every row readable.
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
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
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
}

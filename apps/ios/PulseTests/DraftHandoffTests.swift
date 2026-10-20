import XCTest
import GRDB
@testable import Pulse

@MainActor
final class DraftHandoffTests: XCTestCase {
    private let viewer = PulseViewer(id: "sender", name: "Sender")

    func testPendingJournalCannotReviveTextAfterQueueCommitAndReopen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("chat.sqlite").path
        let store = try PulseStore(path: path)
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        let pending = journal.submit("send me", revision: 1)
        // No await: the UI's queued writer has not run yet.
        let message = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room",
            content: "send me", effect: "echo", anon: true, draftJournal: journal)
        XCTAssertEqual(journal.snapshot, "")
        try await pending.value
        let reopened = try PulseStore(path: path)
        XCTAssertEqual(reopened.draftSnapshot(conversationId: "room"), "")
        XCTAssertEqual(try reopened.message(id: message.id)?.content, "send me")
        XCTAssertEqual(try reopened.outboxAll().count, 1)
        XCTAssertTrue(reopened.allDrafts().isEmpty)
        let next = PulseDraftJournal(store: reopened, conversationId: "room")
        XCTAssertEqual(next.snapshot, "")
        try await next.save("new edit", revision: 10)
        try await journal.save("old disposal", revision: 100)
        XCTAssertEqual(reopened.draftSnapshot(conversationId: "room"), "new edit")
    }

    func testOldTokenCannotConsumeNewerEditAcrossStoreHandles() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let first = try PulseStore(path: path)
        let second = try PulseStore(path: path)
        try first.saveDraftSnapshot(conversationId: "room", text: "first")
        let token = first.captureDraftConsumption(conversationId: "room")
        try second.saveDraftSnapshot(conversationId: "room", text: "next")
        let message = TempMessages.make(conversationId: "room", viewer: viewer, clientId: "one",
            content: "first", parentId: nil, replyTo: nil, anon: false, anonAlias: nil, topicId: nil)
        XCTAssertFalse(try first.enqueueMessage(message, clientId: "one", envelope: PulseOutboxForward(kind: "text"), consumingDraft: token))
        XCTAssertEqual(first.draftSnapshot(conversationId: "room"), "next")
        XCTAssertEqual(first.countOutbox(), 1)
    }

    func testDraftWriteFailureRollsBackBubbleAndQueueWithoutLosingDraft() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try PulseStore(path: path)
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("keep me", revision: 1)
        let connection = try DatabaseQueue(path: path)
        try connection.write { db in
            try db.execute(sql: "CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk write failed'); END")
        }
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: "keep me", draftJournal: journal))
        XCTAssertEqual(store.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "keep me")
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"), "keep me")
        XCTAssertEqual(try connection.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM message") }, 0)
        try connection.write { try $0.execute(sql: "DROP TRIGGER fail_draft") }
        _ = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: "keep me", draftJournal: journal)
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"), "")
    }

    func testNonComposerSendsPreserveDraftAndForeignStoreTokenIsRejected() async throws {
        let store = try PulseStore()
        let other = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("unfinished", revision: 1)
        _ = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: "thread reply", parentId: "root")
        _ = try PulseTextDelivery.enqueueRich(store: store, viewer: viewer, conversationId: "room", kind: "sticker", payload: ["emoji": "🍁", "pack": "Pulse"])
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"), "unfinished")
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: other, viewer: viewer, conversationId: "room", content: "unfinished", draftJournal: journal))
        XCTAssertEqual(other.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "unfinished")
    }
}

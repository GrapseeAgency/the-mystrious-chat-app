import XCTest
import GRDB
@testable import Pulse

@MainActor
final class DraftHandoffTests: XCTestCase {
    private let viewer = PulseViewer(id: "sender", name: "Sender")

    func testFileOnlyDraftManagerClearIsExactAndSurvivesReopen() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm", ".draft-recovery"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path, composerOwner: "sender")
        let files = PulseDraftCheckpointJournal(path: path)
        _ = try files.write("file-only", text: "first")
        let old = try XCTUnwrap(store.allDraftRows().first)
        let revision = store.captureDraftConsumption(conversationId: "file-only").revision
        _ = try files.write("file-only", text: "newer")
        XCTAssertFalse(try store.deleteDraftIfUnchanged(old, revision: revision))
        let current = try XCTUnwrap(store.allDraftRows().first)
        XCTAssertEqual(current.text, "newer")
        XCTAssertTrue(try store.deleteDraftIfUnchanged(current, revision: revision))
        let reopened = try PulseStore(path: path, composerOwner: "sender")
        XCTAssertEqual(reopened.draftSnapshot(conversationId: "file-only"), "")
        XCTAssertTrue(try reopened.allDraftRows().isEmpty)
        XCTAssertNil(try files.read("file-only"))
    }

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
        do { try await journal.save("old disposal", revision: 100); XCTFail("Displaced draft must report its recovery copy") }
        catch { XCTAssertTrue(error is PulseDraftJournal.Failure) }
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
        XCTAssertEqual(try first.countOutbox(), 1)
    }

    func testDraftWriteFailureRollsBackBubbleAndQueueWithoutLosingDraft() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try PulseStore(path: path)
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("keep me", revision: 1)
        let connection = try DatabaseQueue(path: path)
        try await connection.write { db in
            try db.execute(sql: "CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk write failed'); END")
        }
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: "keep me", draftJournal: journal))
        XCTAssertEqual(try store.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "keep me")
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"), "keep me")
        let messageCount = try await connection.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM message") }
        XCTAssertEqual(messageCount, 0)
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_draft") }
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
        XCTAssertEqual(try other.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "unfinished")
    }
    func testIndependentCheckpointSurvivesFailedSQLiteAndConsumedFileCannotReviveDraft() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm", ".draft-recovery"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path, composerOwner: "sender")
        let connection = try DatabaseQueue(path: path)
        try store.saveDraftSnapshot(conversationId: "room", text: "older disk text")
        try await connection.write { try $0.execute(sql: "CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'SQLite cannot commit'); END") }
        let thread = PulseThreadDraft.key("room", parentId: "root")
        XCTAssertThrowsError(try store.saveDraftSnapshot(conversationId: "room", text: "last keystrokes"))
        XCTAssertThrowsError(try store.saveDraftSnapshot(conversationId: thread, text: "thread keystrokes"))
        // No PulseDraftJournal/front buffer was used above. These fresh objects
        // recover from a real file, not the shared in-memory draft fence.
        let checkpoint = try XCTUnwrap(PulseDraftCheckpointJournal(path: path).read("room"))
        XCTAssertEqual(checkpoint.text, "last keystrokes")
        let reopened = try PulseStore(path: path, composerOwner: "sender")
        XCTAssertEqual(reopened.draftSnapshot(conversationId: "room"), "last keystrokes")
        XCTAssertEqual(reopened.draftSnapshot(conversationId: thread), "thread keystrokes")
        XCTAssertEqual(try reopened.allDraftRows().first { $0.conversationId == "room" }?.text, "last keystrokes")
        let state = PulseComposerReceipt(composerVersion: 1, userId: "sender", kind: "draft", targetId: "room", rootId: nil,
            conversationId: "room", text: "remote", revision: "member:1", editedAt: nil, clientId: nil, outcome: nil)
        XCTAssertFalse(try reopened.importComposerDraft(state, token: reopened.captureDraftConsumption(conversationId: "room"), previous: "older disk text"))
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_draft") }
        let journal = PulseDraftJournal(store: reopened, conversationId: "room")
        _ = try PulseTextDelivery.enqueue(store: reopened, viewer: viewer, conversationId: "room", content: "last keystrokes", draftJournal: journal)
        // Simulate process death before post-commit file removal. The UUID ACK
        // committed with queue+clear, so this surviving file is not a new draft.
        XCTAssertEqual(try PulseDraftCheckpointJournal(path: path).read("room")?.id, checkpoint.id)
        let afterSend = try PulseStore(path: path, composerOwner: "sender")
        XCTAssertEqual(afterSend.draftSnapshot(conversationId: "room"), "")
        XCTAssertEqual(afterSend.draftSnapshot(conversationId: thread), "thread keystrokes")
        XCTAssertEqual(try afterSend.countOutbox(), 1)
    }

    func testScheduleAcknowledgementWaitsForAFailedLocalWriteToBecomeDurable() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path,composerOwner: "sender")
        let connection = try DatabaseQueue(path: path)
        try await connection.write { try $0.execute(sql: "CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk full'); END") }
        let journal = PulseDraftJournal(store: store,conversationId: "room")
        do { try await journal.submit("keep original",revision: 1).value; XCTFail("Injected write must fail") } catch { }
        XCTAssertTrue(PulseDraftJournal.hasPending(store: store,conversationId: "room"))
        do { try await PulseDraftJournal.flushPending(store: store,conversationId: "room"); XCTFail("Must not acknowledge failed durability") } catch { }
        XCTAssertEqual(journal.snapshot, "keep original")
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_draft") }
        try await PulseDraftJournal.flushPending(store: store,conversationId: "room")
        XCTAssertFalse(PulseDraftJournal.hasPending(store: store,conversationId: "room"))
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"),"keep original")
    }

}

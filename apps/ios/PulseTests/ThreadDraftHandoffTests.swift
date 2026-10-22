import XCTest
import GRDB
@testable import Pulse

@MainActor
final class ThreadDraftHandoffTests: XCTestCase {
    private let viewer = PulseViewer(id: "sender", name: "Sender")

    func testThreadDraftsReopenIndependentlyAndQueueConsumesOnlySubmittedThread() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try PulseStore(path: path)
        try store.saveDraft(conversationId: "room", text: "main composer")
        let first = PulseDraftJournal(store: store, conversationId: "room", parentId: "root-a")
        let pending = first.submit("reply once", revision: 1)
        let reopened = PulseDraftJournal(store: store, conversationId: "room", parentId: "root-a")
        XCTAssertEqual(reopened.snapshot, "reply once")
        XCTAssertFalse(first.isCurrentOwner)
        let other = PulseDraftJournal(store: store, conversationId: "room", parentId: "root-b")
        try await other.save("other thread", revision: 1)
        _ = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room",
            content: "reply once", parentId: "root-a", draftJournal: reopened)
        try await pending.value
        let disk = try PulseStore(path: path)
        XCTAssertEqual(disk.draftSnapshot(conversationId: PulseThreadDraft.key("room", parentId: "root-a")), "")
        XCTAssertEqual(disk.draftSnapshot(conversationId: PulseThreadDraft.key("room", parentId: "root-b")), "other thread")
        XCTAssertEqual(disk.draftSnapshot(conversationId: "room"), "main composer")
        XCTAssertEqual(try disk.countOutbox(), 1)
    }

    func testWrongThreadOrMainRoomCannotConsumeThreadToken() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        try await journal.save("keep", revision: 1)
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room",
            content: "keep", parentId: "other", draftJournal: journal))
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room",
            content: "keep", draftJournal: journal))
        XCTAssertEqual(try store.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "keep")
    }

    func testThreadDraftFailureRollsBackQueueAndKeepsOtherDrafts() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try PulseStore(path: path)
        let journal = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        try await journal.save("keep", revision: 1)
        let connection = try DatabaseQueue(path: path)
        try await connection.write { try $0.execute(sql: "CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk full'); END") }
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room",
            content: "keep", parentId: "root", draftJournal: journal))
        XCTAssertEqual(try store.countOutbox(), 0)
        XCTAssertEqual(journal.snapshot, "keep")
    }

    func testOlderThreadTokenPreservesTypingAndOtherAccountStorage() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        try await journal.save("old", revision: 1)
        let token = try journal.prepareSend()
        let pending = journal.submit("new typing", revision: 2)
        let message = TempMessages.make(conversationId: "room", viewer: viewer, clientId: "thread-token",
            content: "old", parentId: "root")
        XCTAssertFalse(try store.enqueueMessage(message, clientId: "thread-token",
            envelope: PulseOutboxForward(kind: "text", parentId: "root"), consumingDraft: token))
        try await pending.value
        XCTAssertEqual(journal.snapshot, "new typing")
        let otherStore = try PulseStore()
        let other = PulseDraftJournal(store: otherStore, conversationId: "room", parentId: "root")
        XCTAssertNil(other.snapshot)
        XCTAssertThrowsError(try otherStore.enqueueMessage(message, clientId: "thread-token",
            envelope: PulseOutboxForward(kind: "text", parentId: "root"), consumingDraft: token))
    }

    func testDeletingDraftInvalidatesPendingFrontBufferOnRapidReopen() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        let pending = journal.submit("deleted", revision: 1)
        try store.deleteDraft(conversationId: PulseThreadDraft.key("room", parentId: "root"))
        let reopened = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        XCTAssertEqual(reopened.snapshot, "")
        try await pending.value
        XCTAssertEqual(reopened.snapshot, "")
    }
}

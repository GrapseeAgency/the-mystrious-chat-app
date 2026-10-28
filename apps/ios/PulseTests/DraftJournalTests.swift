import XCTest
@testable import Pulse

@MainActor
final class DraftJournalTests: XCTestCase {
    func testLateOlderWriteCannotResurrectClearedDraft() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("older", revision: 1)
        try await journal.save("", revision: 3)
        try await journal.save("late stale text", revision: 2)
        XCTAssertNil(store.draft(conversationId: "room"))
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"), "")
        XCTAssertEqual(store.draftSnapshot(conversationId: "room") ?? "stale server text", "")
        XCTAssertTrue(store.allDrafts().isEmpty)
        XCTAssertEqual(store.allDrafts(includingClears: true)["room"], "")
    }

    func testCapturedJournalDoesNotWriteIntoReplacementStore() async throws {
        let first = try PulseStore()
        let replacement = try PulseStore()
        let journal = PulseDraftJournal(store: first, conversationId: "same-room")
        try replacement.saveDraft(conversationId: "same-room", text: "new session")
        try await journal.save("last keystroke before leaving", revision: 1)
        XCTAssertEqual(first.draft(conversationId: "same-room"), "last keystroke before leaving")
        XCTAssertEqual(replacement.draft(conversationId: "same-room"), "new session")
    }

    func testNewestDraftSurvivesOutOfOrderQueuedWrites() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("new composer", revision: 9)
        try await journal.save("", revision: 8)
        XCTAssertEqual(store.draft(conversationId: "room"), "new composer")
    }
    func testNewRoomJournalCannotBeOverwrittenByAnOldRoomTask() async throws {
        let store = try PulseStore()
        let old = PulseDraftJournal(store: store, conversationId: "room")
        let reopened = PulseDraftJournal(store: store, conversationId: "room")
        try await reopened.save("new edit", revision: 20)
        do { try await old.save("late old edit", revision: 30); XCTFail("Displaced draft must report its recovery copy") }
        catch { XCTAssertTrue(error is PulseDraftJournal.Failure) }
        XCTAssertEqual(store.draft(conversationId: "room"), "new edit")
        let recovery = try XCTUnwrap(store.composerRecoveryPage(owner: "local").items.first)
        XCTAssertEqual(recovery.text, "late old edit")
        XCTAssertFalse(recovery.canRetry)
    }

    func testRapidReopenSeesPendingSnapshotBeforeAwaitingSQLite() async throws {
        let store = try PulseStore()
        let old = PulseDraftJournal(store: store, conversationId: "room")
        let pending = old.submit("last keystroke", revision: 1)
        let reopened = PulseDraftJournal(store: store, conversationId: "room")
        XCTAssertEqual(reopened.snapshot, "last keystroke")
        let newer = reopened.submit("new room edit", revision: 2)
        let lateDisposal = old.submit("stale room text", revision: 99)
        try await pending.value
        try await newer.value
        try await lateDisposal.value
        XCTAssertEqual(store.draft(conversationId: "room"), "new room edit")
    }

    func testScheduleReceiptCannotClearAnEditedBackOrReopenedDraft() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("Schedule this", revision: 1)
        let receipt = try XCTUnwrap(journal.captureReceipt())
        XCTAssertTrue(journal.matches(receipt))
        let away = journal.submit("New text", revision: 2)
        let back = journal.submit("Schedule this", revision: 3)
        XCTAssertFalse(journal.matches(receipt), "Reject before asynchronous SQLite writes finish too")
        try await away.value; try await back.value
        let current = try XCTUnwrap(journal.captureReceipt())
        XCTAssertTrue(journal.matches(current))
        let reopened = PulseDraftJournal(store: store, conversationId: "room")
        XCTAssertFalse(journal.matches(current))
        XCTAssertFalse(reopened.matches(current))
        XCTAssertEqual(reopened.snapshot, "Schedule this")
        let other = PulseDraftJournal(store: try PulseStore(), conversationId: "room")
        XCTAssertFalse(other.matches(current), "Same room in another account/store is not the same draft")
    }

    func testScheduleReceiptCannotConsumeAThreadOrExternalDraftWrite() async throws {
        let store = try PulseStore()
        let journal = PulseDraftJournal(store: store, conversationId: "room")
        try await journal.save("Schedule this", revision: 1)
        let receipt = try XCTUnwrap(journal.captureReceipt())
        let thread = PulseDraftJournal(store: store, conversationId: "room", parentId: "root")
        XCTAssertFalse(thread.matches(receipt))
        try store.saveDraft(conversationId: "room", text: "External editor")
        XCTAssertFalse(journal.matches(receipt))
    }
    func testOversizedDraftSurvivesPersistenceReopenAndRejectedSendInRoomAndThread() async throws {
        let text = String(repeating: "a", count: 1999) + "🌿" + String(repeating: " tail", count: 100)
        let store = try PulseStore()
        for parent in [nil, "root"] as [String?] {
            let journal = PulseDraftJournal(store: store, conversationId: "room", parentId: parent)
            try await journal.save(text, revision: 1)
            let reopened = PulseDraftJournal(store: store, conversationId: "room", parentId: parent)
            XCTAssertEqual(reopened.snapshot, text)
            XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: PulseViewer(id: "owner", name: "Owner"),
                conversationId: "room", content: text, parentId: parent, draftJournal: reopened))
            XCTAssertEqual(reopened.snapshot, text)
            XCTAssertEqual(try store.countOutbox(), 0)
        }
    }

}

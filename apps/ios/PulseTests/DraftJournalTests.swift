import XCTest
@testable import Pulse

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
        try await old.save("late old edit", revision: 30)
        XCTAssertEqual(store.draft(conversationId: "room"), "new edit")
    }

}

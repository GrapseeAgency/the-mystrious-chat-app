import XCTest
@testable import Pulse

@MainActor
final class ComposerJournalTests: XCTestCase {
    private func receipt(_ intent: PulseComposerIntent, text: String, revision: String, outcome: String = "applied") -> PulseComposerReceipt {
        PulseComposerReceipt(composerVersion: 1,userId: intent.owner,kind: intent.kind,targetId: intent.targetId,
            rootId: intent.rootId,conversationId: intent.conversationId,text: text,revision: revision,
            editedAt: intent.kind == "edit" ? "2026-10-28T00:00:00.001Z" : nil,clientId: intent.flight?.clientId,outcome: outcome)
    }
    func testFrozenFlightSurvivesReopenAndNewTypingKeepsItsOriginalObservedRevision() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).path
        defer { for suffix in ["","-wal","-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        var store: PulseStore? = try PulseStore(path: path,composerOwner: "me")
        try store!.saveDraftSnapshot(conversationId: "room",text: "first")
        let first = try XCTUnwrap(store!.composerPage(owner: "me").first?.intent)
        let frozen = try XCTUnwrap(store!.freezeComposer(first,revision: "member:0"))
        try store!.saveDraftSnapshot(conversationId: "room",text: "second")
        store = nil
        let reopened = try PulseStore(path: path,composerOwner: "me")
        let newer = try XCTUnwrap(reopened.composerPage(owner: "me").first?.intent)
        XCTAssertEqual(newer.flight,frozen.flight)
        XCTAssertNotEqual(newer.desiredId,frozen.desiredId)
        try reopened.acceptComposer(frozen,receipt: receipt(frozen,text: "first",revision: "member:1"))
        let successor = try XCTUnwrap(reopened.composerPage(owner: "me").first?.intent)
        XCTAssertEqual(successor.text,"second")
        XCTAssertNil(successor.flight)
        XCTAssertEqual(successor.baseRevision,"member:1")
        XCTAssertEqual(reopened.draftSnapshot(conversationId: "room"),"second")
    }
    func testBlockedDraftRemainsRecoverableButDoesNotSpinTransportQueue() throws {
        let store = try PulseStore(owner: "me")
        try store.saveDraftSnapshot(conversationId: "room",text: "mine")
        let desired = try XCTUnwrap(store.composerPage(owner: "me").first?.intent)
        let frozen = try XCTUnwrap(store.freezeComposer(desired,revision: "member:0"))
        try store.acceptComposer(frozen,receipt: receipt(frozen,text: "other device",revision: "member:1",outcome: "conflict"))
        XCTAssertEqual(try store.pendingComposerCount(owner: "me"),0)
        let held = try XCTUnwrap(store.composerPage(owner: "me",held: true).first?.intent)
        XCTAssertEqual(held.text,"mine")
        XCTAssertNil(held.flight)
        XCTAssertNotNil(held.blocked)
        XCTAssertTrue(try store.composerPage(owner: "someone-else",held: true).isEmpty)
    }
    func testUnsubmittedEditIsDurableButNeverDispatchable() throws {
        let store = try PulseStore(owner: "me")
        let message = try JSONDecoder().decode(WireChatMessage.self,from: Data("{\"id\":\"m\",\"conversationId\":\"room\",\"senderId\":\"me\",\"kind\":\"text\",\"content\":\"original\",\"createdAt\":\"2026-10-28T00:00:00Z\"}".utf8))
        try store.saveComposerEdit(message,text: "not submitted",owner: "me")
        XCTAssertEqual(try store.recoveredComposerEdit(owner: "me",id: "m"),"not submitted")
        XCTAssertEqual(try store.pendingComposerCount(owner: "me"),0)
        try store.enqueueComposerEdit(message,text: "not submitted",owner: "me")
        XCTAssertEqual(try store.pendingComposerCount(owner: "me"),1)
        XCTAssertThrowsError(try store.saveComposerEdit(message,text: "wrong owner",owner: "other"))
    }
    func testMainAndThreadDraftsAreIsolatedAndReadImportCannotOverwriteReservedTyping() throws {
        let store = try PulseStore(owner: "me")
        let token = store.captureDraftConsumption(conversationId: "room")
        let state = PulseComposerReceipt(composerVersion: 1,userId: "me",kind: "draft",targetId: "room",rootId: nil,conversationId: "room",text: "remote",revision: "member:1",editedAt: nil,clientId: nil,outcome: nil)
        _ = store.reserveDraftWrite(conversationId: "room")
        XCTAssertFalse(try store.importComposerDraft(state,token: token,previous: nil))
        try store.saveDraftSnapshot(conversationId: "room",text: "main")
        let key = PulseThreadDraft.key("room",parentId: "root🧭")
        XCTAssertEqual(try PulseThreadDraft.scope(key).root,"root🧭")
        try store.saveDraftSnapshot(conversationId: key,text: "thread")
        XCTAssertEqual(try store.composerPage(owner: "me").count,2)
        XCTAssertEqual(store.draftSnapshot(conversationId: "room"),"main")
        XCTAssertEqual(store.draftSnapshot(conversationId: key),"thread")
    }
}

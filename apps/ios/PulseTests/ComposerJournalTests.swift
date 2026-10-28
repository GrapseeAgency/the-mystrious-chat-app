import XCTest
import GRDB
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
    func testUnknownDraftBaselineCannotOverwriteARemoteClear() {
        let intent = PulseComposerIntent(owner: "me",kind: "draft",targetId: "room",conversationId: "room",rootId: nil,
            desiredId: UUID().uuidString,text: "offline text",baseText: "",baseEditedAt: nil,flight: nil,blocked: nil)
        let state = PulseComposerReceipt(composerVersion: 1,userId: "me",kind: "draft",targetId: "room",rootId: nil,
            conversationId: "room",text: "",revision: "member:2",editedAt: nil,clientId: nil,outcome: nil)
        XCTAssertFalse(state.hasSafeDraftBaseline(intent))
        var verified = intent; verified.baseRevision = "member:2"
        XCTAssertTrue(state.hasSafeDraftBaseline(verified))
    }

    func testLatePreflightRefusalCannotHoldNewerDraftOrEdit() throws {
        for kind in ["draft", "edit"] {
            let store = try PulseStore(owner: "me")
            let message = try JSONDecoder().decode(WireChatMessage.self, from: Data("{\"id\":\"m\",\"conversationId\":\"room\",\"senderId\":\"me\",\"kind\":\"text\",\"content\":\"original\",\"createdAt\":\"2026-10-28T00:00:00Z\"}".utf8))
            func stage(_ text: String) throws {
                if kind == "draft" { try store.saveDraftSnapshot(conversationId: "room", text: text) }
                else { try store.enqueueComposerEdit(message, text: text, owner: "me") }
            }
            try stage("older text")
            let original = try XCTUnwrap(store.composerPage(owner: "me").first?.intent)
            XCTAssertNil(original.flight)
            try stage("newer text")
            try store.blockComposer(original, notice: "Old lookup refused")
            let newer = try XCTUnwrap(store.composerPage(owner: "me").first?.intent)
            XCTAssertNotEqual(newer.desiredId, original.desiredId)
            XCTAssertEqual(newer.text, "newer text")
            XCTAssertNil(newer.blocked)
            XCTAssertTrue(try store.composerPage(owner: "me", held: true).isEmpty)
            let frozen = try XCTUnwrap(store.freezeComposer(newer, revision: "member:0"))
            try store.acceptComposer(frozen, receipt: receipt(frozen, text: "newer text", revision: "member:1"))
            XCTAssertEqual(try store.pendingComposerCount(owner: "me"), 0)
        }
    }

    func testAcceptedBaselineStoresOnlyMetadataAndExactTextFingerprint() throws {
        let state = PulseComposerReceipt(composerVersion: 1,userId: "me",kind: "edit",targetId: "message",rootId: nil,
            conversationId: "room",text: "private e\u{301}",revision: "fingerprint",editedAt: nil,clientId: nil,outcome: nil)
        let baseline = state.metadataBaseline
        XCTAssertEqual(baseline.text, "")
        XCTAssertTrue(baseline.matchesBaselineText("private e\u{301}"))
        XCTAssertFalse(baseline.matchesBaselineText("private é"))
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(baseline),as: UTF8.self).contains("private"))
    }

    func testEditCheckpointRecoversAfterVolatileBufferLossAndCannotReviveAfterAcceptance() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm", ".draft-recovery"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path, composerOwner: "me")
        let connection = try DatabaseQueue(path: path)
        let message = try JSONDecoder().decode(WireChatMessage.self, from: Data("{\"id\":\"m\",\"conversationId\":\"room\",\"senderId\":\"me\",\"kind\":\"text\",\"content\":\"original\",\"createdAt\":\"2026-10-28T00:00:00Z\"}".utf8))
        try await connection.write { try $0.execute(sql: "CREATE TRIGGER fail_edit BEFORE INSERT ON wave7Cache BEGIN SELECT RAISE(ABORT, 'SQLite cannot commit'); END") }
        XCTAssertThrowsError(try store.saveComposerEdit(message, text: "my retained edit", owner: "me"))
        let scope = "composer-local-edit:v1|me|m"
        XCTAssertNotNil(try PulseDraftCheckpointJournal(path: path).read(scope))
        // Explicitly discard the volatile front buffer, as process death would.
        PulseDraftWriteFence.shared(for: store.storageIdentity).withEditSnapshot(scope) { $0 = nil }
        let reopened = try PulseStore(path: path, composerOwner: "me")
        XCTAssertEqual(try reopened.recoveredComposerEdit(owner: "me", id: "m"), "my retained edit")
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_edit") }
        var remote = message; remote.content = "new remote content"
        try reopened.enqueueComposerEdit(remote, text: "my retained edit", owner: "me")
        let intent = try XCTUnwrap(reopened.composerPage(owner: "me").first?.intent)
        XCTAssertEqual(intent.baseText, "original")
        let frozen = try XCTUnwrap(reopened.freezeComposer(intent, revision: "fingerprint"))
        try reopened.acceptComposer(frozen, receipt: receipt(frozen, text: "my retained edit", revision: "next-fingerprint"))
        XCTAssertNil(try reopened.recoveredComposerEdit(owner: "me", id: "m"))
        XCTAssertNil(try PulseDraftCheckpointJournal(path: path).read(scope))
    }

    func testFailedEditWriteSurvivesReopenedStoreWithoutRebasingOntoRemoteText() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path,composerOwner: "me")
        let connection = try DatabaseQueue(path: path)
        let message = try JSONDecoder().decode(WireChatMessage.self,from: Data("{\"id\":\"m\",\"conversationId\":\"room\",\"senderId\":\"me\",\"kind\":\"text\",\"content\":\"original\",\"createdAt\":\"2026-10-28T00:00:00Z\"}".utf8))
        try await connection.write { try $0.execute(sql: "CREATE TRIGGER fail_edit BEFORE INSERT ON wave7Cache BEGIN SELECT RAISE(ABORT, 'disk full'); END") }
        XCTAssertThrowsError(try store.saveComposerEdit(message,text: "my edit",owner: "me"))
        let reopened = try PulseStore(path: path,composerOwner: "me")
        XCTAssertEqual(try reopened.recoveredComposerEdit(owner: "me",id: "m"),"my edit")
        XCTAssertThrowsError(try reopened.recoveredComposerEdit(owner: "other",id: "m"))
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_edit") }
        var changed = message; changed.content = "other device"
        try reopened.enqueueComposerEdit(changed,text: "my edit",owner: "me")
        let pending = try XCTUnwrap(reopened.composerPage(owner: "me").first?.intent)
        XCTAssertEqual(pending.baseText,"original")
        XCTAssertEqual(pending.text,"my edit")
    }

}

@MainActor
final class ScheduledCommandJournalTests: XCTestCase {
    func testOriginalScheduleInstructionsPersistAndUncertainRoomHeadCannotBeReplaced() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).path
        defer { for suffix in ["","-wal","-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let command = PulseScheduledCommand(owner: "me",clientId: "immutable-operation",kind: "create",conversationId: "room",text: "original",scheduledAt: "2030-01-01T00:00:00.000Z")
        var store: PulseStore? = try PulseStore(path: path,composerOwner: "me")
        try store!.stageScheduledCommand(command)
        store = nil
        let reopened = try PulseStore(path: path,composerOwner: "me")
        XCTAssertEqual(try reopened.scheduledCommandPage(owner: "me").first?.text,"original")
        var changed = command; changed.text = "changed"
        XCTAssertThrowsError(try reopened.stageScheduledCommand(changed))
        let replacement = PulseScheduledCommand(owner: "me",clientId: "different-operation",kind: "create",conversationId: "room",text: "other",scheduledAt: "2030-01-02T00:00:00.000Z")
        XCTAssertThrowsError(try reopened.stageScheduledCommand(replacement))
        try reopened.deferScheduledCommand(command,notice: "Original time is no longer valid")
        XCTAssertEqual(try reopened.pendingSchedules(owner: "me"),0)
        let recovery = try XCTUnwrap(reopened.composerRecoveryPage(owner: "me").items.first)
        XCTAssertEqual(recovery.text,"original")
        XCTAssertTrue(recovery.canRetry)
        XCTAssertThrowsError(try reopened.composerRecoveryPage(owner: "other"))
    }
    func testCancellationAcknowledgementIsDurableAndNeverRequeued() throws {
        let store = try PulseStore(owner: "me")
        let command = PulseScheduledCommand(owner: "me",clientId: "scheduled-id",kind: "cancel")
        try store.stageScheduledCommand(command)
        try store.finishScheduledCommand(command,result: "cancelled")
        try store.stageScheduledCommand(command)
        XCTAssertEqual(try store.pendingSchedules(owner: "me"),0)
        XCTAssertEqual(try store.scheduledCommandResult(command),"cancelled")
        XCTAssertThrowsError(try store.composerRecoveryPage(owner: "other"))
    }
    func testAcceptedScheduleReceiptRetainsOnlyBoundMetadataAndRejectsChangedInstructions() throws {
      for acknowledgeBeforeCancel in [false, true] {
        let store = try PulseStore(owner: "me")
        let command = PulseScheduledCommand(owner: "me",clientId: "immutable-operation",kind: "create",conversationId: "room",text: "private body",scheduledAt: "2030-01-01T00:00:00.000Z")
        try store.stageScheduledCommand(command)
        let item = WireScheduledItem(id: "accepted",conversationId: "room",content: command.text,scheduledAt: command.scheduledAt,
            sentAt: nil,cancelledAt: nil,cancelledReason: nil,creationClientId: command.clientId)
        try store.finishScheduledCommand(command,result: String(decoding: JSONEncoder().encode(item),as: UTF8.self))
        let raw = try XCTUnwrap(store.loadWave7Cache(key: command.ackKey)?.json)
        XCTAssertFalse(raw.contains("private body"))
        XCTAssertEqual(try store.pendingSchedules(owner: "me"),0)
        let restored = try JSONDecoder().decode(WireScheduledItem.self,from: Data(try XCTUnwrap(store.scheduledCommandResult(command)).utf8))
        XCTAssertEqual(restored.content,"private body")
        var altered = command; altered.text = "different body"
        XCTAssertThrowsError(try store.stageScheduledCommand(altered))
        XCTAssertThrowsError(try store.scheduledCommandResult(altered))
        let recovery = try XCTUnwrap(store.composerRecoveryPage(owner: "me").items.first)
        XCTAssertEqual(recovery.text, "")
        let replacement = PulseScheduledCommand(owner: "me",clientId: "new-operation",kind: "create",conversationId: "room",text: "new",scheduledAt: "2030-01-02T00:00:00.000Z")
        XCTAssertThrowsError(try store.stageScheduledCommand(replacement), "Background acceptance must still fence an unconsumed composer")
        if acknowledgeBeforeCancel { try store.acknowledgeScheduledCommand(item,owner: "me") }
        let cancel = PulseScheduledCommand(owner: "me",clientId: "accepted",kind: "cancel")
        try store.stageScheduledCommand(cancel)
        XCTAssertThrowsError(try store.finishScheduledCommand(cancel,result: "pending"))
        XCTAssertEqual(try store.pendingSchedules(owner: "me"),1)
        try store.finishScheduledCommand(cancel,result: "cancelled")
        XCTAssertThrowsError(try store.stageScheduledCommand(command))
        XCTAssertThrowsError(try store.scheduledCommandResult(command))
        XCTAssertNil(try store.loadWave7Cache(key: command.headKey))
        try store.stageScheduledCommand(replacement)
        try store.acknowledgeScheduledCommand(item,owner: "me")
        XCTAssertEqual(try store.loadWave7Cache(key: replacement.headKey)?.json,replacement.clientId)
      }
    }

}

import XCTest
@testable import Pulse

@MainActor
final class SavedIntentJournalTests: XCTestCase {
    private func message(_ id: String = "m") throws -> WireChatMessage {
        try JSONDecoder().decode(WireChatMessage.self, from: Data("{\"id\":\"\(id)\",\"conversationId\":\"r\",\"senderId\":\"peer\",\"kind\":\"text\",\"content\":\"body\",\"createdAt\":\"2026-10-20T00:00:00Z\"}".utf8))
    }
    private func receipt(_ intent: PulseSavedIntent, saved: Bool, revision: String, outcome: String = "applied") -> WireSavedStateReceipt {
        WireSavedStateReceipt(savedReceiptVersion: 2, userId: intent.owner, messageId: intent.messageId,
            saved: saved, savedAt: saved ? "2026-10-28T00:00:00.000Z" : nil,
            revision: revision, clientId: intent.clientId, outcome: outcome, visible: saved)
    }
    func testJournalAndOptimisticMarkerSurviveReopenAndLaterUnsaveWinsOverOlderRefresh() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let original = try message()
        var store: PulseStore? = try PulseStore(path: path)
        try store!.upsert(messages: [original])
        try store!.enqueueSavedIntent(owner: "owner", message: original, saved: true)
        let first = try store!.pendingSaved(owner: "owner")[0]
        XCTAssertEqual(first.expectedRevision, "0")
        store = nil
        let reopened = try PulseStore(path: path)
        XCTAssertEqual(try reopened.pendingSaved(owner: "owner")[0].clientId, first.clientId)
        let oldRead = reopened.beginSavedSnapshot()
        try reopened.enqueueSavedIntent(owner: "owner", message: original, saved: false)
        let second = try reopened.pendingSaved(owner: "owner")[1]
        XCTAssertEqual(second.predecessorClientId, first.clientId)
        try reopened.upsert(savedItems: [WireSavedItem(savedAt: "2026-10-28T00:00:00.000Z", conversation: WireSavedConversation(id: "r", isGroup: nil, name: nil), message: original)], savedOwner: "owner", savedSnapshot: oldRead)
        XCTAssertTrue(try reopened.cachedSavedPage(ids: ["m"]).isEmpty)
        try reopened.acceptSavedState(receipt(first, saved: true, revision: "1"), intent: first, snapshot: reopened.beginSavedSnapshot())
        XCTAssertTrue(try reopened.cachedSavedPage(ids: ["m"]).isEmpty)
        try reopened.acceptSavedState(receipt(second, saved: false, revision: "2"), intent: second, snapshot: reopened.beginSavedSnapshot())
        XCTAssertTrue(try reopened.pendingSaved(owner: "owner").isEmpty)
        try reopened.enqueueSavedIntent(owner: "owner", message: original, saved: true)
        XCTAssertEqual(try reopened.pendingSaved(owner: "owner")[0].expectedRevision, "2")
        XCTAssertTrue(try reopened.pendingSaved(owner: "other").isEmpty)
    }
    func testRetryFairnessDoesNotRewriteTheCausalHeadAndCancellationKeepsIdentity() async throws {
        let store = try PulseStore(), original = try message()
        try store.upsert(messages: [original])
        try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let first = try store.pendingSaved(owner: "o")[0]
        try store.enqueueSavedIntent(owner: "o", message: original, saved: false)
        let second = try store.pendingSaved(owner: "o")[1]
        try store.deferSavedIntent(first)
        try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let third = try store.pendingSaved(owner: "o").last!
        XCTAssertEqual(third.predecessorClientId, second.clientId)
        let completed = await PulseSavedReplay.flush(store: store, owner: "o", isCurrent: { false }, send: { _ in XCTFail("Retired owner dispatched"); throw CancellationError() })
        XCTAssertFalse(completed); XCTAssertEqual(try store.pendingSaved(owner: "o").count, 3)
    }
    func testOfflineLibraryTraversesThousandsOfTiedRowsWithoutWholeBodyReads() throws {
        let store = try PulseStore()
        for batch in stride(from: 0, to: 2400, by: 100) {
            try store.upsert(savedItems: (batch..<batch+100).map { id in
                WireSavedItem(savedAt: "2026-10-28T00:00:00.000Z", conversation: WireSavedConversation(id: "r", isGroup: nil, name: nil), message: try message(String(format: "%05d", id)))
            })
        }
        var cursor: String?, count = 0
        repeat {
            let page = try store.cachedSavedOfflinePage(owner: "o", cursor: cursor)
            XCTAssertLessThanOrEqual(page.items.count, 100)
            count += page.items.count; cursor = page.nextCursor
        } while cursor != nil
        XCTAssertEqual(count, 2400)
        let first = try store.cachedSavedOfflinePage(owner: "o", cursor: nil)
        XCTAssertThrowsError(try store.cachedSavedOfflinePage(owner: "other", cursor: first.nextCursor))
    }
    func testConflictAdoptsCanonicalStateAndCannotAcknowledgeAnotherIdentity() throws {
        let store = try PulseStore(), original = try message()
        try store.upsert(messages: [original]); try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let intent = try store.pendingSaved(owner: "o")[0]
        var wrong = receipt(intent, saved: false, revision: "3", outcome: "conflict"); wrong.clientId = "different"
        XCTAssertThrowsError(try store.acceptSavedState(wrong, intent: intent, snapshot: store.beginSavedSnapshot()))
        XCTAssertEqual(try store.pendingSaved(owner: "o").count, 1)
        try store.acceptSavedState(receipt(intent, saved: false, revision: "3", outcome: "conflict"), intent: intent, snapshot: store.beginSavedSnapshot())
        XCTAssertTrue(try store.cachedSavedPage(ids: ["m"]).isEmpty)
        XCTAssertTrue(try store.pendingSaved(owner: "o").isEmpty)
        XCTAssertNotNil(try store.loadWave7Cache(key: "saved-sync-notice:v1|o"))
    }
    func testReadStartingAfterPostButBeforeItsCommitCannotDiscardTheCommit() throws {
        let store = try PulseStore(), original = try message()
        try store.upsert(messages: [original]); try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let intent = try store.pendingSaved(owner: "o")[0], post = store.beginSavedSnapshot()
        let read = store.beginSavedSnapshot()
        try store.acceptSavedState(receipt(intent, saved: false, revision: "0"), snapshot: read)
        try store.acceptSavedState(receipt(intent, saved: true, revision: "1"), intent: intent, snapshot: post)
        XCTAssertEqual(try store.cachedSavedPage(ids: ["m"]).count, 1)
        try store.enqueueSavedIntent(owner: "o", message: original, saved: false)
        XCTAssertEqual(try store.pendingSaved(owner: "o")[0].expectedRevision, "1")
    }
    func testLatePostCannotUndoNewerDeviceStateReadWhileItWasPending() throws {
        let store = try PulseStore(), original = try message()
        try store.upsert(messages: [original]); try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let intent = try store.pendingSaved(owner: "o")[0], post = store.beginSavedSnapshot()
        try store.acceptSavedState(receipt(intent, saved: false, revision: "2"), snapshot: store.beginSavedSnapshot())
        // The optimistic marker stays until its operation is acknowledged.
        XCTAssertEqual(try store.cachedSavedPage(ids: ["m"]).count, 1)
        try store.acceptSavedState(receipt(intent, saved: true, revision: "1"), intent: intent, snapshot: post)
        XCTAssertTrue(try store.cachedSavedPage(ids: ["m"]).isEmpty)
        XCTAssertTrue(try store.pendingSaved(owner: "o").isEmpty)
        try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        XCTAssertEqual(try store.pendingSaved(owner: "o")[0].expectedRevision, "2")
    }
    func testLatePostCannotRestoreVisibilityRevokedAtTheSameSavedRevision() throws {
        let store = try PulseStore(), original = try message()
        try store.upsert(messages: [original]); try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        let intent = try store.pendingSaved(owner: "o")[0], post = store.beginSavedSnapshot()
        var hidden = receipt(intent, saved: true, revision: "1"); hidden.visible = false
        try store.acceptSavedState(hidden, snapshot: store.beginSavedSnapshot())
        try store.acceptSavedState(receipt(intent, saved: true, revision: "1"), intent: intent, snapshot: post)
        XCTAssertTrue(try store.cachedSavedPage(ids: ["m"]).isEmpty)
        XCTAssertTrue(try store.pendingSaved(owner: "o").isEmpty)
    }
    func testRevisionComparisonDoesNotUseFloatingPointOrLexicalWidth() {
        XCTAssertGreaterThan(WireSavedStateReceipt.compareRevisions("10", "9"), 0)
        XCTAssertGreaterThan(WireSavedStateReceipt.compareRevisions("9007199254740993", "9007199254740992"), 0)
        for invalid in ["", "01", "-1", "1\n", "1.0", "v1", String(repeating: "1", count: 41)] {
            XCTAssertFalse(WireSavedStateReceipt.validRevision(invalid))
        }
    }

    func testUnreadableJournalRowIsRetainedWithoutBlockingOtherMessagesOrClaimingDrain() async throws {
        let store = try PulseStore(), original = try message()
        let badKey = "saved-intent:v1|o|damaged|bad"
        try store.saveWave7Cache(key: badKey, json: "{broken", updatedAt: 0)
        try store.upsert(messages: [original]); try store.enqueueSavedIntent(owner: "o", message: original, saved: true)
        var sent: [String] = []
        let drained = await PulseSavedReplay.flush(store: store, owner: "o", isCurrent: { true }) { intent in
            sent.append(intent.messageId)
            return self.receipt(intent, saved: true, revision: "1")
        }
        XCTAssertEqual(sent, ["m"]); XCTAssertFalse(drained)
        XCTAssertTrue(try store.hasPendingSaved(owner: "o"))
        XCTAssertEqual(try store.loadWave7Cache(key: badKey)?.json, "{broken")
    }

}

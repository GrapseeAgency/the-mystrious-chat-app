import XCTest
@testable import Pulse

final class ReadVisibilityTests: XCTestCase {
    func testThreadAuthorityIsFreshButCoverAndReturnPreserveOriginalSurfaceIntents() throws {
        let epoch = UUID()
        let primary = PulseReadAuthority(), thread = PulseReadAuthority()
        primary.bind(to: epoch)
        primary.gate.setBoundary(roomId: "room", messageId: "root", now: 0)
        let old = try XCTUnwrap(primary.gate.begin(roomId: "room", now: 1))
        XCTAssertTrue(primary.gate.retainIntent("old.member", for: old))
        primary.gate.finish(old, succeeded: false)

        thread.bind(to: epoch)
        var surfaces = PulseReadSurfaces()
        surfaces.setPrimary(roomId: "room", messageId: "root")
        let owner = surfaces.begin(roomId: "room", rootId: "root", authority: thread)
        thread.gate.setBoundary(roomId: "room", messageId: "root", now: 1)
        let fresh = try XCTUnwrap(thread.gate.begin(roomId: "room", now: 2))
        XCTAssertNil(thread.gate.intent(for: fresh), "A genuinely new thread must not borrow the old room intent")
        XCTAssertTrue(thread.gate.retainIntent("fresh.member", for: fresh))
        thread.gate.finish(fresh, succeeded: false)
        XCTAssertTrue(surfaces.end(owner))
        XCTAssertEqual(primary.gate.intent(for: old), "old.member", "Returning to the room cannot renew its authority")

        thread.bind(to: epoch) // temporary cover; same mounted thread
        thread.gate.restartDwell(now: 3)
        let resumed = try XCTUnwrap(thread.gate.begin(roomId: "room", now: 4))
        XCTAssertEqual(thread.gate.intent(for: resumed), "fresh.member")
        let replacement = PulseReadAuthority()
        replacement.bind(to: epoch)
        replacement.gate.setBoundary(roomId: "room", messageId: "root", now: 4)
        XCTAssertNil(replacement.gate.intent(for: try XCTUnwrap(replacement.gate.begin(roomId: "room", now: 5))))
        thread.bind(to: UUID()) // room/account replacement retires a retained view
        thread.gate.setBoundary(roomId: "room", messageId: "root", now: 5)
        XCTAssertNil(thread.gate.intent(for: try XCTUnwrap(thread.gate.begin(roomId: "room", now: 6))))
    }

    func testRoomVisitSurvivesCoverButSameRoomReentryIsNewAuthority() {
        var visit = PulseRoomReadVisit()
        let old = UUID(), replacement = UUID()
        XCTAssertTrue(visit.activate(entryID: old, roomID: "room"))
        XCTAssertTrue(visit.suspend(entryID: old, roomID: "room"))
        XCTAssertFalse(visit.owns(entryID: old, roomID: "room"))
        XCTAssertFalse(visit.activate(entryID: old, roomID: "room"), "A cover must not renew authority")
        XCTAssertTrue(visit.activate(entryID: replacement, roomID: "room"))
        XCTAssertFalse(visit.suspend(entryID: old, roomID: "room"), "Late disappearance belongs to the old entry")
        XCTAssertTrue(visit.owns(entryID: replacement, roomID: "room"))
        visit.reset()
        XCTAssertTrue(visit.activate(entryID: replacement, roomID: "room"), "Session replacement retires authority")
    }

    @MainActor
    func testOldRoomDisappearanceCannotHideNewSameRoomVisit() {
        let session = PulseSession()
        let old = UUID(), current = UUID()
        session.showRoomReadVisit(conversationId: "room", entryID: old)
        session.showRoomReadVisit(conversationId: "room", entryID: current)
        XCTAssertFalse(session.hideRoomReadVisit(conversationId: "room", entryID: old))
        XCTAssertEqual(session.activeRoomId, "room")
        XCTAssertTrue(session.roomVisible)
        XCTAssertFalse(session.ownsRoomReadVisit(conversationId: "room", entryID: old))
        XCTAssertTrue(session.ownsRoomReadVisit(conversationId: "room", entryID: current))
        XCTAssertTrue(session.hideRoomReadVisit(conversationId: "room", entryID: current))
        XCTAssertNil(session.activeRoomId)
        XCTAssertFalse(session.roomVisible)
        session.showRoomReadVisit(conversationId: "room", entryID: current)
        XCTAssertTrue(session.ownsRoomReadVisit(conversationId: "room", entryID: current))
        session.stop()
        XCTAssertFalse(session.ownsRoomReadVisit(conversationId: "room", entryID: current))
    }

    func testOldMenuDismissalCannotReleaseANewerPresentation() throws {
        var menu = PulseMenuVisibility()
        menu.begin()
        let old = try XCTUnwrap(menu.ticket)
        menu.begin()
        let current = try XCTUnwrap(menu.ticket)
        XCTAssertFalse(menu.end(old))
        XCTAssertEqual(menu.ticket, current)
        XCTAssertTrue(menu.end(current))
        XCTAssertNil(menu.ticket)
        XCTAssertFalse(menu.end(current))
    }

    func testViewportChurnPreservesEachContinuouslyVisibleRowsDwell() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundaries(roomId: "room", messageIds: ["stable", "leaving"], now: 0)
        gate.setBoundaries(roomId: "room", messageIds: ["stable", "new"], now: 0.3)
        let stable = try XCTUnwrap(gate.begin(roomId: "room", now: 0.61))
        XCTAssertEqual(stable.boundary.messageId, "stable")
        XCTAssertTrue(gate.retainIntent("original.member", for: stable))
        gate.finish(stable, succeeded: true)
        XCTAssertEqual(try XCTUnwrap(gate.nextDelay(now: 0.61)), 0.29, accuracy: 0.0001)
        XCTAssertNil(gate.begin(roomId: "room", now: 0.89))
        let next = try XCTUnwrap(gate.begin(roomId: "room", now: 0.91))
        XCTAssertEqual(next.boundary.messageId, "new")
        XCTAssertEqual(gate.intent(for: next), "original.member")
        gate.finish(next, succeeded: true)
        gate.setBoundaries(roomId: "room", messageIds: ["stable", "new", "leaving"], now: 1)
        XCTAssertNil(gate.begin(roomId: "room", now: 1.59))
        XCTAssertEqual(gate.begin(roomId: "room", now: 1.61)?.boundary.messageId, "leaving")
    }

    func testRefusedIdentityDoesNotStarveOtherQualifiedRowsOrCreateATimerLoop() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundaries(roomId: "room", messageIds: ["bad", "good"], now: 0)
        let bad = try XCTUnwrap(gate.begin(roomId: "room", now: 0.61))
        XCTAssertEqual(bad.boundary.messageId, "bad")
        gate.finish(bad, succeeded: false)
        gate.deferAttempt(until: .infinity)
        XCTAssertEqual(gate.nextDelay(now: 0.61), 0)
        let good = try XCTUnwrap(gate.begin(roomId: "room", now: 0.61))
        XCTAssertEqual(good.boundary.messageId, "good")
        gate.finish(good, succeeded: true)
        XCTAssertNil(gate.nextDelay(now: 100))
        XCTAssertNil(gate.begin(roomId: "room", now: 100))
    }

    func testDelayedBackgroundBadgeSnapshotCannotCrossAnotherFetchOrForegroundPublication() {
        var fence = PulseBadgeSnapshotFence()
        let delayed = fence.reserve()
        let newer = fence.reserve()
        XCTAssertFalse(fence.owns(delayed))
        XCTAssertTrue(fence.owns(newer))
        _ = fence.reserve() // the foreground publishes a newer count
        XCTAssertFalse(fence.owns(newer))
    }

    func testShellSheetsBlockBothPrimaryAndThreadLeasesWithoutLosingPipOwnershipRules() {
        var surfaces = PulseReadSurfaces()
        XCTAssertTrue(surfaces.isObscured(shellPresented: true, pipOpen: false))
        XCTAssertTrue(surfaces.isObscured(shellPresented: false, pipOpen: true))
        let thread = surfaces.begin(roomId: "room", rootId: "root")
        XCTAssertTrue(surfaces.isObscured(shellPresented: true, pipOpen: false))
        XCTAssertTrue(surfaces.isObscured(shellPresented: true, pipOpen: true))
        XCTAssertFalse(surfaces.isObscured(shellPresented: false, pipOpen: true))
        surfaces.end(thread)
        XCTAssertFalse(surfaces.isObscured(shellPresented: false, pipOpen: false))
    }

    func testThreadLeaseIgnoresUnderlyingRoomAndRestoresEitherDismissalOrder() {
        var surfaces = PulseReadSurfaces()
        surfaces.setPrimary(roomId: "room", messageId: "river")
        let thread = surfaces.begin(roomId: "room")
        XCTAssertNil(surfaces.boundary)
        surfaces.setOverlay(thread, messageId: "reply")
        surfaces.setPrimary(roomId: "room", messageId: nil) // obscured room refresh
        XCTAssertEqual(surfaces.boundary?.messageId, "reply")
        surfaces.setPrimary(roomId: "room", messageId: "river-restored") // parent dismisses first
        XCTAssertEqual(surfaces.boundary?.messageId, "reply")
        XCTAssertTrue(surfaces.end(thread))
        XCTAssertEqual(surfaces.boundary?.messageId, "river-restored")
        let second = surfaces.begin(roomId: "room")
        surfaces.setPrimary(roomId: "room", messageId: nil)
        surfaces.end(second) // sheet disappears first
        XCTAssertNil(surfaces.boundary)
        surfaces.setPrimary(roomId: "room", messageId: "river-again")
        XCTAssertEqual(surfaces.boundary?.messageId, "river-again")
    }

    func testRetiredThreadCannotClearReplacementOrSurviveRoomReset() {
        var surfaces = PulseReadSurfaces()
        let first = surfaces.begin(roomId: "room")
        let second = surfaces.begin(roomId: "room")
        surfaces.setOverlay(second, messageId: "new-reply")
        surfaces.setOverlay(first, messageId: "old-reply")
        XCTAssertFalse(surfaces.end(first))
        XCTAssertEqual(surfaces.boundary?.messageId, "new-reply")
        surfaces.reset()
        surfaces.setPrimary(roomId: "other", messageId: "other-message")
        surfaces.setOverlay(second, messageId: "late-reply")
        XCTAssertFalse(surfaces.end(second))
        XCTAssertEqual(surfaces.boundary?.roomId, "other")
    }

    func testPrefetchedClippedAndZeroSizeRowsCannotBeRead() {
        let frames = [
            "visible": CGRect(x: 0, y: 50, width: 100, height: 100),
            "prefetched": CGRect(x: 0, y: 550, width: 100, height: 100),
            "sliver": CGRect(x: 0, y: 490, width: 100, height: 100),
            "above": CGRect(x: 0, y: -120, width: 100, height: 100),
            "short": CGRect(x: 0, y: 200, width: 100, height: 20),
            "short-clipped": CGRect(x: 0, y: -1, width: 100, height: 20),
            "tall": CGRect(x: 0, y: -100, width: 100, height: 800),
            "zero": CGRect(x: 0, y: 100, width: 100, height: 0),
        ]
        XCTAssertEqual(PulseReadViewport.visibleIDs(frames, height: 500), Set(["visible", "short", "tall"]))
        XCTAssertTrue(PulseReadViewport.visibleIDs(frames, height: 0).isEmpty)
        XCTAssertFalse(PulseReadViewport.tailVisible(nil, height: 500))
        XCTAssertFalse(PulseReadViewport.tailVisible(CGRect(x: 0, y: 501, width: 100, height: 4), height: 500))
        XCTAssertTrue(PulseReadViewport.tailVisible(CGRect(x: 0, y: 490, width: 100, height: 4), height: 500))
    }

    func testRefreshCannotBypassDwellAndUnchangedBoundaryDoesNotRestartIt() throws {
        var gate = PulseReadReceiptGate()
        XCTAssertTrue(gate.setBoundary(roomId: "room", messageId: "shown", now: 10))
        XCTAssertNil(gate.begin(roomId: "room", now: 10.59))
        XCTAssertFalse(gate.setBoundary(roomId: "room", messageId: "shown", now: 10.4))
        let request = try XCTUnwrap(gate.begin(roomId: "room", now: 10.61))
        XCTAssertNil(gate.begin(roomId: "room", now: 11)) // one current attempt
        XCTAssertTrue(gate.finish(request, succeeded: true))
        XCTAssertNil(gate.begin(roomId: "room", now: 12)) // no duplicate ACK loop
    }

    func testOcclusionAndForegroundRestartRequireFreshVisibleDwell() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "shown", now: 0)
        gate.setBoundary(roomId: "room", messageId: nil, now: 0.5)
        XCTAssertNil(gate.begin(roomId: "room", now: 1))
        gate.setBoundary(roomId: "room", messageId: "shown", now: 2)
        XCTAssertNil(gate.begin(roomId: "room", now: 2.5))
        gate.restartDwell(now: 3) // foreground again
        XCTAssertNil(gate.begin(roomId: "room", now: 3.5))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 3.61))
    }

    func testOldResponseCannotAcknowledgeOrReleaseNewBoundaryEvenOnReturnToSameRoom() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "a", now: 0)
        let old = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        gate.reset() // navigate away, then back (or replace account)
        gate.setBoundary(roomId: "room", messageId: "a", now: 2)
        XCTAssertNil(gate.begin(roomId: "other", now: 3))
        let replacement = try XCTUnwrap(gate.begin(roomId: "room", now: 3))
        XCTAssertFalse(gate.finish(old, succeeded: true))
        XCTAssertNil(gate.begin(roomId: "room", now: 4)) // replacement still owns the slot
        XCTAssertTrue(gate.finish(replacement, succeeded: false))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 5)) // stale success did not acknowledge it
    }

    func testRefreshTriggersCannotSkipRetryBackoff() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "shown", now: 0)
        let request = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        XCTAssertTrue(gate.finish(request, succeeded: false))
        gate.deferAttempt(until: 3)
        XCTAssertNil(gate.begin(roomId: "room", now: 2.99))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 3))
    }

    func testVisibleReadOutageBackoffDoesNotExhaustAndHonorsServerDelay() throws {
        let offline = URLError(.notConnectedToInternet)
        XCTAssertEqual((1...9).map { PulseVisibleReadRetry.delay(after: offline, failures: $0)! },
            [1.5, 3, 6, 12, 24, 48, 60, 60, 60])
        let throttled = PulseAPIClient.Failure(kind: .rateLimited, message: nil, status: 429, retryAfter: 120)
        XCTAssertEqual(PulseVisibleReadRetry.delay(after: throttled, failures: 1), 120)
        for status in [408, 409, 425, 429, 500, 503] {
            XCTAssertNotNil(PulseVisibleReadRetry.delay(after: PulseAPIClient.Failure(kind: .unknown, message: nil, status: status), failures: 8))
        }
        for status in [400, 401, 403, 404, 410, 422] {
            XCTAssertNil(PulseVisibleReadRetry.delay(after: PulseAPIClient.Failure(kind: .unknown, message: nil, status: status), failures: 1))
        }
        XCTAssertNil(PulseVisibleReadRetry.delay(after: CancellationError(), failures: 1))
        XCTAssertNil(PulseVisibleReadRetry.delay(after: URLError(.cancelled), failures: 1))
    }

    func testRefusedBoundaryStaysBlockedUntilFreshVisibility() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "shown", now: 0)
        let request = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        gate.finish(request, succeeded: false)
        gate.deferAttempt(until: .infinity)
        XCTAssertNil(gate.begin(roomId: "room", now: 1000))
        gate.setBoundary(roomId: "room", messageId: "other", now: 1000)
        XCTAssertNil(gate.begin(roomId: "room", now: 1000.1))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 1001))
    }

}

extension ReadVisibilityTests {
    func testReadIntentSurvivesLostAckAndReconnectButRetiredPreparationCannotReplaceIt() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "message", now: 0)
        let first = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        XCTAssertTrue(gate.retainIntent("before-manual-unread", for: first))
        XCTAssertTrue(gate.finish(first, succeeded: false))
        gate.restartDwell(now: 2)
        let retry = try XCTUnwrap(gate.begin(roomId: "room", now: 3))
        XCTAssertEqual(gate.intent(for: retry), "before-manual-unread")
        XCTAssertFalse(gate.retainIntent("late-old-preparation", for: first))
        gate.reset()
        gate.setBoundary(roomId: "room", messageId: "message", now: 4)
        let reopened = try XCTUnwrap(gate.begin(roomId: "room", now: 5))
        XCTAssertNil(gate.intent(for: reopened))
    }
}

extension ReadVisibilityTests {
    func testEveryDwellQualifiedIdentityRetainsTheOriginalSessionFence() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundaries(roomId: "room", messageIds: ["b", "a", "b"], now: 0)
        XCTAssertNil(gate.begin(roomId: "room", now: 0.59))
        let first = try XCTUnwrap(gate.begin(roomId: "room", now: 0.61))
        XCTAssertEqual(first.boundary.messageId, "a")
        XCTAssertTrue(gate.retainIntent("original.member", for: first))
        gate.finish(first, succeeded: true)
        let second = try XCTUnwrap(gate.begin(roomId: "room", now: 0.62))
        XCTAssertEqual(second.boundary.messageId, "b")
        XCTAssertEqual(gate.intent(for: second), "original.member")
        gate.finish(second, succeeded: true)
        XCTAssertNil(gate.begin(roomId: "room", now: 1))
    }

    func testReadJournalSurvivesReopenAndRetainsDistinctVisitsWithoutCrossOwnerReplay() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("reads.sqlite").path
        let original = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: "2026-10-26T00:00:00Z", intent: "first.member")
        let newer = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: original.minimumReadAt, intent: "after-manual.member")
        do {
            let store = try PulseStore(path: path)
            XCTAssertEqual(try store.enqueueRead(original).intent, original.intent)
            XCTAssertEqual(try store.enqueueRead(newer).intent, newer.intent)
            XCTAssertEqual(try store.enqueueRead(original).intent, original.intent) // original retry stays original
            // A pre-upgrade journal row is also drainable after reopening.
            try store.saveWave7Cache(key: original.legacyKey,
                json: String(decoding: JSONEncoder().encode(original), as: UTF8.self), updatedAt: 0)
        }
        let reopened = try PulseStore(path: path)
        XCTAssertTrue(try reopened.pendingReads(owner: "another-account").isEmpty)
        XCTAssertEqual(Set(try reopened.pendingReads(owner: "owner").map(\.intent)), Set([original.intent, newer.intent]))
        try reopened.acknowledgeRead(original)
        XCTAssertNil(try reopened.loadWave7Cache(key: original.legacyKey))
        try reopened.acknowledgeRead(original) // lost/duplicated old ACK must not delete the new observation
        XCTAssertEqual(try reopened.pendingReads(owner: "owner").map(\.intent), [newer.intent])
    }

    @MainActor
    func testBackgroundReplayKeepsOriginalIntentAcrossLostAckAndIdentityChange() async throws {
        let store = try PulseStore(path: ":memory:")
        let read = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: "2026-10-26T00:00:00Z", intent: "original.member")
        _ = try store.enqueueRead(read)
        var sent: [String] = []
        var settled: [String] = []
        let lost = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true }, onSettled: { settled.append($0.room) }) { row in
            sent.append(row.intent)
            throw PulseAPIClient.Failure(kind: .network, message: "ACK lost")
        }
        XCTAssertFalse(lost)
        XCTAssertTrue(settled.isEmpty)
        XCTAssertEqual(try store.pendingReads(owner: "owner").count, 1)
        var owned = true
        let switched = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { owned }, onSettled: { settled.append($0.room) }) { row in
            sent.append(row.intent)
            owned = false
        }
        XCTAssertFalse(switched)
        XCTAssertTrue(settled.isEmpty, "Retired sessions cannot invalidate the new account's scopes")
        XCTAssertEqual(try store.pendingReads(owner: "owner").count, 1)
        let delivered = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true }, onSettled: { settled.append($0.room) }) { row in
            sent.append(row.intent)
        }
        XCTAssertTrue(delivered)
        XCTAssertEqual(settled, ["room"])
        XCTAssertEqual(sent, Array(repeating: "original.member", count: 3))
        XCTAssertTrue(try store.pendingReads(owner: "owner").isEmpty)
    }

    @MainActor
    func testBackgroundReplayRetiresRemovedMembershipButPreservesRetryableFailure() async throws {
        let store = try PulseStore(path: ":memory:")
        let read = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: "2026-10-26T00:00:00Z", intent: "original.member")
        _ = try store.enqueueRead(read)
        var settled: [String] = []
        let retry = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true }, onSettled: { settled.append($0.room) }) { _ in
            throw PulseAPIClient.Failure(kind: .network, message: "Rate limited", status: 429)
        }
        XCTAssertFalse(retry)
        XCTAssertTrue(settled.isEmpty)
        XCTAssertEqual(try store.pendingReads(owner: "owner").count, 1)
        let retired = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true }, onSettled: { settled.append($0.room) }) { _ in
            throw PulseAPIClient.Failure(kind: .forbidden, message: "Membership retired", status: 410)
        }
        XCTAssertTrue(retired)
        XCTAssertEqual(settled, ["room"], "A refused scope needs canonical refresh, not invented read progress")
        XCTAssertTrue(try store.pendingReads(owner: "owner").isEmpty)
    }

    @MainActor
    func testHTTPReadProofIsPersistedOnlyAfterAcceptanceAndDoesNotRewriteCurrentContent() async throws {
        let store = try PulseStore(path: ":memory:")
        var message = OutboxFixtures.makeMessage(id: "message", conversationId: "room", content: "Current edited body")
        message.readByMembershipIds = ["other-member"]
        try store.upsert(messages: [message])
        let read = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: message.createdAt, intent: "original.member")
        _ = try store.enqueueRead(read)
        var confirmed: [String] = []
        let lost = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true },
            onConfirmed: { confirmed.append($0.message) }) { _ in
                throw PulseAPIClient.Failure(kind: .network, message: "Unknown ACK")
            }
        XCTAssertFalse(lost)
        XCTAssertEqual(try store.message(id: "message")?.readByMembershipIds, ["other-member"])
        XCTAssertTrue(confirmed.isEmpty)
        let accepted = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true },
            onConfirmed: { confirmed.append($0.message) }) { _ in }
        XCTAssertTrue(accepted)
        XCTAssertEqual(confirmed, ["message"])
        XCTAssertEqual(Set(try XCTUnwrap(store.message(id: "message")?.readByMembershipIds)), Set(["other-member", "member"]))
        XCTAssertEqual(try store.message(id: "message")?.content, "Current edited body")
        XCTAssertFalse(try store.recordConfirmedRead(read))
        let foreign = PulseQueuedRead(owner: "owner", room: "other-room", message: "message",
            minimumReadAt: message.createdAt, intent: "other.foreign-member")
        XCTAssertFalse(try store.recordConfirmedRead(foreign))
        let refused = PulseQueuedRead(owner: "owner", room: "room", message: "message",
            minimumReadAt: message.createdAt, intent: "retired.retired-member")
        _ = try store.enqueueRead(refused)
        _ = await PulseReadJournalReplay.flush(store: store, owner: "owner", isCurrent: { true }) { _ in
            throw PulseAPIClient.Failure(kind: .forbidden, message: "Retired", status: 410)
        }
        XCTAssertFalse(try XCTUnwrap(store.message(id: "message")?.readByMembershipIds).contains("retired-member"))
    }

    @MainActor
    func testPushScopeInvalidationNeverResurrectsAStoppedEngine() throws {
        let store = try PulseStore(path: ":memory:")
        let engine = PulseOutboxEngine(store: store, monitorNetwork: false) { nil }
        var rooms: [String] = []
        engine.onReadStateInvalidation = { rooms.append($0) }
        engine.invalidateReadProjections("room")
        engine.invalidateReadProjections("")
        engine.invalidateReadProjections(String(repeating: "x", count: 129))
        XCTAssertEqual(rooms, ["room"])
        engine.stop()
        engine.invalidateReadProjections("other")
        XCTAssertEqual(rooms, ["room"])
    }

    @MainActor
    func testHTTPReadInvalidationIsRoomSpecificAndRetiredOnSessionStop() {
        let session = PulseSession()
        XCTAssertEqual(session.readStateRevision("room"), 0)
        session.noteReadStateChanged("room")
        session.noteReadStateChanged("room")
        XCTAssertEqual(session.readStateRevision("room"), 2)
        XCTAssertEqual(session.readStateRevision("other"), 0)
        session.stop()
        XCTAssertEqual(session.readStateRevision("room"), 0)
    }

    func testPipOccludesUnderlyingRoomButNotThreadPresentedAboveIt() {
        var surfaces = PulseReadSurfaces()
        XCTAssertFalse(surfaces.obscuredByPip(isOpen: false))
        XCTAssertTrue(surfaces.obscuredByPip(isOpen: true))
        let thread = surfaces.begin(roomId: "room", rootId: "root")
        XCTAssertFalse(surfaces.obscuredByPip(isOpen: true))
        surfaces.end(thread)
        XCTAssertTrue(surfaces.obscuredByPip(isOpen: true))
    }
}

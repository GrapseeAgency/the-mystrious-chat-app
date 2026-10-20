import XCTest
@testable import Pulse

@MainActor
final class ConfirmedRoomMutationTests: XCTestCase {
    func testNoSuccessOrLocalCancellationBeforeServerAcknowledgement() async {
        let actions = Wave7RoomActions()
        var cancelled = false
        let started = expectation(description: "request started")
        var resume: CheckedContinuation<Void, Never>?
        let task = actions.confirmedMutation(key: "reminder:r", success: "Canceled", operation: {
            started.fulfill()
            await withCheckedContinuation { resume = $0 }
        }, onSuccess: { cancelled = true })!
        await fulfillment(of: [started], timeout: 2)
        XCTAssertFalse(cancelled)
        XCTAssertNil(actions.toast)
        XCTAssertNil(actions.confirmedMutation(key: "reminder:r", success: "Resolved", operation: {}))
        resume?.resume(); await task.value
        XCTAssertTrue(cancelled)
        XCTAssertEqual(actions.toast, "Canceled")
        XCTAssertTrue(actions.pendingMutations.isEmpty)
    }

    func testFailedRequestPreservesLocalAlarmAndAllowsRetry() async {
        let actions = Wave7RoomActions()
        var cancelled = false
        let task = actions.confirmedMutation(key: "reminder:r", success: "Canceled", operation: {
            throw URLError(.notConnectedToInternet)
        }, onSuccess: { cancelled = true })!
        await task.value
        XCTAssertFalse(cancelled)
        XCTAssertTrue(actions.toastIsError)
        XCTAssertNotEqual(actions.toast, "Canceled")
        XCTAssertTrue(actions.pendingMutations.isEmpty)
        let retry = actions.confirmedMutation(key: "reminder:r", success: "Canceled", operation: {}, onSuccess: { cancelled = true })!
        await retry.value
        XCTAssertTrue(cancelled)
    }

    func testCancelledRequestCannotClaimSuccessAfterLateResponse() async {
        let actions = Wave7RoomActions()
        var applied = false
        let started = expectation(description: "started")
        var resume: CheckedContinuation<Void, Never>?
        let task = actions.confirmedMutation(key: "event:e", success: "Deleted", operation: {
            started.fulfill()
            await withCheckedContinuation { resume = $0 }
        }, onSuccess: { applied = true })!
        await fulfillment(of: [started], timeout: 2)
        task.cancel(); resume?.resume(); await task.value
        XCTAssertFalse(applied)
        XCTAssertNil(actions.toast)
        XCTAssertTrue(actions.pendingMutations.isEmpty)
    }

    func testReminderSchedulePreservesSecondsAndAbsoluteTimezone() throws {
        let epoch: Int64 = 1_800_000_059_000
        let components = PulseReminderNotifications.triggerComponents(remindAtEpochMs: epoch)
        let calendar = try XCTUnwrap(components.calendar)
        XCTAssertEqual(components.timeZone?.secondsFromGMT(), 0)
        XCTAssertNotNil(components.second)
        let date = try XCTUnwrap(calendar.date(from: components))
        XCTAssertEqual(Int64(date.timeIntervalSince1970 * 1000), epoch)
    }

    func testAcceptedNotificationIsNotReplayedWhenResolveNeedsRetry() async {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        var calls = 0
        let enqueue = { calls += 1; return true }
        let first = await PulseReminderDelivery.queueOnce(ownerId: "a", reminderId: "r", defaults: defaults, enqueue: enqueue)
        let retry = await PulseReminderDelivery.queueOnce(ownerId: "a", reminderId: "r", defaults: defaults, enqueue: enqueue)
        XCTAssertTrue(first); XCTAssertTrue(retry); XCTAssertEqual(calls, 1)
    }

    func testDeniedNotificationIsNotMarkedDeliveredOrResolved() async {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        var calls = 0
        let first = await PulseReminderDelivery.queueOnce(ownerId: "a", reminderId: "r", defaults: defaults) { calls += 1; return false }
        let retry = await PulseReminderDelivery.queueOnce(ownerId: "a", reminderId: "r", defaults: defaults) { calls += 1; return true }
        XCTAssertFalse(first); XCTAssertTrue(retry); XCTAssertEqual(calls, 2)
    }

    func testNotificationReceiptIsScopedToItsOwner() async {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        var calls = 0
        _ = await PulseReminderDelivery.queueOnce(ownerId: "a", reminderId: "r", defaults: defaults) { calls += 1; return true }
        _ = await PulseReminderDelivery.queueOnce(ownerId: "b", reminderId: "r", defaults: defaults) { calls += 1; return true }
        XCTAssertEqual(calls, 2)
    }
    func testOverlappingDeliveryAttemptsShareOneFlight() async {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let owner = UUID().uuidString
        let started = expectation(description: "enqueue started")
        var continuation: CheckedContinuation<Bool, Never>?
        var posts = 0
        let first = Task { await PulseReminderDelivery.queueOnce(ownerId: owner, reminderId: "r", defaults: defaults) {
            posts += 1; started.fulfill()
            return await withCheckedContinuation { continuation = $0 }
        } }
        await fulfillment(of: [started], timeout: 2)
        let overlapping = await PulseReminderDelivery.queueOnce(ownerId: owner, reminderId: "r", defaults: defaults) { posts += 1; return true }
        XCTAssertFalse(overlapping)
        continuation?.resume(returning: true)
        let accepted = await first.value
        XCTAssertTrue(accepted); XCTAssertEqual(posts, 1)
    }

    func testCancellationDuringEnqueueRetractsLateNotificationAndBlocksStalePoll() async {
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let owner = UUID().uuidString
        let started = expectation(description: "enqueue started")
        var continuation: CheckedContinuation<Bool, Never>?
        var visible = false
        var retractions = 0
        let first = Task { await PulseReminderDelivery.queueOnce(ownerId: owner, reminderId: "r", defaults: defaults,
            retract: { visible = false; retractions += 1 }) {
                started.fulfill()
                let accepted = await withCheckedContinuation { continuation = $0 }
                visible = accepted
                return accepted
            }
        }
        await fulfillment(of: [started], timeout: 2)
        PulseReminderDelivery.finish(ownerId: owner, reminderId: "r", defaults: defaults) { visible = false }
        continuation?.resume(returning: true)
        let accepted = await first.value
        XCTAssertFalse(accepted); XCTAssertFalse(visible); XCTAssertEqual(retractions, 1)
        let stale = await PulseReminderDelivery.queueOnce(ownerId: owner, reminderId: "r", defaults: defaults) { XCTFail("cancelled"); return true }
        XCTAssertFalse(stale)
    }

    func testNotificationAndReceiptIdentityIncludesGateway() {
        let a = PulseAPIClient(baseURL: URL(string: "https://one.test")!, userId: "same")
        let b = PulseAPIClient(baseURL: URL(string: "https://two.test")!, userId: "same")
        let first = PulseReminderDelivery.ownerKey(a), second = PulseReminderDelivery.ownerKey(b)
        XCTAssertNotEqual(first, second)
        XCTAssertNotEqual(PulseReminderNotifications.notificationID(reminderId: "r", ownerId: first),
                          PulseReminderNotifications.notificationID(reminderId: "r", ownerId: second))
    }

}

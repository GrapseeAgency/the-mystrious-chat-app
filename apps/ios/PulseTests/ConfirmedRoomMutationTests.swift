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
}

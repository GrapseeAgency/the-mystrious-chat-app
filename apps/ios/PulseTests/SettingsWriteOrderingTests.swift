import XCTest
@testable import Pulse

@MainActor
final class SettingsWriteOrderingTests: XCTestCase {
    func testOldReceiptCannotClearABAIntentOrRewriteOtherFields() throws {
        let suite = "SettingsWriteOrdering.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let journal = PulseSettingsJournal(defaults: defaults)
        let old = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "first")
        _ = try journal.stage(WirePulsePrefs(readReceipts: true), owner: "first")
        let latest = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "first")
        var applied = WirePulsePrefs()
        try journal.acknowledge(old, sent: old.patch, server: WirePulsePrefs(density: "compact", readReceipts: false)) { applied = $0 }
        XCTAssertTrue(applied.settingsKeys.isEmpty)
        XCTAssertTrue(try journal.hasPending(owner: "first"))
        XCTAssertTrue(try journal.current(old).settingsKeys.isEmpty)
        try journal.acknowledge(latest, sent: latest.patch, server: WirePulsePrefs(density: "compact", readReceipts: false)) { applied = $0 }
        XCTAssertNil(applied.density); XCTAssertEqual(applied.readReceipts, false)
        XCTAssertFalse(try journal.hasPending(owner: "first"))
    }
    func testUnrelatedSuccessAndSnapshotKeepFailedIntentAcrossReopen() throws {
        let suite = "SettingsWriteOrdering.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var journal = PulseSettingsJournal(defaults: defaults)
        _ = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "first")
        let density = try journal.stage(WirePulsePrefs(density: "compact"), owner: "first")
        try journal.acknowledge(density, sent: density.patch, server: WirePulsePrefs(density: "compact", readReceipts: true)) { XCTAssertNil($0.readReceipts) }
        journal = PulseSettingsJournal(defaults: try XCTUnwrap(UserDefaults(suiteName: suite)))
        XCTAssertTrue(try journal.hasPending(owner: "first"))
        XCTAssertFalse(try journal.hasPending(owner: "different-gateway-or-user"))
        let pending = try journal.pendingTicket(owner: "first")
        XCTAssertEqual(pending.patch.readReceipts, false)
        var applied: [WirePulsePrefs] = []
        try journal.applySnapshot(WirePulsePrefs(density: "cozy", readReceipts: true), owner: "first") { applied.append($0) }
        XCTAssertNil(applied[0].readReceipts); XCTAssertEqual(applied[1].readReceipts, false)
        XCTAssertThrowsError(try journal.acknowledge(pending, sent: pending.patch, server: WirePulsePrefs()) { _ in XCTFail("Missing receipt applied") })
        XCTAssertTrue(try journal.hasPending(owner: "first"))
    }
    func testPrefsRetryAndFailureHintRemainScopedToTheOriginalOwner() throws {
        let suite = "SettingsWriteOrdering.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let gateway = PulseEndpoints.configuredBase
        defer { defaults.removePersistentDomain(forName: suite); PulseEndpoints.configuredBase = gateway }
        PulseEndpoints.configuredBase = "https://settings-owner.test"
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "first", name: "First"))
        var tickets: [PulseSettingsTicket] = []
        prefs.patchRemote = { tickets.append($0) }
        prefs.setReadReceipts(false)
        prefs.setDensity(.compact)
        try prefs.acknowledgeSettings(tickets[1], sent: tickets[1].patch, server: WirePulsePrefs(density: "compact", readReceipts: true))
        XCTAssertEqual(prefs.readReceipts, false); XCTAssertNotNil(prefs.lastSyncNote)
        prefs.retryPendingSettings()
        XCTAssertEqual(tickets.last?.patch.readReceipts, false); XCTAssertNil(tickets.last?.patch.density)
        prefs.setViewer(PulseViewer(id: "second", name: "Second"))
        XCTAssertThrowsError(try prefs.currentSettingsPatch(tickets[0]))
        XCTAssertNil(prefs.lastSyncNote)
        PulseEndpoints.configuredBase = "https://other-settings-owner.test"
        prefs.setViewer(PulseViewer(id: "first", name: "First"))
        XCTAssertThrowsError(try prefs.currentSettingsPatch(tickets[0]))
    }
    func testHTTPQueueOrdersRequestsAndCancellationCannotDispatchOldQueuedWork() async {
        let queue = PulseSettingsWriteQueue()
        let started = expectation(description: "first request started")
        var release: CheckedContinuation<Void, Never>?
        var sent: [Int] = []
        let first = queue.enqueue {
            sent.append(1)
            await withCheckedContinuation { release = $0; started.fulfill() }
        }
        let second = queue.enqueue { sent.append(2) }
        await fulfillment(of: [started], timeout: 2)
        XCTAssertEqual(sent, [1])
        release?.resume(); await second.value; await first.value
        XCTAssertEqual(sent, [1, 2])

        let nextStarted = expectation(description: "old session request started")
        let old = queue.enqueue {
            sent.append(3)
            await withCheckedContinuation { release = $0; nextStarted.fulfill() }
        }
        let cancelled = queue.enqueue { sent.append(4) }
        await fulfillment(of: [nextStarted], timeout: 2)
        queue.cancelAll()
        let fresh = queue.enqueue { sent.append(5) }
        release?.resume(); await old.value; await cancelled.value; await fresh.value
        XCTAssertFalse(sent.contains(4)); XCTAssertTrue(sent.contains(5))
    }
}

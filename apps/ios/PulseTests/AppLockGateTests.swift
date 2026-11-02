import XCTest
import LocalAuthentication
@testable import Pulse

/// Section 13 item 13 - the app-lock gate state machine, pinned without the
/// real LAContext evaluation (the system prompt cannot run in tests):
/// arming follows the persisted flag in both directions, user-driven cancels
/// stay silent, .passcodeNotSet is honest AND escapable, and the persisted
/// protection flag reverts when its write cannot be proven (item 5).
@MainActor
final class AppLockGateTests: XCTestCase {
    private var suiteName: String?

    override func setUp() {
        super.setUp()
        suiteName = "app-lock-gate-" + UUID().uuidString
    }
    override func tearDown() {
        if let suiteName { UserDefaults().removePersistentDomain(forName: suiteName) }
        suiteName = nil
        super.tearDown()
    }

    private func freshDefaults() -> UserDefaults {
        let defaults = UserDefaults(suiteName: suiteName!)!
        defaults.removePersistentDomain(forName: suiteName!)
        return defaults
    }

    // (a) rapid background/foreground cycles - arming is idempotent and the
    // gate ALWAYS follows the persisted toggle.

    func testBackgroundForegroundCyclesArmExactlyPerToggleAndNeverLeaveAStaleGate() {
        let lock = PulseAppLock(defaults: freshDefaults())
        lock.appDidEnterBackground(appLockEnabled: true)
        XCTAssertTrue(lock.needsLock)
        lock.appDidEnterBackground(appLockEnabled: true) // cycle again mid-"evaluation"
        XCTAssertTrue(lock.needsLock, "re-arming while gated is a no-op, not a second state")
        lock.appDidEnterBackground(appLockEnabled: false) // the toggle was switched off mid-cycle
        XCTAssertFalse(lock.needsLock, "the persisted OFF flag clears any stale gate")
        XCTAssertNil(lock.statusMessage)
        XCTAssertFalse(lock.unsatisfiable)
        lock.appDidEnterBackground(appLockEnabled: true)
        XCTAssertTrue(lock.needsLock)
    }

    // (b) user cancellation stays silent.

    func testUserDrivenCancelsMapToSilenceNotErrorCopy() {
        XCTAssertNil(PulseAppLock.failureCopy(for: LAError(.userCancel)))
        XCTAssertNil(PulseAppLock.failureCopy(for: LAError(.systemCancel)))
        XCTAssertNil(PulseAppLock.failureCopy(for: LAError(.appCancel)))
        XCTAssertNil(PulseAppLock.failureCopy(for: LAError(.userFallback)))
        XCTAssertFalse(PulseAppLock.isUnsatisfiable(LAError(.userCancel)))
    }

    // (d) passcodeNotSet shows honest copy and is escapable.

    func testPasscodeNotSetIsHonestAndEscapable() {
        let error = LAError(.passcodeNotSet)
        XCTAssertEqual(PulseAppLock.failureCopy(for: error), "Set a passcode to use App lock.")
        XCTAssertTrue(PulseAppLock.isUnsatisfiable(error), "no passcode = the gate can never be satisfied")
        XCTAssertFalse(PulseAppLock.isUnsatisfiable(LAError(.biometryLockout)), "lockout recovers via the passcode")

        let lock = PulseAppLock(defaults: freshDefaults())
        lock.appDidEnterBackground(appLockEnabled: true)
        let disabled = expectation(description: "prefs path flipped")
        lock.onEmergencyDisable = { disabled.fulfill() }
        lock.emergencyDisable()
        XCTAssertFalse(lock.needsLock, "the escape hatch opens the gate")
        XCTAssertNil(lock.statusMessage)
        XCTAssertFalse(lock.unsatisfiable)
        XCTAssertEqual(lock.defaults.bool(forKey: PulsePrefs.appLockEnabledKey), false, "the persisted flag is off")
        wait(for: [disabled], timeout: 1)
        // And a later background cycle cannot re-arm from the (now off) disk.
        lock.appDidEnterBackground(appLockEnabled: false)
        XCTAssertFalse(lock.needsLock)
    }

    // Item 5 - the persisted protection flag reverts when the write cannot
    // be proven (read-back mismatch); the gate must never arm from it.

    func testFailedPersistenceRevertsTheToggleAndSurfacesAnHonestError() throws {
        let defaults = freshDefaults()
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        let confirmed = try XCTUnwrap(UserDefaults(suiteName: suiteName!))
        // The "broken disk": the probe accepts nothing and keeps the old value.
        prefs.appLockPersister = { _, _ in
            confirmed.set(false, forKey: PulsePrefs.appLockEnabledKey)
            return false
        }
        prefs.setAppLockEnabled(true)
        XCTAssertFalse(prefs.appLockEnabled, "the in-memory gate reverted to the last persisted value")
        XCTAssertFalse(defaults.bool(forKey: PulsePrefs.appLockEnabledKey), "the previous (off) value is retained")
        XCTAssertEqual(prefs.appLockWriteError, "App lock could not be saved on this device. The previous setting is kept.")
        // The gate CANNOT arm from an unpersisted flag.
        let gate = PulseAppLock(defaults: defaults)
        gate.appDidEnterBackground(appLockEnabled: prefs.appLockEnabled)
        XCTAssertFalse(gate.needsLock)

        // Recovery: an honest write adopts the value and clears the error.
        prefs.appLockPersister = { enabled, target in
            target.set(enabled, forKey: PulsePrefs.appLockEnabledKey)
            return target.bool(forKey: PulsePrefs.appLockEnabledKey) == enabled
        }
        prefs.setAppLockEnabled(true)
        XCTAssertTrue(prefs.appLockEnabled)
        XCTAssertNil(prefs.appLockWriteError)
        XCTAssertTrue(defaults.bool(forKey: PulsePrefs.appLockEnabledKey))
    }

    func testSuccessfulPersistenceWritesAndReadsBackTheSameValue() throws {
        let defaults = freshDefaults()
        let prefs = PulsePrefs(defaults: defaults)
        let previous = prefs.appLockPersister
        prefs.appLockPersister = previous // production probe (write + read-back)
        prefs.setAppLockEnabled(true)
        XCTAssertTrue(prefs.appLockEnabled)
        XCTAssertTrue(defaults.bool(forKey: PulsePrefs.appLockEnabledKey))
        XCTAssertNil(prefs.appLockWriteError)
        prefs.setAppLockEnabled(false)
        XCTAssertFalse(prefs.appLockEnabled)
        XCTAssertFalse(defaults.bool(forKey: PulsePrefs.appLockEnabledKey))
    }
}

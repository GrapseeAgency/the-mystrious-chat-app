import XCTest
import SwiftUI
@testable import Pulse

/// Section 15 item 10 - the reduce-motion gate that every gated animation
/// site consults. The OS half (UIAccessibility.isReduceMotionEnabled) is a
/// device gate; the PREF half is the testable contract this suite pins:
/// the synced prefs flag alone must trip the shared gate, and clearing it
/// must not leave a stale latched true. The substitution helper's contract
/// (same distance, no spring energy) is what every gated site relies on -
/// the spring sites themselves are visual and stay covered by the audit
/// table in the receipt rather than pretend-animation tests.
final class PulseMotionGateTests: XCTestCase {
    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: PulsePrefs.reducedMotionKey)
        super.tearDown()
    }

    func testSyncedPrefAloneTripsTheSharedGate() {
        UserDefaults.standard.set(true, forKey: PulsePrefs.reducedMotionKey)
        XCTAssertTrue(PulseMotion.reduceMotion, "the synced prefs flag alone must gate motion")
    }

    func testClearingThePrefDoesNotLatchStaleTrue() {
        UserDefaults.standard.set(true, forKey: PulsePrefs.reducedMotionKey)
        _ = PulseMotion.reduceMotion
        UserDefaults.standard.set(false, forKey: PulsePrefs.reducedMotionKey)
        // On the simulator UIAccessibility.isReduceMotionEnabled is false, so
        // the gate must follow the cleared pref back down.
        XCTAssertFalse(PulseMotion.reduceMotion)
        UserDefaults.standard.removeObject(forKey: PulsePrefs.reducedMotionKey)
        XCTAssertFalse(PulseMotion.reduceMotion)
    }

    func testTheSubstitutionHelperNeverCrashesAndAlwaysAnswers() {
        // The helper exists per preset and answers for both gate states; the
        // actual "no spring energy" behavior is exercised in previews/UI, this
        // pins that the contract call sites depend on stays callable.
        _ = Animation.pulse(.pulseSnappy, reduceMotion: true)
        _ = Animation.pulse(.pulseSnappy, reduceMotion: false)
        _ = Animation.pulse(.pulseSoft, reduceMotion: true)
        _ = Animation.pulse(.pulseBouncy, reduceMotion: true)
        _ = Animation.pulse(.pulseGentle, reduceMotion: true)
    }
}

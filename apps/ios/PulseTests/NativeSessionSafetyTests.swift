import XCTest
@testable import Pulse

@MainActor
final class NativeSessionSafetyTests: XCTestCase {
    func testColdLaunchHonorsPersistedAppLock() {
        let name = "lock-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(true, forKey: PulsePrefs.appLockEnabledKey)
        XCTAssertTrue(PulseAppLock(defaults: defaults).needsLock)
        defaults.set(false, forKey: PulsePrefs.appLockEnabledKey)
        XCTAssertFalse(PulseAppLock(defaults: defaults).needsLock)
    }

    func testCacheNamespaceSeparatesAccountAndServer() {
        let a = PulseStore.accountFilename(gateway: "https://one.example", viewerId: "a")
        XCTAssertEqual(a, PulseStore.accountFilename(gateway: "https://one.example/", viewerId: "a"))
        XCTAssertNotEqual(a, PulseStore.accountFilename(gateway: "https://one.example", viewerId: "b"))
        XCTAssertNotEqual(a, PulseStore.accountFilename(gateway: "https://two.example", viewerId: "a"))
        XCTAssertNotEqual(a, "pulse.sqlite")
        XCTAssertFalse(a.contains("https"))
        XCTAssertFalse(a.contains("/"))
    }
}

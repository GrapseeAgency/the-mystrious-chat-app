import XCTest
@testable import Pulse

/// R4-A item 3 — navigation-style registry tests (web ground truth:
/// src/lib/nav-registry.ts — key `pulse.navStyle.v2`, DEFAULT_NAV_STYLE,
/// meta :50-64 label/hint strings byte-verbatim, and the excluded five
/// desktop/keyboard/exotic ids that must still parse → capsule fallback).
final class PulseNavStyleTests: XCTestCase {

    // ── registry surface ─────────────────────────────────────

    func testAllCasesCarryTheWebRawValues() {
        // The 8 shipped ids, byte-same with the web store.
        let expected: [PulseNavStyle: String] = [
            .capsule: "capsule",
            .floatingTop: "floating-top",
            .pill: "pill",
            .bottomBar: "bottom-bar",
            .tabBar: "tab-bar",
            .floatingTabBar: "floating-tab-bar",
            .rail: "rail",
            .island: "island",
        ]
        XCTAssertEqual(PulseNavStyle.allCases.count, expected.count)
        XCTAssertTrue(expected.allSatisfy { style, raw in
            style.rawValue == raw
        })
    }

    func testMetaStringsAreByteIdenticalWithNavRegistry() {
        // nav-registry.ts:51-63 verbatim.
        XCTAssertEqual(PulseNavStyle.capsule.label, "Floating Capsule")
        XCTAssertEqual(PulseNavStyle.capsule.hint, "Detached glass capsule dock — the default")
        XCTAssertEqual(PulseNavStyle.floatingTop.label, "Floating Top Nav")
        XCTAssertEqual(PulseNavStyle.floatingTop.hint, "Capsule bar floating beneath the top edge")
        XCTAssertEqual(PulseNavStyle.pill.label, "Pill Navigation")
        XCTAssertEqual(PulseNavStyle.pill.hint, "Single segmented pill with sliding fill")
        XCTAssertEqual(PulseNavStyle.bottomBar.label, "Bottom Bar")
        XCTAssertEqual(PulseNavStyle.bottomBar.hint, "Classic edge-to-edge bottom bar")
        XCTAssertEqual(PulseNavStyle.tabBar.label, "Tab Bar")
        XCTAssertEqual(PulseNavStyle.tabBar.hint, "iOS-style tab bar with tinted squircles")
        XCTAssertEqual(PulseNavStyle.floatingTabBar.label, "Floating Tab Bar")
        XCTAssertEqual(PulseNavStyle.floatingTabBar.hint, "Detached card, elevated active tab")
        XCTAssertEqual(PulseNavStyle.rail.label, "Navigation Rail")
        XCTAssertEqual(PulseNavStyle.rail.hint, "Persistent vertical side rail")
        XCTAssertEqual(PulseNavStyle.island.label, "Island Navigation")
        XCTAssertEqual(PulseNavStyle.island.hint, "Dynamic-island pill that expands on tap")
    }

    func testZonesDriveTheShellChannels() {
        // RootView routes bottom styles through the bottom reserve,
        // floating-top through the top inset and rail through the leading
        // inset — the zone decides, so it must match the web meta.
        XCTAssertEqual(PulseNavStyle.capsule.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.pill.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.bottomBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.tabBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.floatingTabBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.island.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.floatingTop.zone, .top)
        XCTAssertEqual(PulseNavStyle.rail.zone, .side)
    }

    // ── tolerant decode (web getNavStyleMeta fallback parity) ──

    func testExcludedWebIdsParseToCapsuleFallback() {
        // The 5 desktop/keyboard/exotic idioms stay PARSEABLE so a stored
        // future style survives an app update — they decode to capsule.
        let excluded = ["floating-dock", "command-bar", "radial", "gesture", "contextual-dock"]
        for raw in excluded {
            XCTAssertEqual(PulseNavStyle.parse(raw), .capsule, "raw \(raw) must fall back to capsule")
        }
    }

    func testNilAndJunkParseToCapsule() {
        XCTAssertEqual(PulseNavStyle.parse(nil), .capsule)
        XCTAssertEqual(PulseNavStyle.parse(""), .capsule)
        XCTAssertEqual(PulseNavStyle.parse("not-a-style"), .capsule)
        XCTAssertEqual(PulseNavStyle.parse("CAPSULE"), .capsule) // case-sensitive ids
        XCTAssertEqual(PulseNavStyle.defaultValue, .capsule)
    }

    func testEveryShippedIdRoundTripsThroughParse() {
        for style in PulseNavStyle.allCases {
            XCTAssertEqual(PulseNavStyle.parse(style.rawValue), style)
        }
    }

    // ── persistence (PulsePrefs · pulse.navStyle.v2) ─────────

    @MainActor
    func testPrefsRoundTripPersistsTheWebRawValue() {
        let suiteName = "pulse-nav-style-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let prefs = PulsePrefs(defaults: defaults)
        XCTAssertEqual(prefs.navStyle, .capsule) // web DEFAULT_NAV_STYLE

        prefs.setNavStyle(.rail)
        XCTAssertEqual(prefs.navStyle, .rail)
        // Byte-same raw value under the web's exact key.
        XCTAssertEqual(defaults.string(forKey: PulsePrefs.navStyleKey), "rail")
        XCTAssertEqual(PulsePrefs.navStyleKey, "pulse.navStyle.v2")

        // A fresh instance rehydrates the selection.
        let reloaded = PulsePrefs(defaults: defaults)
        XCTAssertEqual(reloaded.navStyle, .rail)
    }

    @MainActor
    func testPrefsToleratesJunkAndExcludedStoredValues() {
        let suiteName = "pulse-nav-style-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set("not-a-style", forKey: PulsePrefs.navStyleKey)
        let junk = PulsePrefs(defaults: defaults)
        XCTAssertEqual(junk.navStyle, .capsule)

        // A future web-widened id stored today must not break the shell.
        defaults.set("radial", forKey: PulsePrefs.navStyleKey)
        let excluded = PulsePrefs(defaults: defaults)
        XCTAssertEqual(excluded.navStyle, .capsule)
    }

    @MainActor
    func testSetNavStyleCyclesThroughEveryValue() {
        let suiteName = "pulse-nav-style-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let prefs = PulsePrefs(defaults: defaults)
        for style in PulseNavStyle.allCases {
            prefs.setNavStyle(style)
            XCTAssertEqual(defaults.string(forKey: PulsePrefs.navStyleKey) ?? "", style.rawValue)
            XCTAssertEqual(prefs.navStyle, style)
        }
    }
}

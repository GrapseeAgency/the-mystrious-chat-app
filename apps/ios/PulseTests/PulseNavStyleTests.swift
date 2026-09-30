import XCTest
@testable import Pulse

/// R4-A item 3 - navigation-style registry tests (web ground truth:
/// src/lib/nav-registry.ts - key `pulse.navStyle.v2`, DEFAULT_NAV_STYLE,
/// meta :50-64 label/hint strings byte-verbatim). R14 5-b - the registry
/// is 13/13: the five previously-excluded desktop/keyboard/exotic ids now
/// ship as honest mobile adaptations and round-trip through parse.
final class PulseNavStyleTests: XCTestCase {

    // registry surface

    func testAllCasesCarryTheWebRawValues() {
        // The 13 shipped ids, byte-same with the web store.
        let expected: [PulseNavStyle: String] = [
            .capsule: "capsule",
            .floatingTop: "floating-top",
            .floatingDock: "floating-dock",
            .pill: "pill",
            .bottomBar: "bottom-bar",
            .tabBar: "tab-bar",
            .floatingTabBar: "floating-tab-bar",
            .commandBar: "command-bar",
            .rail: "rail",
            .island: "island",
            .radial: "radial",
            .gesture: "gesture",
            .contextualDock: "contextual-dock",
        ]
        XCTAssertEqual(PulseNavStyle.allCases.count, expected.count)
        XCTAssertTrue(expected.allSatisfy { style, raw in
            style.rawValue == raw
        })
    }

    func testMetaStringsAreByteIdenticalWithNavRegistry() {
        // nav-registry.ts:51-63 verbatim.
        XCTAssertEqual(PulseNavStyle.capsule.label, "Floating Capsule")
        XCTAssertEqual(PulseNavStyle.capsule.hint, "Detached glass capsule dock - the default")
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
        // R14 5-b - the five mobile adaptations carry the web strings too.
        XCTAssertEqual(PulseNavStyle.floatingDock.label, "Floating Dock")
        XCTAssertEqual(PulseNavStyle.floatingDock.hint, "Desktop-style dock with magnifying icons")
        XCTAssertEqual(PulseNavStyle.commandBar.label, "Command Bar")
        XCTAssertEqual(PulseNavStyle.commandBar.hint, "Compact text command strip with search")
        XCTAssertEqual(PulseNavStyle.radial.label, "Radial Navigation")
        XCTAssertEqual(PulseNavStyle.radial.hint, "FAB fanning destinations in an arc")
        XCTAssertEqual(PulseNavStyle.gesture.label, "Gesture Navigation")
        XCTAssertEqual(PulseNavStyle.gesture.hint, "Edge swipes + gesture pill quick switcher")
        XCTAssertEqual(PulseNavStyle.contextualDock.label, "Contextual Dock")
        XCTAssertEqual(PulseNavStyle.contextualDock.hint, "Dock that adapts to the active tab")
    }

    func testZonesDriveTheShellChannels() {
        // RootView routes bottom styles through the bottom reserve,
        // floating-top through the top inset and rail through the leading
        // inset - the zone decides, so it must match the web meta.
        XCTAssertEqual(PulseNavStyle.capsule.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.pill.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.bottomBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.tabBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.floatingTabBar.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.island.zone, .bottom)
        XCTAssertEqual(PulseNavStyle.floatingTop.zone, .top)
        XCTAssertEqual(PulseNavStyle.commandBar.zone, .top)
        XCTAssertEqual(PulseNavStyle.rail.zone, .side)
        XCTAssertEqual(PulseNavStyle.radial.zone, .overlay)
    }

    // tolerant decode (web getNavStyleMeta fallback parity)

    func testExcludedWebIdsNowRoundTripToThemselves() {
        // R14 5-b - the five previously-excluded ids are shipped styles:
        // they parse to THEIR OWN case (a stored value survives upgrades).
        let shipped = ["floating-dock", "command-bar", "radial", "gesture", "contextual-dock"]
        for raw in shipped {
            let style = PulseNavStyle.parse(raw)
            XCTAssertEqual(style.rawValue, raw, "raw \(raw) must round-trip")
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

    // persistence (PulsePrefs · pulse.navStyle.v2)

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
        let widened = PulsePrefs(defaults: defaults)
        XCTAssertEqual(widened.navStyle, .radial)
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

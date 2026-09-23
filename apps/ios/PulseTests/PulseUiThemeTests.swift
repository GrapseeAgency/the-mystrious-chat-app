import XCTest
import SwiftUI
@testable import Pulse

/// R2-D ITEM 3 — the five design-language themes. Web ground truth:
/// src/lib/ui-theme.ts:35-76 (meta) + src/app/globals.css:124-240 (tokens).
/// The table values are asserted BYTE-SAME so the two platforms can never
/// drift; the selection decodes tolerantly (junk → glass) and persists
/// under the web's exact `pulse.uiTheme.v2` key.
final class PulseUiThemeTests: XCTestCase {

    // ── meta table (ui-theme.ts:35-76 verbatim) ─────────────

    func testMetaTableIsByteSameAsWeb() {
        let expectations: [(PulseUiThemeId, String, String, String, [String], String)] = [
            (.glass, "Immersive Glass", "Glassmorphic Chat UI", "Layered frosted glass, aurora backdrop, specular edges, elastic motion.", ["#10b981", "#0ea5e9"], "elastic"),
            (.kinetic, "Kinetic", "Kinetic UI", "High-contrast ink, sharp corners, bold type, whip-crack springs.", ["#18181b", "#f43f5e"], "crisp"),
            (.minimal, "Quiet Minimal", "Motion-Driven Minimalist UI", "Hairlines and whitespace. Motion whispers, structure speaks.", ["#52525b", "#a1a1aa"], "quiet"),
            (.dynamic, "Dynamic", "Dynamic Minimalism", "Soft neutrals with vivid gradient accents and playful bounce.", ["#f59e0b", "#ec4899"], "playful"),
            (.aero, "Aero Kinetic", "Kinetic Minimalist Interface", "Frost-stroke panels on cool graphite, gliding inertia.", ["#38bdf8", "#818cf8"], "glide"),
        ]
        XCTAssertTrue(PulseUiThemeId.allCases.allSatisfy { id in
            PulseUiThemeId.allCases.contains(id)
        })
        for (id, label, tagline, detail, swatch, motion) in expectations {
            let meta = PulseUiTheme.meta(for: id)
            XCTAssertEqual(meta.id, id)
            XCTAssertEqual(meta.label, label, id.rawValue)
            XCTAssertEqual(meta.tagline, tagline, id.rawValue)
            XCTAssertEqual(meta.detail, detail, id.rawValue)
            XCTAssertEqual(meta.swatch, swatch, id.rawValue)
            XCTAssertEqual(meta.motion, motion, id.rawValue)
        }
    }

    func testAllMetaCoversEveryThemeExactlyOnce() {
        let metas = PulseUiTheme.allMeta()
        XCTAssertEqual(metas.count, 5)
        XCTAssertEqual(Set(metas.map(\.id)), Set(PulseUiThemeId.allCases))
    }

    // ── tolerant decode (web isUiThemeId parity) ────────────

    func testParseAcceptsTheFiveLockedValues() {
        XCTAssertTrue(PulseUiThemeId.allCases.allSatisfy { id in
            PulseUiTheme.parse(id.rawValue) == id
        })
    }

    func testParseFallsBackToGlassOnJunk() {
        XCTAssertEqual(PulseUiTheme.parse(nil), .glass)
        XCTAssertEqual(PulseUiTheme.parse(""), .glass)
        XCTAssertEqual(PulseUiTheme.parse("aurora"), .glass)
        XCTAssertEqual(PulseUiTheme.parse("GLASS"), .glass)
        XCTAssertEqual(PulseUiTheme.parse("immersive"), .glass)
    }

    func testDefaultIsGlass() {
        XCTAssertEqual(PulseUiTheme.defaultId, .glass)
        XCTAssertEqual(PulseTheme.activeUiTheme, .glass)
    }

    // ── tokens (globals.css [data-ui] blocks) ───────────────

    func testRadiusPanelMatchesTheCssBlocks() {
        // Base 28 (glass), kinetic 14, minimal 22, dynamic 26, aero 24.
        XCTAssertEqual(PulseUiTheme.tokens(for: .glass, dark: false).radiusPanel, 28)
        XCTAssertEqual(PulseUiTheme.tokens(for: .kinetic, dark: false).radiusPanel, 14)
        XCTAssertEqual(PulseUiTheme.tokens(for: .minimal, dark: false).radiusPanel, 22)
        XCTAssertEqual(PulseUiTheme.tokens(for: .dynamic, dark: false).radiusPanel, 26)
        XCTAssertEqual(PulseUiTheme.tokens(for: .aero, dark: false).radiusPanel, 24)
        // Radius does not flip with the scheme.
        XCTAssertEqual(PulseUiTheme.tokens(for: .glass, dark: true).radiusPanel, 28)
        XCTAssertEqual(PulseUiTheme.tokens(for: .aero, dark: true).radiusPanel, 24)
    }

    func testBlurMatchesTheCssBlocks() {
        XCTAssertEqual(PulseUiTheme.tokens(for: .glass, dark: false).blur, 28)
        XCTAssertEqual(PulseUiTheme.tokens(for: .kinetic, dark: false).blur, 0)
        XCTAssertEqual(PulseUiTheme.tokens(for: .minimal, dark: false).blur, 14)
        XCTAssertEqual(PulseUiTheme.tokens(for: .dynamic, dark: false).blur, 22)
        XCTAssertEqual(PulseUiTheme.tokens(for: .aero, dark: false).blur, 34)
    }

    func testAccentsMatchTheSwatchPairs() {
        // Glass emerald + sky; the light values are byte-equal to the web
        // swatch pairs. Kinetic is the only dark flip (ink → paper).
        let glass = PulseUiTheme.tokens(for: .glass, dark: false)
        XCTAssertEqual(glass.accent, PulseUiThemeColor(hex: "#10b981"))
        XCTAssertEqual(glass.accent2, PulseUiThemeColor(hex: "#0ea5e9"))
        let kineticLight = PulseUiTheme.tokens(for: .kinetic, dark: false)
        XCTAssertEqual(kineticLight.accent, PulseUiThemeColor(hex: "#18181b"))
        XCTAssertEqual(kineticLight.accent2, PulseUiThemeColor(hex: "#f43f5e"))
        let kineticDark = PulseUiTheme.tokens(for: .kinetic, dark: true)
        XCTAssertEqual(kineticDark.accent, PulseUiThemeColor(hex: "#fafafa"))
        XCTAssertEqual(kineticDark.accent2, PulseUiThemeColor(hex: "#f43f5e"))
        let dynamic = PulseUiTheme.tokens(for: .dynamic, dark: false)
        XCTAssertEqual(dynamic.accent, PulseUiThemeColor(hex: "#f59e0b"))
        XCTAssertEqual(dynamic.accent2, PulseUiThemeColor(hex: "#ec4899"))
        let aero = PulseUiTheme.tokens(for: .aero, dark: false)
        XCTAssertEqual(aero.accent, PulseUiThemeColor(hex: "#38bdf8"))
        XCTAssertEqual(aero.accent2, PulseUiThemeColor(hex: "#818cf8"))
    }

    func testEveryThemeResolvesPositiveTokensInBothSchemes() {
        let bothSchemes = PulseUiThemeId.allCases.allSatisfy { id in
            let light = PulseUiTheme.tokens(for: id, dark: false)
            let dark = PulseUiTheme.tokens(for: id, dark: true)
            return light.radiusPanel > 0 && dark.radiusPanel > 0
                && light.pageBg.alpha > 0 && dark.pageBg.alpha > 0
                && light.panelBg.alpha > 0 && dark.panelBg.alpha > 0
                && light.panelBorder.alpha > 0 && dark.panelBorder.alpha > 0
                && light.blur >= 0 && dark.blur >= 0
                && light.panelAlpha == light.panelBg.alpha
        }
        XCTAssertTrue(bothSchemes)
    }

    // ── hex decode (pure) ───────────────────────────────────

    func testHexDecodePureValues() {
        let emerald = PulseUiThemeColor(hex: "#10b981")
        XCTAssertEqual(emerald.red, 16.0 / 255.0, accuracy: 0.0001)
        XCTAssertEqual(emerald.green, 185.0 / 255.0, accuracy: 0.0001)
        XCTAssertEqual(emerald.blue, 129.0 / 255.0, accuracy: 0.0001)
        XCTAssertEqual(emerald.alpha, 1.0)
        // Alpha rides separately (globals rgba() values).
        let glassPanel = PulseUiThemeColor(hex: "#ffffff", alpha: 0.55)
        XCTAssertEqual(glassPanel.alpha, 0.55, accuracy: 0.0001)
        XCTAssertEqual(glassPanel.red, 1.0, accuracy: 0.0001)
        // Junk degrades to opaque black — never traps.
        let junk = PulseUiThemeColor(hex: "zz")
        XCTAssertEqual(junk.red, 0.0, accuracy: 0.0001)
        XCTAssertEqual(junk.green, 0.0, accuracy: 0.0001)
        XCTAssertEqual(junk.blue, 0.0, accuracy: 0.0001)
    }

    // ── persistence (PulsePrefs · pulse.uiTheme.v2) ─────────

    @MainActor
    func testPrefsRoundTripPersistsTheWebRawValue() {
        let suiteName = "pulse-ui-theme-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let prefs = PulsePrefs(defaults: defaults)
        XCTAssertEqual(prefs.uiTheme, .glass) // web DEFAULT_UI_THEME

        prefs.setUiTheme(.kinetic)
        XCTAssertEqual(prefs.uiTheme, .kinetic)
        // Byte-same raw value under the web's exact key.
        XCTAssertEqual(defaults.string(forKey: PulsePrefs.uiThemeKey), "kinetic")
        XCTAssertEqual(PulsePrefs.uiThemeKey, "pulse.uiTheme.v2")

        // A fresh instance rehydrates the selection.
        let reloaded = PulsePrefs(defaults: defaults)
        XCTAssertEqual(reloaded.uiTheme, .kinetic)
    }

    @MainActor
    func testPrefsToleratesJunkStoredValues() {
        let suiteName = "pulse-ui-theme-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set("not-a-theme", forKey: PulsePrefs.uiThemeKey)
        let prefs = PulsePrefs(defaults: defaults)
        XCTAssertEqual(prefs.uiTheme, .glass)
    }

    @MainActor
    func testSetUiThemeCyclesThroughEveryValue() {
        let suiteName = "pulse-ui-theme-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let prefs = PulsePrefs(defaults: defaults)
        let all: [PulseUiThemeId] = [.glass, .kinetic, .minimal, .dynamic, .aero]
        XCTAssertTrue(all.allSatisfy { theme in
            prefs.setUiTheme(theme)
            let stored = defaults.string(forKey: PulsePrefs.uiThemeKey) ?? ""
            return stored == theme.rawValue && prefs.uiTheme == theme
        })
    }
}

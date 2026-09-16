import XCTest
@testable import Pulse

/// Wave 8 — pure-logic parity tests:
///   • quiet-hours math — verbatim port of web isQuietHoursNow
///     (src/lib/pulse-settings.ts:73-83): normal window, wrap across
///     midnight, degenerate equal window, malformed HH:mm fallbacks.
///   • prefs merge parity — port of web mergePrefs (src/lib/prefs-defaults.ts):
///     defaults + whitelist-clamped shallow merge, server value wins.
final class Wave8LogicTests: XCTestCase {

    private let calendar = Calendar.current

    private func localTime(hour: Int, minute: Int) -> Date {
        var components = calendar.dateComponents([.year, .month, .day], from: Date())
        components.hour = hour
        components.minute = minute
        return calendar.date(from: components)!
    }

    private func quiet(_ hour: Int, _ minute: Int, start: String, end: String, enabled: Bool = true) -> Bool {
        PulseWave8Logic.isQuietHoursNow(
            enabled: enabled,
            start: start,
            end: end,
            now: localTime(hour: hour, minute: minute),
            calendar: calendar,
        )
    }

    // ── isQuietHoursNow ──────────────────────────────────────────

    /// Normal window fully inside one day.
    func testQuietHoursNormalWindow() {
        XCTAssertTrue(quiet(9, 30, start: "09:00", end: "17:00"))
        XCTAssertTrue(quiet(9, 0, start: "09:00", end: "17:00"))  // start inclusive
        XCTAssertFalse(quiet(17, 0, start: "09:00", end: "17:00")) // end exclusive
        XCTAssertFalse(quiet(8, 59, start: "09:00", end: "17:00"))
        XCTAssertFalse(quiet(18, 0, start: "09:00", end: "17:00"))
    }

    /// Overnight windows (22:00 → 07:00) wrap across midnight: cur >= start
    /// OR cur < end.
    func testQuietHoursWrapAcrossMidnight() {
        XCTAssertTrue(quiet(22, 0, start: "22:00", end: "07:00"))  // start inclusive
        XCTAssertTrue(quiet(23, 30, start: "22:00", end: "07:00"))
        XCTAssertTrue(quiet(0, 0, start: "22:00", end: "07:00"))
        XCTAssertTrue(quiet(3, 15, start: "22:00", end: "07:00"))
        XCTAssertTrue(quiet(6, 59, start: "22:00", end: "07:00"))
        XCTAssertFalse(quiet(7, 0, start: "22:00", end: "07:00"))  // end exclusive
        XCTAssertFalse(quiet(12, 0, start: "22:00", end: "07:00"))
        XCTAssertFalse(quiet(21, 59, start: "22:00", end: "07:00"))
    }

    /// Degenerate equal window = off (verbatim `start === end` guard), even
    /// when the current time equals the boundary.
    func testQuietHoursDegenerateEqualWindow() {
        XCTAssertFalse(quiet(9, 0, start: "09:00", end: "09:00"))
        XCTAssertFalse(quiet(15, 42, start: "00:00", end: "00:00"))
        // minutesOf("24:61") clamps to 23*60+59 == 1439 too — equal + off.
        XCTAssertFalse(quiet(23, 59, start: "24:61", end: "23:59"))
    }

    /// Disabled quiet hours NEVER report active, whatever the window.
    func testQuietHoursDisabledAlwaysFalse() {
        XCTAssertFalse(quiet(23, 30, start: "22:00", end: "07:00", enabled: false))
        XCTAssertFalse(quiet(3, 0, start: "00:00", end: "23:59", enabled: false))
    }

    /// minutesOf: NaN-safe fallbacks + clamping (web parity).
    func testMinutesOfMalformedAndClamped() {
        XCTAssertEqual(PulseWave8Logic.minutesOf("22:00"), 22 * 60)
        XCTAssertEqual(PulseWave8Logic.minutesOf("07:05"), 7 * 60 + 5)
        XCTAssertEqual(PulseWave8Logic.minutesOf(" 9:30 "), 9 * 60 + 30) // trimmed
        XCTAssertEqual(PulseWave8Logic.minutesOf("25:99"), 23 * 60 + 59) // clamped 0-23 / 0-59
        XCTAssertEqual(PulseWave8Logic.minutesOf("nope"), 0)
        XCTAssertEqual(PulseWave8Logic.minutesOf("9:5"), 0)   // needs 2-digit minutes
        XCTAssertEqual(PulseWave8Logic.minutesOf(""), 0)
        XCTAssertEqual(PulseWave8Logic.minutesOf("-1:30"), 0) // regex refuses the sign
    }

    // ── mergedPrefs (web mergePrefs parity) ─────────────────────

    /// mergePrefs over an empty blob == DEFAULT_PREFERENCES.
    func testPrefsMergeDefaults() {
        let merged = PulseWave8Logic.mergedPrefs(base: PulsePrefsValues(), patch: WirePulsePrefs())
        XCTAssertEqual(merged.bubbleRadius, .lg)
        XCTAssertEqual(merged.density, .cozy)
        XCTAssertEqual(merged.wallpaper, .none)
        XCTAssertTrue(merged.notifPreviews)
        XCTAssertTrue(merged.notifSound)
        XCTAssertFalse(merged.notifVibrate)
        XCTAssertTrue(merged.lastSeenVisible)
        XCTAssertTrue(merged.readReceipts)
        XCTAssertTrue(merged.typingVisible)
        XCTAssertFalse(merged.reducedMotion)
    }

    /// Server values win over the base, booleans pass straight through.
    func testPrefsMergeServerWins() {
        let patch = WirePulsePrefs(
            bubbleRadius: "pill",
            density: "compact",
            wallpaper: "forest",
            notifPreviews: false,
            notifSound: false,
            notifVibrate: true,
            lastSeenVisible: false,
            readReceipts: false,
            typingVisible: false,
            reducedMotion: true,
        )
        let merged = PulseWave8Logic.mergedPrefs(base: PulsePrefsValues(), patch: patch)
        XCTAssertEqual(merged.bubbleRadius, .pill)
        XCTAssertEqual(merged.density, .compact)
        XCTAssertEqual(merged.wallpaper, .forest)
        XCTAssertFalse(merged.notifPreviews)
        XCTAssertFalse(merged.notifSound)
        XCTAssertTrue(merged.notifVibrate)
        XCTAssertFalse(merged.lastSeenVisible)
        XCTAssertFalse(merged.readReceipts)
        XCTAssertFalse(merged.typingVisible)
        XCTAssertTrue(merged.reducedMotion)
    }

    /// Malformed tokens are clamped away (web RADIUS/DENSITY/WALLPAPER
    /// whitelists): the base value survives — never junk, never a throw.
    func testPrefsMergeClampsUnknownTokens() {
        let patch = WirePulsePrefs(bubbleRadius: "xl", density: "squeezed", wallpaper: "ocean")
        let merged = PulseWave8Logic.mergedPrefs(base: PulsePrefsValues(), patch: patch)
        XCTAssertEqual(merged.bubbleRadius, .lg)
        XCTAssertEqual(merged.density, .cozy)
        XCTAssertEqual(merged.wallpaper, .none)

        // A non-default base survives an invalid patch too.
        let custom = PulsePrefsValues(bubbleRadius: .md, density: .compact, wallpaper: .dusk)
        let kept = PulseWave8Logic.mergedPrefs(base: custom, patch: patch)
        XCTAssertEqual(kept.bubbleRadius, .md)
        XCTAssertEqual(kept.density, .compact)
        XCTAssertEqual(kept.wallpaper, .dusk)
    }

    /// A partial patch touches ONLY its fields (shallow merge, not replace).
    func testPrefsMergeIsShallow() {
        let base = PulsePrefsValues(wallpaper: .mono, notifPreviews: false, notifSound: false)
        let merged = PulseWave8Logic.mergedPrefs(base: base, patch: WirePulsePrefs(reducedMotion: true))
        XCTAssertTrue(merged.reducedMotion)      // patched
        XCTAssertFalse(merged.notifPreviews)     // untouched
        XCTAssertFalse(merged.notifSound)        // untouched
        XCTAssertEqual(merged.wallpaper, .mono)  // untouched
        XCTAssertEqual(merged.density, .cozy)    // untouched default
    }

    // ── native bubble/density token values ──────────────────────

    /// The native corner radii the ChatRoomView consumes (md=10, lg=16,
    /// pill=26) and the density row spacing — pinned so a refactor can't
    /// silently drift the visual contract.
    func testNativeTokenValues() {
        XCTAssertEqual(PulseBubbleRadius.md.cornerRadius, 10)
        XCTAssertEqual(PulseBubbleRadius.lg.cornerRadius, 16)
        XCTAssertEqual(PulseBubbleRadius.pill.cornerRadius, 26)
        XCTAssertEqual(PulseDensity.cozy.rowSpacing, 6)
        XCTAssertEqual(PulseDensity.compact.rowSpacing, 2)
    }
}

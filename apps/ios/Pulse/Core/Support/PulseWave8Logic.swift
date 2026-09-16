import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 8 — pure logic parity with the web (no transport, no UI):
//   • isQuietHoursNow — verbatim port of src/lib/pulse-settings.ts:73-83
//   • mergedPrefs     — port of src/lib/prefs-defaults.ts mergePrefs
//     (defaults + whitelist-clamped shallow merge, server value wins)
// ─────────────────────────────────────────────────────────────
public enum PulseWave8Logic {

    // ── quiet hours (local card, Notifications section) ──────

    /// 'HH:MM' → minutes since midnight; NaN-safe (falls back to 0).
    /// Verbatim: /^(\d{1,2}):(\d{2})$/ on the trimmed value; hour clamped
    /// 0-23, minute clamped 0-59; a malformed string is 0 — never throws.
    public static func minutesOf(_ hhmm: String) -> Int {
        let trimmed = hhmm.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let match = quietTimeRegex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)),
              match.numberOfRanges >= 3,
              let hourRange = Range(match.range(at: 1), in: trimmed),
              let minuteRange = Range(match.range(at: 2), in: trimmed),
              let hour = Int(trimmed[hourRange]),
              let minute = Int(trimmed[minuteRange]) else {
            return 0
        }
        let h = min(max(hour, 0), 23)
        let min = min(max(minute, 0), 59)
        return h * 60 + min
    }

    private static let quietTimeRegex = try? NSRegularExpression(pattern: #"^(\d{1,2}):(\d{2})$"#)

    /// True when the current LOCAL time sits inside the quiet window.
    /// Supports overnight windows (e.g. 22:00 → 07:00). Verbatim port of
    /// web isQuietHoursNow: `start == end → false` (degenerate window = off);
    /// `start < end → cur in [start, end)`; otherwise the wrap branch.
    public static func isQuietHoursNow(
        enabled: Bool,
        start: String,
        end: String,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> Bool {
        guard enabled else { return false }
        let components = calendar.dateComponents([.hour, .minute], from: now)
        let cur = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        let startMin = minutesOf(start)
        let endMin = minutesOf(end)
        if startMin == endMin { return false } // degenerate window = off
        return startMin < endMin ? (cur >= startMin && cur < endMin) : (cur >= startMin || cur < endMin)
    }

    // ── prefs merge parity (src/lib/prefs-defaults.ts mergePrefs) ──

    /// Shallow-merges a server patch over `base` — the patch field wins ONLY
    /// when it decoded to a real value, and the string tokens are clamped to
    /// the web whitelists (RADIUS / DENSITY / WALLPAPER): malformed values
    /// are silently discarded, never thrown, never junk. `base` starts from
    /// PulsePrefsValues() (DEFAULT_PREFERENCES) at the call sites that mirror
    /// mergePrefs over a stored blob.
    public static func mergedPrefs(base: PulsePrefsValues, patch: WirePulsePrefs) -> PulsePrefsValues {
        var out = base
        if let raw = patch.bubbleRadius, let value = PulseBubbleRadius(rawValue: raw) {
            out.bubbleRadius = value
        }
        if let raw = patch.density, let value = PulseDensity(rawValue: raw) {
            out.density = value
        }
        if let raw = patch.wallpaper, let value = PulseWallpaper(rawValue: raw) {
            out.wallpaper = value
        }
        if let value = patch.notifPreviews { out.notifPreviews = value }
        if let value = patch.notifSound { out.notifSound = value }
        if let value = patch.notifVibrate { out.notifVibrate = value }
        if let value = patch.lastSeenVisible { out.lastSeenVisible = value }
        if let value = patch.readReceipts { out.readReceipts = value }
        if let value = patch.typingVisible { out.typingVisible = value }
        if let value = patch.reducedMotion { out.reducedMotion = value }
        return out
    }
}

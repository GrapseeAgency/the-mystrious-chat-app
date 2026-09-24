import UIKit

// ─────────────────────────────────────────────────────────────
// R7 — ONE shared motion gate for every surface that used to
// hardcode `reduceMotion: false` in its `.pulse(...)` animation
// calls (poll votes, topic rail, folders, voice rooms, space,
// user page, toasts, channels…). True when the SYSTEM Reduce
// Motion setting is on OR the in-app toggle is enabled
// (Settings → Accessibility — persisted at PulsePrefs.reducedMotionKey
// = "prefs.reducedMotion", the SAME key the toggle reads/writes and
// PATCHes to the server, so the in-app switch controls these
// surfaces too). Read at CALL time — no cached snapshot — so
// toggling either switch takes effect on the next render.
// ─────────────────────────────────────────────────────────────
public enum PulseMotion {
    public static var reduceMotion: Bool {
        UIAccessibility.isReduceMotionEnabled
            || UserDefaults.standard.bool(forKey: PulsePrefs.reducedMotionKey)
    }
}

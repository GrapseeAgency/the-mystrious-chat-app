import UIKit
import AudioToolbox

/// Tactile feedback — light impact for taps, success notice for sends,
/// mirroring the web's press micro-interaction feel.
/// Wave 8 — the web gates haptics twice: the master `hapticsOn` toggle
/// (pulse-settings.haptic()) and the quiet-hours window ("Silence sounds
/// and vibration inside the window"). Both inputs live on PulsePrefs and
/// land here as plain statics so the gate evaluates at CALL time from any
/// isolation context; the session's 1s loop keeps the quiet snapshot fresh.
public enum PulseHaptics {
    /// Settings → Accessibility → Haptics (local, default on).
    public static var isEnabled: Bool = true
    /// True while the LOCAL quiet-hours window covers right now.
    public static var quietNow: Bool = false

    static func allowed() -> Bool {
        isEnabled && !quietNow
    }

    public static func tap() {
        guard allowed() else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    public static func success() {
        guard allowed() else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    public static func warning() {
        guard allowed() else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }

    /// Wave 8 — incoming-message buzz (notifVibrate toggle, web haptic(20)
    /// parity). Quiet hours gate it exactly like every other haptic.
    public static func incoming() {
        guard allowed() else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}

/// Wave 8 — audible feedback. The web plays a two-note WebAudio ding for
/// incoming messages; the native equivalent uses the system "message
/// received" sound (no bundled asset, no new dependency). Gated by the
/// quiet-hours window + the sound toggles at the call site.
public enum PulseSounds {
    /// Soft incoming-message blip (system sound 1003).
    public static func incoming() {
        AudioServicesPlaySystemSound(1003)
    }
}

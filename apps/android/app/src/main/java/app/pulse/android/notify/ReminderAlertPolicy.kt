package app.pulse.android.notify

// Wave 8 - process-wide alert prefs mirror for notification-time
// decisions. PulseApplication collects the prefs flows into these
// @Volatile fields (cheap, no DataStore read on the notify path);
// quiet-hours is EVALUATED at show() time because the window is
// time-dependent. Math = PulseWave8Logic.isQuietHoursNow (web port).
import app.pulse.protocol.PulseWave8Logic

object ReminderAlertPolicy {
    /** Server prefs blob (default sound on, vibrate off - web defaults). */
    @Volatile
    var soundOn: Boolean = true

    @Volatile
    var vibrateOn: Boolean = false

    /** LOCAL settings (web pulse.settings.v1) - never ride the server blob. */
    @Volatile
    var quietHoursOn: Boolean = false

    @Volatile
    var quietStart: String = "22:00"

    @Volatile
    var quietEnd: String = "07:00"

    /**
     * R14 - device-local master ding gate (web pulse.settings.v1 soundOn,
     * default true). [soundOn] above mirrors the server notifSound blob; the
     * web keeps the incoming ding gated by the DEVICE store (pulse-settings.ts
     * playIncomingPing) and the per-account "Message pop" separately, so the
     * incoming path consults BOTH (device master AND account pop), while the
     * reminder notifier keeps its existing notifSound-only gate.
     */
    @Volatile
    var deviceSoundOn: Boolean = true

    /** Evaluated at notification time, not cached - the window moves. */
    fun quietNow(): Boolean = PulseWave8Logic.isQuietHoursNow(quietHoursOn, quietStart, quietEnd)
}

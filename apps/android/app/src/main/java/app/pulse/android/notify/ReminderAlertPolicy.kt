package app.pulse.android.notify

// ─────────────────────────────────────────────────────────────
// Wave 8 — process-wide alert prefs mirror for notification-time
// decisions. PulseApplication collects the prefs flows into these
// @Volatile fields (cheap, no DataStore read on the notify path);
// quiet-hours is EVALUATED at show() time because the window is
// time-dependent. Math = PulseWave8Logic.isQuietHoursNow (web port).
// ─────────────────────────────────────────────────────────────
import app.pulse.protocol.PulseWave8Logic

object ReminderAlertPolicy {
    /** Server prefs blob (default sound on, vibrate off — web defaults). */
    @Volatile
    var soundOn: Boolean = true

    @Volatile
    var vibrateOn: Boolean = false

    /** LOCAL settings (web pulse.settings.v1) — never ride the server blob. */
    @Volatile
    var quietHoursOn: Boolean = false

    @Volatile
    var quietStart: String = "22:00"

    @Volatile
    var quietEnd: String = "07:00"

    /** Evaluated at notification time, not cached — the window moves. */
    fun quietNow(): Boolean = PulseWave8Logic.isQuietHoursNow(quietHoursOn, quietStart, quietEnd)
}

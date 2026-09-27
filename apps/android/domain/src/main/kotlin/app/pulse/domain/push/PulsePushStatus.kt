package app.pulse.domain.push

/**
 * R9 — device-level remote-push status, rendered honestly by the Settings →
 * Notifications row (feature-settings). Lives in DOMAIN because the push
 * wiring itself ([app.pulse.android.push.PulsePush]) is app-module code that
 * feature modules cannot see; :app publishes snapshots here at every state
 * transition and sets [resyncHook] so the row's "Re-check" can re-run the
 * FCM registration sync through the same domain seam.
 *
 * This is DEVICE state, never a user preference: nothing here is persisted,
 * nothing is faked — an unarmed build reports Off even though the toggle
 * rows above it stay fully functional (they are local + server prefs).
 */
object PulsePushStatus {

    data class Snapshot(
        /** Real FirebaseApp exists (baked pulse_fcm_* credentials). */
        val armed: Boolean,
        /** An FCM token has been handed over (pending bind until sign-in). */
        val hasToken: Boolean,
        /** A viewer identity exists to bind the token to. */
        val viewerBound: Boolean,
        /** Wall clock of the last publish — lets the UI show staleness. */
        val checkedAtMs: Long = 0L,
    )

    @Volatile
    private var current: Snapshot = Snapshot(armed = false, hasToken = false, viewerBound = false)

    /** Set by :app at startup — re-runs the FCM registration sync. */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun publish(snapshot: Snapshot) {
        current = snapshot
    }

    fun snapshot(): Snapshot = current

    /** Fire the :app-side resync (no-op when the hook was never set). */
    fun requestResync() {
        resyncHook?.invoke()
    }
}

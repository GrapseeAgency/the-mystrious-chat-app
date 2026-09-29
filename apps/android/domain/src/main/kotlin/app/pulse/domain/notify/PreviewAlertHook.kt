package app.pulse.domain.notify

/**
 * R14 gap 2 — the Settings → Notifications "Preview alert" test row (web
 * settings-screen.tsx:1258-1275) must run the REAL incoming attention path
 * (the ding + haptic honoring soundOn/haptics/quiet hours), which lives in
 * app-module code ([app.pulse.android.notify.IncomingAttention]) that the
 * feature-settings module cannot see.
 *
 * This is the same inversion the remote-push row uses
 * ([app.pulse.domain.push.PulsePushStatus.resyncHook]): :app registers the
 * implementation at startup, the feature calls through the seam, and a build
 * where the hook was never set degrades to a no-op — never a crash.
 */
object PreviewAlertHook {

    /** Set by :app at startup — plays the real ding, buzzing when [hapticsOn]. */
    @Volatile
    var play: ((hapticsOn: Boolean) -> Unit)? = null

    /** Fire the :app-side preview alert (no-op when the hook was never set). */
    fun playPreview(hapticsOn: Boolean) {
        play?.invoke(hapticsOn)
    }
}

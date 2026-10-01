package app.pulse.android

import android.app.Application
import app.pulse.core.PulseEndpoints
import app.pulse.data.remote.ManifestEndpoints
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.ui.update.LiveUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class PulseApplication : Application() {

    @Inject lateinit var manifestEndpoints: ManifestEndpoints

    @Inject
    lateinit var prefs: PulsePrefsStore

    @Inject
    lateinit var repository: app.pulse.domain.repository.PulseRepository

    // R8 Task 3-c - OS call integration + group calls start with the process.
    @Inject
    lateinit var telecomCallController: app.pulse.feature.calls.TelecomCallController

    @Inject
    lateinit var groupCallEngine: app.pulse.feature.calls.GroupCallEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Startup gateway resolution - strict precedence, zero fake hosts:
        //   1. Start OFFLINE (blank base). A static CDN can never serve /api/*
        //      - pointing REST at one is a guaranteed 404, so we simply don't.
        //   2. The user's saved Profile → Connection server address wins.
        //   3. Otherwise the Wave 0 deployment hook: the secure-vault overrides
        //      re-apply instantly, then update-manifest.json is fetched (3s
        //      timeout, silent) and its `gateway` / `socket` fields adopted
        //      when present - ops can publish a live origin without a rebuild.
        //   4. Still nothing → stay offline-first, honestly.
        PulseEndpoints.applyBase(null)

        // R8 Task 3-c - remote push: fail-closed Firebase init (feature stays
        // OFF with empty credentials), then the honest notification channels.
        app.pulse.android.push.PulsePush.init(this)
        app.pulse.android.push.PulsePush.ensureChannels(this)
        // R9 - the Settings → Notifications "Remote push" row re-runs the FCM
        // registration sync through this domain seam (feature-settings cannot
        // see :app where PulsePush lives).
        app.pulse.domain.push.PulsePushStatus.resyncHook = {
            app.pulse.android.push.PulsePush.syncRegistration(repository)
        }
        // R14 gap 2 - the Settings "Preview alert" row runs the REAL ding/buzz
        // path (same domain seam as the push row above: feature-settings
        // cannot see :app where IncomingAttention lives).
        app.pulse.domain.notify.PreviewAlertHook.play = { hapticsOn ->
            app.pulse.android.notify.IncomingAttention.previewAlert(this, hapticsOn)
        }

        // R8 Task 3-c - Telecom self-managed PhoneAccount registration at app
        // start (idempotent; a refusal is logged and the call paths fall back
        // to the in-app ring + full-screen-notification idiom).
        if (app.pulse.feature.calls.TelecomRegistrar.register(this)) {
            telecomCallController.start()
        } else {
            android.util.Log.i("PulseApp", "Telecom unavailable - calls stay in-app with the notification fallback")
        }

        // R8 Task 3-c - the group-call engine's event collectors live for the
        // whole process (same pattern as the 1:1 CallEngine's init block).
        groupCallEngine.start()

        // Wave 8 - mirror alert prefs for the notification path (quiet hours,
        // reminder sound/vibration). Cheap flow collection, evaluated live.
        appScope.launch {
            repository.pulsePrefs.collect { p ->
                app.pulse.android.notify.ReminderAlertPolicy.soundOn = p.notifSound ?: true
                app.pulse.android.notify.ReminderAlertPolicy.vibrateOn = p.notifVibrate ?: false
                // R7 item 7 - the notifPreviews gate finally rides the
                // incoming-attention path (toast preview vs generic line).
                app.pulse.android.notify.IncomingAttention.previewsOn = p.notifPreviews ?: true
            }
        }
        appScope.launch {
            prefs.quietHoursOn.collect { app.pulse.android.notify.ReminderAlertPolicy.quietHoursOn = it }
        }
        // R14 - the device-local ding master rides the SAME mirror pattern
        // (web pulse.settings.v1 soundOn - the incoming-ding gate).
        appScope.launch {
            prefs.soundOn.collect { app.pulse.android.notify.ReminderAlertPolicy.deviceSoundOn = it }
        }
        appScope.launch {
            prefs.quietStart.collect { app.pulse.android.notify.ReminderAlertPolicy.quietStart = it }
        }
        appScope.launch {
            prefs.quietEnd.collect { app.pulse.android.notify.ReminderAlertPolicy.quietEnd = it }
        }

        // R6 - M4: incoming-message attention (foreground ding/buzz honoring
        // quiet hours + per-room mute + the Wave-8 sound/vibration prefs).
        // Rides the SAME repository event bus the chats/room surfaces use;
        // the sender/echo/mute gates live inside IncomingAttention.
        appScope.launch {
            repository.events().collect { event ->
                if (event is app.pulse.domain.repository.PulseEvent.MessageReceived) {
                    app.pulse.android.notify.IncomingAttention.onMessageReceived(
                        this@PulseApplication,
                        event.conversationId,
                        event.message,
                        repository.viewerId,
                    )
                }
            }
        }
        appScope.launch {
            repository.observeConversations().collect { list ->
                app.pulse.android.notify.IncomingAttention.syncMutes(list)
            }
        }

        appScope.launch {
            val userBase = runCatching { prefs.serverBase.first() }.getOrNull()
            if (!userBase.isNullOrBlank()) {
                // The user's explicit server beats every manifest override.
                PulseEndpoints.applyBase(userBase)
                return@launch
            }
            runCatching { manifestEndpoints.applyPersisted() }
            runCatching { manifestEndpoints.fetchAndApply() }
        }

        // LiveUpdate quiet check - throttled inside, silent on failure, so the
        // update surfaces are ready before the first frame is drawn.
        appScope.launch { runCatching { LiveUpdater.syncFrom(this@PulseApplication) } }
    }
}

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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Startup gateway resolution — strict precedence, zero fake hosts:
        //   1. Start OFFLINE (blank base). A static CDN can never serve /api/*
        //      — pointing REST at one is a guaranteed 404, so we simply don't.
        //   2. The user's saved Profile → Connection server address wins.
        //   3. Otherwise the Wave 0 deployment hook: the secure-vault overrides
        //      re-apply instantly, then update-manifest.json is fetched (3s
        //      timeout, silent) and its `gateway` / `socket` fields adopted
        //      when present — ops can publish a live origin without a rebuild.
        //   4. Still nothing → stay offline-first, honestly.
        PulseEndpoints.applyBase(null)

        // Wave 8 — mirror alert prefs for the notification path (quiet hours,
        // reminder sound/vibration). Cheap flow collection, evaluated live.
        appScope.launch {
            repository.pulsePrefs.collect { p ->
                app.pulse.android.notify.ReminderAlertPolicy.soundOn = p.notifSound ?: true
                app.pulse.android.notify.ReminderAlertPolicy.vibrateOn = p.notifVibrate ?: false
            }
        }
        appScope.launch {
            prefs.quietHoursOn.collect { app.pulse.android.notify.ReminderAlertPolicy.quietHoursOn = it }
        }
        appScope.launch {
            prefs.quietStart.collect { app.pulse.android.notify.ReminderAlertPolicy.quietStart = it }
        }
        appScope.launch {
            prefs.quietEnd.collect { app.pulse.android.notify.ReminderAlertPolicy.quietEnd = it }
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

        // LiveUpdate quiet check — throttled inside, silent on failure, so the
        // update surfaces are ready before the first frame is drawn.
        appScope.launch { runCatching { LiveUpdater.syncFrom(this@PulseApplication) } }
    }
}

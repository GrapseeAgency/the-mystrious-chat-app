package app.pulse.android

import android.app.Application
import app.pulse.core.PulseEndpoints
import app.pulse.data.remote.ManifestEndpoints
import app.pulse.ui.update.LiveUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PulseApplication : Application() {

    @Inject lateinit var manifestEndpoints: ManifestEndpoints

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Deployment endpoints from BuildConfig (bake a public gateway in with
        // -PpulseGateway=…); defaults target the emulator loopback.
        PulseEndpoints.gatewayHttpUrl = BuildConfig.PULSE_GATEWAY
        PulseEndpoints.socketUrl = BuildConfig.PULSE_SOCKET

        // Wave 0 deployment hook, ordered so overrides ALWAYS beat network:
        //   1. re-apply the persisted secure-vault overrides (instant, no IO);
        //   2. LiveUpdate quiet check — throttled inside, silent on failure;
        //   3. fetch update-manifest.json (3s timeout, silent) and adopt its
        //      `gateway` / `socket` fields when present — today's manifest
        //      carries neither, so the client stays offline-first on the baked
        //      CDN until a real host is deployed; the hook works the day they do.
        val persisted = appScope.launch {
            runCatching { manifestEndpoints.applyPersisted() }
        }
        appScope.launch {
            persisted.join()
            runCatching { LiveUpdater.syncFrom(this@PulseApplication) }
        }
        appScope.launch {
            persisted.join()
            runCatching { manifestEndpoints.fetchAndApply() }
        }
    }
}

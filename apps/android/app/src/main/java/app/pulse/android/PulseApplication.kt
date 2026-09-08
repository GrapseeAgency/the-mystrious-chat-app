package app.pulse.android

import android.app.Application
import app.pulse.core.PulseEndpoints
import app.pulse.ui.update.LiveUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PulseApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Deployment endpoints from BuildConfig (bake a public gateway in with
        // -PpulseGateway=…); defaults target the emulator loopback.
        PulseEndpoints.gatewayHttpUrl = BuildConfig.PULSE_GATEWAY
        PulseEndpoints.socketUrl = BuildConfig.PULSE_SOCKET

        // LiveUpdate quiet check — throttled inside, silent on failure, so the
        // update surfaces are ready before the first frame is drawn.
        appScope.launch { LiveUpdater.syncFrom(this@PulseApplication) }
    }
}

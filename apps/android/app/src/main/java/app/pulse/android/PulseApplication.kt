package app.pulse.android

import android.app.Application
import app.pulse.core.PulseEndpoints
import app.pulse.ui.update.LiveUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

        // Manifest-driven gateway resolution (N5-c): the distribution manifest
        // may carry a live "gateway" URL (e.g. an interim tunnel or the future
        // VPS). Fetch it quietly in the background; on ANY failure keep the
        // BuildConfig defaults. Socket routing goes through the same base URL
        // via the XTransformPort relay query (see PulseSocketClient).
        appScope.launch {
            runCatching {
                val conn = URL(
                    "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json",
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = true
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val gateway = JSONObject(body).optString("gateway", "")
                    if (gateway.isNotEmpty()) {
                        PulseEndpoints.gatewayHttpUrl = gateway
                        PulseEndpoints.socketUrl = gateway
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}

package app.pulse.android

import android.app.Application
import app.pulse.core.PulseEndpoints
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.ui.update.LiveUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@HiltAndroidApp
class PulseApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var prefs: PulsePrefsStore

    override fun onCreate() {
        super.onCreate()

        // Startup gateway resolution — strict precedence, zero fake hosts:
        //   1. Start OFFLINE (blank base). A static CDN can never serve /api/*
        //      — pointing REST at one is a guaranteed 404, so we simply don't.
        //   2. The user's saved Profile → Connection server address wins.
        //   3. Otherwise the distribution manifest's "gateway" field (the repo
        //      CDN is static but that file is real) — lets ops publish a live
        //      origin without a rebuild.
        //   4. Still nothing → stay offline-first, honestly.
        PulseEndpoints.applyBase(null)

        // LiveUpdate quiet check — throttled inside, silent on failure, so the
        // update surfaces are ready before the first frame is drawn.
        appScope.launch { LiveUpdater.syncFrom(this@PulseApplication) }

        appScope.launch {
            val userBase = runCatching { prefs.serverBase.first() }.getOrNull()
            if (!userBase.isNullOrBlank()) {
                PulseEndpoints.applyBase(userBase)
                return@launch
            }
            runCatching {
                val conn = URL(
                    "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json",
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = true
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    PulseEndpoints.applyBase(JSONObject(body).optString("gateway", "").ifBlank { null })
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}

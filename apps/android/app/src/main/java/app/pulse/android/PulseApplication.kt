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

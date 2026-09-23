package app.pulse.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.SecureSessionStore
import app.pulse.data.local.SessionTokenStore
import app.pulse.data.local.SessionVault
import app.pulse.data.remote.ManifestEndpoints
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Device-session state: viewer identity + appearance prefs (DataStore-backed). */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val prefs: PulsePrefsStore,
    private val repo: PulseRepository,
    private val secureSessionStore: SecureSessionStore,
    private val sessionTokenStore: SessionTokenStore,
    private val manifestEndpoints: ManifestEndpoints,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    init {
        // Cold start order (Wave 0): persisted endpoint overrides BEFORE any
        // network, then re-seed the plaintext prefs mirror from the encrypted
        // vault so the identity flows light up even after the legacy viewer.*
        // keys were folded away.
        viewModelScope.launch {
            runCatching { manifestEndpoints.applyPersisted() }
            // Wave 8 — the session credential warms the synchronous cache
            // before the first REST call so the Bearer header rides along.
            runCatching { sessionTokenStore.load() }
            runCatching {
                val vaultJson = secureSessionStore.load() ?: return@runCatching
                val vault = PulseJson.decodeFromString(SessionVault.serializer(), vaultJson)
                if (vault.viewerId != null && prefs.viewerId.first() == null) {
                    prefs.setViewer(vault.viewerId, vault.viewerName, vault.viewerColor)
                }
            }
        }
        // Wave 8 — a 401 or relay join:error proved the stored token invalid
        // or rotated: clear the device session and surface the honest
        // re-login notice on the onboarding screen.
        viewModelScope.launch {
            sessionTokenStore.invalidated.collect { invalid ->
                if (invalid && prefs.viewerId.first() != null) {
                    _sessionNotice.value =
                        "Your session ended. Log in again to reclaim your identity."
                    prefs.setViewer(null, null)
                    runCatching { secureSessionStore.delete() }
                }
            }
        }
        // Outbox → WorkManager bridge: a pending queue always has an expedited,
        // network-constrained worker behind it (unique-name REPLACE).
        viewModelScope.launch {
            repo.observeOutbox().collect { entries ->
                if (entries.isNotEmpty()) OutboxWorker.enqueue(appContext)
            }
        }
    }

    private val _sessionNotice = MutableStateFlow<String?>(null)

    /** One-shot honest notice when the session token was rejected (401 / join:error). */
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True once the persisted prefs have emitted at least once — gates the
     * onboarding screen so a fresh launch doesn't flash it before DataStore
     * hands back an existing viewer.
     */
    val hydrated: StateFlow<Boolean> = prefs.viewerId
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val viewerName: StateFlow<String?> = prefs.viewerName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val viewerColor: StateFlow<String?> = prefs.viewerColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fxMode: StateFlow<String> = prefs.fxMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "aurora")

    val darkOverride: StateFlow<String> = prefs.darkOverride
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    /** R2-C item 3 — design language (web pulse.uiTheme.v2 value string). */
    val uiTheme: StateFlow<String> = prefs.uiTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "glass")

    /** R4-B item 3 — navigation architecture (web pulse.navStyle.v2 parity). */
    val navStyle: StateFlow<app.pulse.protocol.PulseNavStyle> = prefs.navStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.pulse.protocol.PulseNavStyle.CAPSULE)

    val reducedMotion: StateFlow<Boolean> = prefs.reducedMotion
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val serverBase: StateFlow<String?> = prefs.serverBase
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun chooseViewer(id: String, name: String, color: String? = null) {
        viewModelScope.launch {
            // Identity switch — the old identity's credential must not ride along.
            runCatching { repo.clearSessionToken() }
            prefs.setViewer(id, name, color)
            saveVaultIdentity(id, name, null, color)
            repo.start(id)
        }
    }

    fun forgetViewer() {
        viewModelScope.launch {
            runCatching { repo.clearSessionToken() }
            prefs.setViewer(null, null)
            secureSessionStore.delete()
        }
    }

    fun bootstrap(viewerId: String?) {
        viewerId?.let { repo.start(it) }
    }

    /** Point REST + realtime at a user-provided origin (null = go offline). */
    fun setServerBase(value: String?) {
        PulseEndpoints.applyBase(value)
        viewModelScope.launch { prefs.setServerBase(value) }
    }

    /** Header theme toggle — cycles system → light → dark (web ThemeToggleButton). */
    fun setDarkOverride(value: String) {
        viewModelScope.launch { prefs.setDarkOverride(value) }
    }

    fun cycleDarkOverride() {
        val next = when (darkOverride.value) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        setDarkOverride(next)
    }

    /** Vault write — viewer identity only; endpoint override fields stay untouched. */
    private suspend fun saveVaultIdentity(id: String, name: String, username: String?, color: String?) {
        runCatching {
            val current = secureSessionStore.load()
                ?.let { runCatching { PulseJson.decodeFromString(SessionVault.serializer(), it) }.getOrNull() }
                ?: SessionVault()
            secureSessionStore.save(
                PulseJson.encodeToString(
                    SessionVault.serializer(),
                    current.copy(
                        viewerId = id,
                        viewerName = name,
                        viewerUsername = username,
                        viewerColor = color,
                    ),
                ),
            )
        }
    }
}

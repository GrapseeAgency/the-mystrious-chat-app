package app.pulse.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.SecureSessionStore
import app.pulse.data.local.SessionTokenStore
import app.pulse.data.local.SessionVault
import app.pulse.android.push.PulsePush
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
import kotlinx.coroutines.CancellationException
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

    private val _bootstrapped = MutableStateFlow(false)

    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    fun retryHydration() {
        viewModelScope.launch {
            _startupError.value = null
            try {
                val userBase = prefs.serverBase.first()
                if (!userBase.isNullOrBlank()) PulseEndpoints.applyBase(userBase)
                else manifestEndpoints.applyPersisted()
                sessionTokenStore.load()
                secureSessionStore.load()?.let { vaultJson ->
                    val vault = PulseJson.decodeFromString(SessionVault.serializer(), vaultJson)
                    if (vault.viewerId != null && prefs.viewerId.first() == null) {
                        prefs.setViewer(vault.viewerId, vault.viewerName, vault.viewerColor)
                    }
                }
                // Never expose the shell after a failed credential/storage read.
                prefs.viewerId.first()
                _bootstrapped.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _startupError.value = "Could not open local account storage. Retry without clearing your saved messages."
            }
        }
    }

    private val _sessionNotice = MutableStateFlow<String?>(null)

    /** One-shot honest notice when the session token was rejected (401 / join:error). */
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True only after endpoint, credential, and identity storage hydration.
     * Storage errors leave the shell gated with an explicit retry action.
     */
    val hydrated: StateFlow<Boolean> = _bootstrapped.asStateFlow()

    val viewerName: StateFlow<String?> = prefs.viewerName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val viewerColor: StateFlow<String?> = prefs.viewerColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fxMode: StateFlow<String> = prefs.fxMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "aurora")

    val darkOverride: StateFlow<String> = prefs.darkOverride
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    /** R2-C item 3 - design language (web pulse.uiTheme.v2 value string). */
    val uiTheme: StateFlow<String> = prefs.uiTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "glass")

    /** R4-B item 3 - navigation architecture (web pulse.navStyle.v2 parity). */
    val navStyle: StateFlow<app.pulse.protocol.PulseNavStyle> = prefs.navStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.pulse.protocol.PulseNavStyle.CAPSULE)

    val reducedMotion: StateFlow<Boolean> = prefs.reducedMotion
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val serverBase: StateFlow<String?> = prefs.serverBase
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        retryHydration()
        // Authentication failure is not permission to reclaim an identity by name.
        viewModelScope.launch {
            sessionTokenStore.invalidated.collect { invalid ->
                if (invalid) {
                    repo.stop()
                    OutboxWorker.cancel(appContext)
                    _sessionNotice.value = "Session expired. Pending messages are kept; sign in again to synchronize."
                }
            }
        }
        // One network-constrained worker owns retries; KEEP avoids canceling
        // an in-flight delivery on each queue emission.
        viewModelScope.launch {
            repo.observeOutbox().collect { entries ->
                if (entries.any { it.failureReason == null }) OutboxWorker.enqueue(appContext)
            }
        }
    }

    fun chooseViewer(id: String, name: String, color: String? = null) {
        viewModelScope.launch {
            // Identity switch - the old identity's credential must not ride along.
            runCatching { repo.clearSessionToken() }
            prefs.setViewer(id, name, color)
            saveVaultIdentity(id, name, null, color)
            repo.start(id)
        }
    }

    fun forgetViewer() {
        repo.stop()
        OutboxWorker.cancel(appContext)
        viewModelScope.launch {
            // Task 5-d - BEFORE the identity is cleared: DELETE /api/push/register
            // { token } with the stored FCM token (token-only body, no userId
            // needed), fire-and-forget - sign-out never blocks or fails on it.
            // PulsePush also clears its stored token so a stale one is not
            // re-registered after a re-login before a fresh token arrives.
            runCatching { PulsePush.signOut(repo) }
            runCatching { repo.clearSessionToken() }
            prefs.setViewer(null, null)
            secureSessionStore.delete()
        }
    }

    fun bootstrap(viewerId: String?) {
        if (viewerId == null) repo.stop() else repo.start(viewerId)
    }

    /** Point REST + realtime at a user-provided origin (null = go offline). */
    fun setServerBase(value: String?) {
        if (value.orEmpty().trimEnd('/') == PulseEndpoints.gatewayHttpUrl.trimEnd('/')) return
        _bootstrapped.value = false
        repo.stop()
        OutboxWorker.cancel(appContext)
        PulseEndpoints.applyBase(value)
        viewModelScope.launch {
            prefs.setServerBase(value)
            // A new server must never inherit the previous server's token.
            sessionTokenStore.clear()
            prefs.setViewer(null, null)
            secureSessionStore.delete()
            retryHydration()
        }
    }

    /** Header theme toggle - cycles system → light → dark (web ThemeToggleButton). */
    fun setDarkOverride(value: String) {
        viewModelScope.launch { prefs.setDarkOverride(value) }
    }

    // R71 - device-local settings passthroughs (web pulse.settings.v1 parity):
    // the Settings screen binds these directly, exactly like the web binds
    // pulseSettingsStore. The DataStore stays the single source of truth.

    /** Haptic feedback master toggle (web hapticsOn, default true). */
    val hapticsOn: StateFlow<Boolean> = prefs.hapticsOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Device-local master ding gate (web soundOn, default true). */
    val soundOn: StateFlow<Boolean> = prefs.soundOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Quiet hours window (web quietHoursOn/Start/End, defaults 22:00-07:00). */
    val quietHoursOn: StateFlow<Boolean> = prefs.quietHoursOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val quietStart: StateFlow<String> = prefs.quietStart
        .stateIn(viewModelScope, SharingStarted.Eagerly, "22:00")

    val quietEnd: StateFlow<String> = prefs.quietEnd
        .stateIn(viewModelScope, SharingStarted.Eagerly, "07:00")

    /** Telegram-style folder filter on the chats list (web listFilter). */
    val chatsListFilter: StateFlow<String> = prefs.chatsListFilter
        .stateIn(viewModelScope, SharingStarted.Eagerly, "all")

    fun setHapticsOn(value: Boolean) {
        viewModelScope.launch { prefs.setHapticsOn(value) }
    }

    fun setSoundOn(value: Boolean) {
        viewModelScope.launch { prefs.setSoundOn(value) }
    }

    fun setQuietHoursOn(value: Boolean) {
        viewModelScope.launch { prefs.setQuietHoursOn(value) }
    }

    fun setQuietStart(value: String) {
        viewModelScope.launch { prefs.setQuietStart(value) }
    }

    fun setQuietEnd(value: String) {
        viewModelScope.launch { prefs.setQuietEnd(value) }
    }

    fun setChatsListFilter(value: String) {
        viewModelScope.launch { prefs.setChatsListFilter(value) }
    }

    /** Design-language selection (web pulse.uiTheme.v2 value string). */
    fun setUiTheme(value: String) {
        viewModelScope.launch { prefs.setUiTheme(value) }
    }

    /** Navigation architecture selection (web pulse.navStyle.v2). */
    fun setNavStyle(value: app.pulse.protocol.PulseNavStyle) {
        viewModelScope.launch { prefs.setNavStyle(value) }
    }

    fun cycleDarkOverride() {
        val next = when (darkOverride.value) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        setDarkOverride(next)
    }

    /** Vault write - viewer identity only; endpoint override fields stay untouched. */
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

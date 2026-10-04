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
            // Wave 8 - the session credential warms the synchronous cache
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
        // R52-c - a rejected token NEVER tears the session down anymore: the
        // "Your session ended" onboarding kick the user hit is dead. The
        // device silently reclaims the SAME identity by name (Pulse
        // identities are name-keyed and self-declared - the login endpoint IS
        // the re-auth), the fresh token persists inside the repository, and
        // realtime re-arms. Offline -> nothing changes: the viewer stays, the
        // outbox keeps queueing, the retry happens on the next rejection
        // (throttled). Explicit sign-out (forgetViewer) is the ONLY way out.
        viewModelScope.launch {
            sessionTokenStore.invalidated.collect { invalid ->
                if (!invalid) return@collect
                val id = prefs.viewerId.first() ?: return@collect
                val name = prefs.viewerName.first().takeUnless { it.isNullOrBlank() }
                    ?: return@collect
                val now = System.currentTimeMillis()
                if (now - lastSilentReauth < SILENT_REAUTH_THROTTLE_MS) return@collect
                lastSilentReauth = now
                repo.login(name).fold(
                    onSuccess = {
                        _sessionNotice.value = null
                        repo.start(id)
                    },
                    onFailure = {
                        // Stay exactly where we are - the app remains fully
                        // usable offline; the next 401 retries (throttled).
                    },
                )
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

    /** R52-c throttle: at most one silent re-auth per 10s window. */
    private var lastSilentReauth = 0L

    private companion object {
        const val SILENT_REAUTH_THROTTLE_MS = 10_000L
    }

    /** One-shot honest notice when the session token was rejected (401 / join:error). */
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True once the persisted prefs have emitted at least once - gates the
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

    /** R54 - interface renderer gate (true = WebView-first artboard shell). */
    val webUi: StateFlow<Boolean> = prefs.webUi
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setWebUi(value: Boolean) {
        viewModelScope.launch { prefs.setWebUi(value) }
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
        viewerId?.let { repo.start(it) }
    }

    /** Point REST + realtime at a user-provided origin (null = go offline). */
    fun setServerBase(value: String?) {
        PulseEndpoints.applyBase(value)
        viewModelScope.launch { prefs.setServerBase(value) }
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

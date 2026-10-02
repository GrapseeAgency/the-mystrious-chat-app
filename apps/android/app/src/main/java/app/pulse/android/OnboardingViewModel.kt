package app.pulse.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.data.local.SessionVault
import app.pulse.data.local.SecureSessionStore
import app.pulse.data.repository.OnboardingError
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseJson
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Pulse onboarding - two steps, web parity (onboarding-screen.tsx):
//   1. display name + avatar color (existing reclaim-by-name flow)
//   2. @handle picker - auto-suggested from the name, live
//      availability via GET /api/users/check-username (debounced),
//      skippable. Creates the real account via POST /api/users.

enum class OnboardingStep { CONNECT, NAME, HANDLE }

const val NAME_MAX = 32
const val USERNAME_MIN = 3
const val USERNAME_MAX = 20
const val CHECK_DEBOUNCE_MS = 350L

private val HANDLE_RE = Regex("^[a-z0-9_]+$")

/** Mirrors normalizeUsername on the server: 3–20 chars, a-z0-9_ */
fun isValidHandle(value: String): Boolean =
    value.length in USERNAME_MIN..USERNAME_MAX && HANDLE_RE.matches(value)

/** Auto-suggest a handle from a display name (lowercase, sanitized). */
fun suggestHandleFromName(name: String): String = name
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9_]+"), "_")
    .trimStart('_')
    .trimEnd('_')
    .replace(Regex("_{2,}"), "_")
    .take(USERNAME_MAX)

/** Keep only chars the server would accept, lowercased, capped. */
fun sanitizeHandleInput(value: String): String = value
    .lowercase()
    .replace(Regex("[^a-z0-9_]"), "")
    .take(USERNAME_MAX)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.NAME,
    val name: String = "",
    val color: String = "emerald",
    val nameTaken: Boolean = false,
    // R50-a - connect gate (the offline-first honest front door)
    /** True once a live probe confirmed the candidate server answers. */
    val serverConnected: Boolean = false,
    /** The base URL the user is typing on the connect gate. */
    val serverUrl: String = "",
    val probing: Boolean = false,
    /** Connect-gate status line (probe result / demo login errors). */
    val connectNotice: String? = null,
    val connectNoticeTone: ConnectNoticeTone = ConnectNoticeTone.AMBER,
    // handle step
    val handle: String = "",
    val checking: Boolean = false,
    /** the handle the latest availability result belongs to (staleness guard) */
    val checkedHandle: String? = null,
    val checkAvailable: Boolean? = null,
    val checkSuggestion: String? = null,
    val serverTakenMessage: String? = null,
    val serverTakenSuggestion: String? = null,
    val pending: Boolean = false,
    val signingIn: Boolean = false,
    val notice: String? = null,
)

/** Tone of the connect-gate status line (emerald = good news). */
enum class ConnectNoticeTone { EMERALD, AMBER }

/** The seeded demo identity the connect gate offers after a live probe. */
const val DEMO_ACCOUNT_NAME = "Alice Chen"

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
    private val secureSessionStore: SecureSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var checkJob: Job? = null

    init {
        // R50-a - a fresh install with no gateway configured must NOT land on
        // the name step (every request there fails as a bare "server error").
        // The honest front door is the connect gate; a persisted serverBase
        // (or a manifest-adopted origin) skips straight past it.
        viewModelScope.launch {
            val stored = runCatching { prefs.serverBase.first() }.getOrNull()
            val configured = app.pulse.core.PulseEndpoints.isConfigured || !stored.isNullOrBlank()
            _state.value = _state.value.copy(
                step = if (configured) OnboardingStep.NAME else OnboardingStep.CONNECT,
                serverUrl = stored ?: app.pulse.core.PulseEndpoints.gatewayHttpUrl,
            )
        }
    }

    // R50-a - connect gate

    fun setServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value.take(200), connectNotice = null)
    }

    /**
     * Normalize a candidate base the way PulseEndpoints.applyBase expects:
     * trimmed, no trailing slash, http(s) scheme defaulted to https when the
     * user left it off (a bare "host:port" is still accepted verbatim there).
     */
    private fun normalizeBase(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    /**
     * Live probe of a CANDIDATE base without touching the global endpoints:
     * GET <base>/api/users must answer 200 with a JSON body carrying the
     * users array; as an R52 fallback GET <base>/api/health answers with the
     * ok/database heartbeat (covers hardened gateways where /api/users is
     * auth-gated). Same dependency-free HttpURLConnection pattern the
     * deployment-manifest fetcher uses (4s timeouts, honest verdict).
     */
    private fun probeBase(base: String): Pair<Boolean, String> = try {
        val users = probeUrl("$base/api/users")
        val usersOk = users.first && users.second.contains("users")
        when {
            usersOk -> true to "Connected. This is a live Pulse server."
            users.first -> false to "That host answered but it is not a Pulse gateway (HTTP ${users.third})."
            else -> {
                val health = probeUrl("$base/api/health")
                if (health.first && health.second.contains("ok")) {
                    true to "Connected. This is a live Pulse server."
                } else if (health.first) {
                    false to "That host answered but it is not a Pulse gateway (HTTP ${health.third})."
                } else {
                    false to "Could not reach that host - check the address and your network."
                }
            }
        }
    } catch (e: java.net.UnknownHostException) {
        false to "Host not found - check the address and your network."
    } catch (e: java.io.IOException) {
        false to "Could not reach that host - plain HTTP may be blocked, try https."
    } catch (e: Exception) {
        false to "Probe failed: ${e.message ?: "unknown error"}"
    }

    /** GET a URL, return (reached, body, httpCode) - shared by both probe paths. */
    private fun probeUrl(url: String): Triple<Boolean, String, Int> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        conn.instanceFollowRedirects = true
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Triple(code in 200..299, body, code)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Connect: probe the candidate base, adopt it for REST + realtime
     * (SessionViewModel.setServerBase persists it AND points the endpoints),
     * then advance to the name step where the demo login waits. The callback
     * receives the NORMALIZED base so the host can persist it.
     */
    fun connect(onConnected: (String) -> Unit) {
        val s = _state.value
        if (s.probing) return
        val base = normalizeBase(s.serverUrl) ?: run {
            _state.value = _state.value.copy(
                connectNotice = "Paste the address of a Pulse gateway (https://...).",
                connectNoticeTone = ConnectNoticeTone.AMBER,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(probing = true, connectNotice = null)
            val (ok, message) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                probeBase(base)
            }
            if (ok) {
                onConnected(base)
                _state.value = _state.value.copy(
                    probing = false,
                    serverConnected = true,
                    step = OnboardingStep.NAME,
                    connectNotice = message,
                    connectNoticeTone = ConnectNoticeTone.EMERALD,
                )
            } else {
                _state.value = _state.value.copy(
                    probing = false,
                    connectNotice = message,
                    connectNoticeTone = ConnectNoticeTone.AMBER,
                )
            }
        }
    }

    /** Explore offline - the honest offline-first path stays available. */
    fun skipConnect() {
        if (_state.value.probing) return
        _state.value = _state.value.copy(step = OnboardingStep.NAME)
    }

    /**
     * R50-a - one-tap demo login: reclaim the seeded demo identity through
     * the SAME POST /api/users/login chain the "log in instead" flow uses
     * (token rotate, vault persist, repo.start) - the shell then opens
     * straight onto the rich demo chats.
     */
    fun demoLogin() {
        val s = _state.value
        if (s.signingIn) return
        viewModelScope.launch {
            _state.value = _state.value.copy(signingIn = true, connectNotice = null)
            repo.login(DEMO_ACCOUNT_NAME).fold(
                onSuccess = { user -> complete(user) },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        signingIn = false,
                        connectNotice = (error as? OnboardingError)?.message
                            ?: "Network error - connect to a live Pulse server first.",
                        connectNoticeTone = ConnectNoticeTone.AMBER,
                    )
                },
            )
        }
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(
            name = value.take(NAME_MAX),
            nameTaken = false,
            notice = null,
        )
    }

    fun setColor(value: String) {
        _state.value = _state.value.copy(color = value)
    }

    fun startHandleStep() {
        val name = _state.value.name.trim()
        if (name.isEmpty() || name.length > NAME_MAX) return
        checkJob?.cancel()
        _state.value = _state.value.copy(
            step = OnboardingStep.HANDLE,
            handle = suggestHandleFromName(name),
            serverTakenMessage = null,
            serverTakenSuggestion = null,
            checkedHandle = null,
            checkAvailable = null,
            checkSuggestion = null,
            checking = false,
            notice = null,
        )
        scheduleCheck()
    }

    fun backToName() {
        checkJob?.cancel()
        _state.value = _state.value.copy(step = OnboardingStep.NAME, checking = false)
    }

    fun setHandle(value: String) {
        _state.value = _state.value.copy(
            handle = sanitizeHandleInput(value),
            serverTakenMessage = null,
            serverTakenSuggestion = null,
        )
        scheduleCheck()
    }

    fun useSuggestion(suggestion: String) {
        setHandle(suggestion)
    }

    /** Start chatting - create the account WITH the picked @handle. */
    fun submit() {
        val s = _state.value
        val handle = s.handle.trim()
        if (!isValidHandle(handle) || s.pending || s.serverTakenMessage != null) return
        createAccount(handle)
    }

    /** Skip for now - create the account without a handle. */
    fun skip() {
        if (_state.value.pending) return
        createAccount(null)
    }

    /**
     * "That's me - log in instead" - the web's reclaim-by-name affordance.
     * Wave 8: this now runs POST /api/users/login { name }, which ROTATES the
     * stored session hash and hands back the fresh token - the repository
     * persists it (Keystore-backed SessionTokenStore) before the success
     * lands. The honest 404 copy ("No identity with that name on this
     * Pulse.") surfaces verbatim.
     */
    fun loginInstead() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty() || s.signingIn) return
        viewModelScope.launch {
            _state.value = _state.value.copy(signingIn = true, notice = null)
            repo.login(name).fold(
                onSuccess = { user -> complete(user) },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        signingIn = false,
                        notice = (error as? OnboardingError)?.message ?: "Network error - try again.",
                    )
                },
            )
        }
    }

    private fun createAccount(username: String?) {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty() || s.pending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(pending = true, notice = null)
            repo.createIdentity(name, s.color, username).fold(
                onSuccess = { user -> complete(user) },
                onFailure = { error ->
                    val oe = error as? OnboardingError
                    when {
                        oe?.isUsernameTaken == true -> _state.value = _state.value.copy(
                            pending = false,
                            serverTakenMessage = oe.message,
                            serverTakenSuggestion = oe.suggestion,
                        )
                        oe?.isNameClash == true -> _state.value = _state.value.copy(
                            pending = false,
                            step = OnboardingStep.NAME,
                            nameTaken = true,
                            serverTakenMessage = null,
                            serverTakenSuggestion = null,
                        )
                        else -> _state.value = _state.value.copy(
                            pending = false,
                            notice = oe?.message ?: "Network error - try again.",
                        )
                    }
                },
            )
        }
    }

    /** Debounced live availability - same 350ms rhythm as the web picker. */
    private fun scheduleCheck() {
        checkJob?.cancel()
        val handle = _state.value.handle
        if (!isValidHandle(handle)) {
            _state.value = _state.value.copy(
                checking = false,
                checkedHandle = null,
                checkAvailable = null,
                checkSuggestion = null,
            )
            return
        }
        checkJob = viewModelScope.launch {
            delay(CHECK_DEBOUNCE_MS)
            if (!isActiveFor(handle)) return@launch
            _state.value = _state.value.copy(checking = true)
            repo.checkHandle(handle).fold(
                onSuccess = { check ->
                    if (isActiveFor(handle)) {
                        _state.value = _state.value.copy(
                            checking = false,
                            checkedHandle = handle,
                            checkAvailable = check.available,
                            checkSuggestion = check.suggestion,
                        )
                    }
                },
                onFailure = {
                    // web parity: a failed probe shows no line, never a false verdict
                    if (isActiveFor(handle)) {
                        _state.value = _state.value.copy(
                            checking = false,
                            checkedHandle = null,
                            checkAvailable = null,
                            checkSuggestion = null,
                        )
                    }
                },
            )
        }
    }

    /** only the result for the handle currently on screen may land */
    private fun isActiveFor(handle: String): Boolean =
        _state.value.handle == handle && _state.value.step == OnboardingStep.HANDLE

    private suspend fun complete(user: User) {
        prefs.setViewer(user.id, user.name, user.color)
        // The encrypted vault is the durable identity - the plaintext prefs
        // keys remain only as the read-only UI mirror (Wave 0 secure session).
        runCatching {
            val current = secureSessionStore.load()
                ?.let { runCatching { PulseJson.decodeFromString(SessionVault.serializer(), it) }.getOrNull() }
                ?: SessionVault()
            secureSessionStore.save(
                PulseJson.encodeToString(
                    SessionVault.serializer(),
                    current.copy(
                        viewerId = user.id,
                        viewerName = user.name,
                        viewerUsername = user.handle.takeIf { it.isNotBlank() },
                        viewerColor = user.color,
                    ),
                ),
            )
        }
        repo.start(user.id)
        PulseFx.fire(PulseFx.BurstKind.CONFETTI, count = 120)
        _state.value = _state.value.copy(pending = false, signingIn = false)
    }
}

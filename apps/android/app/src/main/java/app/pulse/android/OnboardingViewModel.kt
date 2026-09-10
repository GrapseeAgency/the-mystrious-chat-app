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
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// Pulse onboarding — two steps, web parity (onboarding-screen.tsx):
//   1. display name + avatar color (existing reclaim-by-name flow)
//   2. @handle picker — auto-suggested from the name, live
//      availability via GET /api/users/check-username (debounced),
//      skippable. Creates the real account via POST /api/users.
// ─────────────────────────────────────────────────────────────

enum class OnboardingStep { NAME, HANDLE }

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

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
    private val secureSessionStore: SecureSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var checkJob: Job? = null

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

    /** Start chatting — create the account WITH the picked @handle. */
    fun submit() {
        val s = _state.value
        val handle = s.handle.trim()
        if (!isValidHandle(handle) || s.pending || s.serverTakenMessage != null) return
        createAccount(handle)
    }

    /** Skip for now — create the account without a handle. */
    fun skip() {
        if (_state.value.pending) return
        createAccount(null)
    }

    /** "That's me — log in instead" — the web's reclaim-by-name affordance. */
    fun loginInstead() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty() || s.signingIn) return
        viewModelScope.launch {
            _state.value = _state.value.copy(signingIn = true, notice = null)
            repo.lookupUserByName(name).fold(
                onSuccess = { user ->
                    if (user != null) {
                        complete(user)
                    } else {
                        _state.value = _state.value.copy(
                            signingIn = false,
                            notice = "No Pulse account with that name.",
                        )
                    }
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        signingIn = false,
                        notice = (error as? OnboardingError)?.message ?: "Network error — try again.",
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
                            notice = oe?.message ?: "Network error — try again.",
                        )
                    }
                },
            )
        }
    }

    /** Debounced live availability — same 350ms rhythm as the web picker. */
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
        // The encrypted vault is the durable identity — the plaintext prefs
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

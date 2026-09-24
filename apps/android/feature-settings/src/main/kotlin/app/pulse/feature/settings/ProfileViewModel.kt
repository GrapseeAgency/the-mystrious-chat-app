package app.pulse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URL
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profile tab state holder — identity management + appearance prefs. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
) : ViewModel() {

    data class UiState(
        val users: List<User> = emptyList(),
        val loading: Boolean = false,
        val creating: Boolean = false,
        val error: String? = null,
        val newIdentityName: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val viewerName: StateFlow<String?> = prefs.viewerName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fxMode: StateFlow<String> = prefs.fxMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "aurora")

    val darkOverride: StateFlow<String> = prefs.darkOverride
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val reducedMotion: StateFlow<Boolean> = prefs.reducedMotion
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val serverBase: StateFlow<String?> = prefs.serverBase
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live probe of the configured origin — honest connectivity verdict. */
    data class ProbeState(val running: Boolean = false, val ok: Boolean? = null, val detail: String = "")

    private val _probe = MutableStateFlow(ProbeState())
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    // R2-A item 10 — the wallet chip (web profile-tab.tsx:149-156: GET
    // /api/hub/wallet → balance row with loading/error handling).
    data class WalletUi(
        val loading: Boolean = false,
        val coins: Long? = null,
        val error: Boolean = false,
    )

    private val _wallet = MutableStateFlow(WalletUi())
    val wallet: StateFlow<WalletUi> = _wallet.asStateFlow()

    /** Wallet chip refresh — viewer-less renders nothing (web parity). */
    fun loadWallet() {
        if (viewerId.value == null || _wallet.value.loading) return
        _wallet.value = WalletUi(loading = true)
        viewModelScope.launch {
            val result: kotlin.Result<app.pulse.protocol.WalletPageDto> = repo.wallet()
            _wallet.value = result.fold(
                onSuccess = { page -> WalletUi(coins = page.wallet.coins) },
                onFailure = { WalletUi(error = true) },
            )
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.users().fold(
                onSuccess = { _state.value = _state.value.copy(users = it, loading = false) },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
    }

    fun setNewIdentityName(value: String) {
        _state.value = _state.value.copy(newIdentityName = value)
    }

    /** Switch the device viewer (native identity parity with web onboarding). */
    fun chooseIdentity(user: User) {
        viewModelScope.launch {
            // Wave 8 — the previous identity's credential must not ride along.
            runCatching { repo.clearSessionToken() }
            prefs.setViewer(user.id, user.name)
            repo.start(user.id)
            PulseFx.fire(PulseFx.BurstKind.STARS, count = 60)
        }
    }

    fun createIdentity(colorHex: String) {
        val name = _state.value.newIdentityName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null)
            repo.createIdentity(name, colorHex).fold(
                onSuccess = { user ->
                    _state.value = _state.value.copy(creating = false, newIdentityName = "")
                    prefs.setViewer(user.id, user.name)
                    repo.start(user.id)
                    PulseFx.fire(PulseFx.BurstKind.CONFETTI, count = 120)
                },
                onFailure = { _state.value = _state.value.copy(creating = false, error = it.message) },
            )
        }
    }

    fun setFxMode(mode: String) {
        viewModelScope.launch { prefs.setFxMode(mode) }
    }

    fun setDarkOverride(value: String) {
        viewModelScope.launch { prefs.setDarkOverride(value) }
    }

    fun setReducedMotion(value: Boolean) {
        viewModelScope.launch { prefs.setReducedMotion(value) }
    }

    fun setServerBase(value: String?) {
        PulseEndpoints.applyBase(value)
        viewModelScope.launch { prefs.setServerBase(value) }
    }

    /** GET <base>/api/users with a hard 5s budget — ms timing on success. */
    fun probeServer(base: String) {
        val origin = base.trim().trimEnd('/')
        if (!origin.startsWith("http")) {
            _probe.value = ProbeState(ok = false, detail = "enter a full https:// address")
            return
        }
        _probe.value = ProbeState(running = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$origin/api/users").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("User-Agent", "Pulse-Probe/1 (Android)")
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) Result.success(code) else Result.failure(IOException("HTTP $code"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.fold(
                onSuccess = { _probe.value = ProbeState(ok = true, detail = "connected — /api/users answered") },
                onFailure = { _probe.value = ProbeState(ok = false, detail = it.message ?: "unreachable") },
            )
        }
    }

    // R6 — M3: ProfileViewModel.forgetIdentity was REMOVED — it cleared the
    // prefs viewer but NOT the encrypted session vault, so SessionViewModel's
    // cold-start re-seed resurrected the identity on the next launch. The
    // ProfileScreen "Forget this viewer" row now routes to the app-level
    // SessionViewModel.forgetViewer() (prefs + vault + token) via the
    // onForgetViewer callback MainActivity provides.
}

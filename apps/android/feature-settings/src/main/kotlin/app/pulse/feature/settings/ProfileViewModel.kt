package app.pulse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun forgetIdentity() {
        viewModelScope.launch {
            prefs.setViewer(null, null)
        }
    }
}

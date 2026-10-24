package app.pulse.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/** Contacts tab state holder - directory from GET /api/users + live presence. */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    sealed interface DmResult {
        data class Ready(val conversationId: String) : DmResult
        data class Failed(val message: String) : DmResult
    }

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    val presence: StateFlow<Set<String>> = repo.observePresence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _dmResult = MutableStateFlow<DmResult?>(null)
    val dmResult: StateFlow<DmResult?> = _dmResult.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repo.events().collect { _users.value = _users.value } // presence re-renders via presence flow
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.users().fold(
                onSuccess = { users ->
                    _users.value = users
                    _state.value = _state.value.copy(loading = false)
                },
                onFailure = { failure ->
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                },
            )
        }
    }

    fun query(text: String) {
        viewModelScope.launch {
            repo.users(text).fold(
                onSuccess = { _users.value = it },
                onFailure = { },
            )
        }
    }

    fun openDm(user: User) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            repo.createDm(user.id).fold(
                onSuccess = { conversation ->
                    _state.value = _state.value.copy(loading = false)
                    _dmResult.value = DmResult.Ready(conversation.id)
                },
                onFailure = { failure ->
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                    _dmResult.value = DmResult.Failed(failure.message ?: "Could not open chat")
                },
            )
        }
    }

    fun consumeDmResult() {
        _dmResult.value = null
    }

    private val pendingSafety = mutableMapOf<String, java.util.UUID>()
    private var safetyEpoch: String? = null

    private fun safetyAction(user: User, action: suspend (PulseRepository) -> Result<Unit>) {
        val epoch = repo.attachmentSessionKey() ?: return
        if (safetyEpoch != epoch) { safetyEpoch = epoch; pendingSafety.clear() }
        if (pendingSafety.containsKey(user.id)) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID(); pendingSafety[user.id] = ticket
        viewModelScope.launch {
            try {
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                action(owned).getOrThrow()
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() == epoch) _state.value = _state.value.copy(error = null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) _state.value = _state.value.copy(error = error.message ?: "Action was not confirmed. Please retry.")
            } finally { if (pendingSafety[user.id] == ticket) pendingSafety.remove(user.id) }
        }
    }

    fun block(user: User) = safetyAction(user) { it.block(user.id) }
    fun report(user: User, reason: String, details: String?) = safetyAction(user) { it.report(user.id, reason, details) }
}

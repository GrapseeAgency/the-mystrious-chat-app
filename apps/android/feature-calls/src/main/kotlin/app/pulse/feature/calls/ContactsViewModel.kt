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
        val dmOpening: Boolean = false,
        val error: String? = null,
        val safetyError: String? = null,
        val safetyPending: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    val presence: StateFlow<Set<String>> = repo.observePresence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _dmResult = MutableStateFlow<DmResult?>(null)
    val dmResult: StateFlow<DmResult?> = _dmResult.asStateFlow()

    private var ownerEpoch: String? = null
    private var readTicket = java.util.UUID.randomUUID()
    private var dmTicket = java.util.UUID.randomUUID()
    private var readTask: kotlinx.coroutines.Job? = null
    private var dmTask: kotlinx.coroutines.Job? = null
    private var directoryQuery = ""
    private val pendingSafety = mutableMapOf<String, java.util.UUID>()

    private fun currentOwner(): String? {
        val epoch = repo.attachmentSessionKey()
        if (ownerEpoch != epoch) {
            ownerEpoch = epoch
            readTicket = java.util.UUID.randomUUID(); dmTicket = java.util.UUID.randomUUID()
            readTask?.cancel(); dmTask?.cancel()
            pendingSafety.clear(); _users.value = emptyList(); _dmResult.value = null
            _state.value = UiState()
        }
        return epoch
    }

    init { refresh() }

    fun refresh() = loadDirectory(directoryQuery)
    fun query(text: String) = loadDirectory(text)

    private fun loadDirectory(text: String) {
        val epoch = currentOwner() ?: return
        val owned = repo.boundToCurrentAccount()
        directoryQuery = text
        val ticket = java.util.UUID.randomUUID(); readTicket = ticket
        readTask?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        readTask = viewModelScope.launch {
            fun owns() = repo.attachmentSessionKey() == epoch && readTicket == ticket
            try {
                coroutineContext.ensureActive(); if (!owns()) return@launch
                val users = owned.users(text).getOrThrow()
                coroutineContext.ensureActive(); if (!owns()) return@launch
                _users.value = users
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owns()) _state.value = _state.value.copy(error = error.message ?: "Could not refresh contacts")
            } finally { if (owns()) _state.value = _state.value.copy(loading = false) }
        }
    }

    fun openDm(user: User) {
        val epoch = currentOwner() ?: return
        if (_state.value.dmOpening) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID(); dmTicket = ticket
        _state.value = _state.value.copy(dmOpening = true, error = null)
        _dmResult.value = null
        dmTask = viewModelScope.launch {
            fun owns() = repo.attachmentSessionKey() == epoch && dmTicket == ticket
            try {
                coroutineContext.ensureActive(); if (!owns()) return@launch
                val conversation = owned.createDm(user.id).getOrThrow()
                coroutineContext.ensureActive(); if (!owns()) return@launch
                _dmResult.value = DmResult.Ready(conversation.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owns()) {
                    val message = error.message ?: "Could not open chat"
                    _state.value = _state.value.copy(error = message)
                    _dmResult.value = DmResult.Failed(message)
                }
            } finally { if (owns()) _state.value = _state.value.copy(dmOpening = false) }
        }
    }

    fun consumeDmResult() {
        _dmResult.value = null
    }

    private fun safetyAction(user: User, onConfirmed: () -> Unit = {}, action: suspend (PulseRepository) -> Result<Unit>) {
        val epoch = currentOwner() ?: return
        if (pendingSafety.containsKey(user.id)) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID(); pendingSafety[user.id] = ticket
        _state.value = _state.value.copy(safetyPending = pendingSafety.keys.toSet(), safetyError = null)
        viewModelScope.launch {
            try {
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                action(owned).getOrThrow()
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() == epoch && pendingSafety[user.id] == ticket) {
                    _state.value = _state.value.copy(safetyError = null)
                    onConfirmed()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) _state.value = _state.value.copy(safetyError = error.message ?: "Action was not confirmed. Please retry.")
            } finally {
                if (pendingSafety[user.id] == ticket) {
                    pendingSafety.remove(user.id)
                    if (repo.attachmentSessionKey() == epoch) _state.value = _state.value.copy(safetyPending = pendingSafety.keys.toSet())
                }
            }
        }
    }

    fun block(user: User) = safetyAction(user) { it.block(user.id) }
    fun safetyOwnerKey(): String? = repo.attachmentSessionKey()

    fun report(user: User, reason: String, details: String?, formOwner: String?, onConfirmed: () -> Unit) {
        val epoch = currentOwner()
        if (formOwner == null || formOwner != epoch) {
            _state.value = _state.value.copy(safetyError = "Account changed. Reopen the report before submitting.")
            return
        }
        safetyAction(user, onConfirmed) { it.report(user.id, reason, details) }
    }
}

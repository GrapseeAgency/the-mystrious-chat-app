package app.pulse.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chats tab state holder (N2 wiring — data only, screen untouched).
 * Consumed by the UI wave: bind [chats] to the list, [state] to loaders.
 */
class ChatsViewModel(private val repo: PulseRepository) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val chats: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Call once after login with the viewer id (parity with web deep-link login). */
    fun start(userId: String) {
        repo.start(userId)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = repo.refreshConversations()
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false) },
                onFailure = { _state.value.copy(loading = false, error = it.message) },
            )
        }
    }
}

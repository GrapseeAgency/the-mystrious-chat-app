package app.pulse.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Chats tab state holder — live inbox from Room + relay events. */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class TypingState(val userName: String, val until: Long)

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, TypingState>>(emptyMap())
    val typing: StateFlow<Map<String, TypingState>> = _typing.asStateFlow()

    val chats: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val presence: StateFlow<Set<String>> = repo.observePresence()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var typingExpiryJob: Job? = null

    init {
        viewModelScope.launch {
            repo.events().collect { event ->
                when (event) {
                    is PulseEvent.Typing -> {
                        if (event.conversationId.isNotEmpty() && event.userId != repo.viewerId) {
                            if (event.isTyping) {
                                _typing.value = _typing.value + (
                                    event.conversationId to TypingState(
                                        event.userName.ifBlank { "Someone" },
                                        System.currentTimeMillis() + 4_000,
                                    )
                                    )
                            } else {
                                _typing.value = _typing.value - event.conversationId
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        typingExpiryJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val stale = _typing.value.filterValues { it.until < now }.keys
                if (stale.isNotEmpty()) _typing.value = _typing.value - stale.toSet()
            }
        }
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

    fun togglePin(conversationId: String, pinned: Boolean) {
        viewModelScope.launch { repo.togglePin(conversationId, pinned).onSuccess { refresh() } }
    }

    fun toggleMute(conversationId: String, muted: Boolean) {
        viewModelScope.launch { repo.setMuted(conversationId, muted).onSuccess { refresh() } }
    }

    fun archive(conversationId: String, archived: Boolean) {
        viewModelScope.launch { repo.archive(conversationId, archived).onSuccess { refresh() } }
    }

    fun markRead(conversationId: String) {
        viewModelScope.launch { repo.markRead(conversationId) }
    }
}

/** Chat room state holder — messages, live typing/presence, sends + reactions. */
@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PulseRepository,
    private val sendUseCase: SendMessageUseCase,
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val replyTo: Message? = null,
        val partnerTypingName: String? = null,
        val partnerLastReadAt: Long? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val messages: StateFlow<List<Message>> = repo.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            repo.refreshMessages(conversationId).fold(
                onSuccess = { _state.value = _state.value.copy(loading = false) },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
            repo.markRead(conversationId)
        }
        viewModelScope.launch {
            repo.events().collect { event ->
                when (event) {
                    is PulseEvent.Typing -> if (event.conversationId == conversationId && event.userId != repo.viewerId) {
                        _state.value = if (event.isTyping) {
                            _state.value.copy(partnerTypingName = event.userName.ifBlank { "Someone" })
                        } else {
                            _state.value.copy(partnerTypingName = null)
                        }
                    }
                    is PulseEvent.MessageRead -> if (event.conversationId == conversationId) {
                        val at = app.pulse.core.time.PulseTime.parse(event.at)?.toInstant()?.toEpochMilli()
                        if (at != null) {
                            _state.value = _state.value.copy(
                                partnerLastReadAt = maxOf(_state.value.partnerLastReadAt ?: 0, at),
                            )
                        }
                    }
                    is PulseEvent.MessageReceived -> if (event.conversationId == conversationId) {
                        repo.markRead(conversationId)
                    }
                    else -> Unit
                }
            }
        }
    }

    private var typingJob: Job? = null

    fun onDraftChanged(text: String) {
        // Debounced typing signal — emit start now, stop after 1.2s idle (web parity).
        val userName = viewerName()
        if (typingJob?.isActive != true) {
            viewModelScope.launch { repo.setTyping(conversationId, userName, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(1_200)
            repo.setTyping(conversationId, userName, false)
            typingJob = null
        }
    }

    private fun viewerName(): String {
        val conv = conversation.value ?: return repo.viewerId ?: ""
        val idx = conv.memberIds.indexOf(repo.viewerId)
        return conv.memberNames.getOrNull(idx) ?: repo.viewerId ?: ""
    }

    fun setReplyTo(message: Message?) {
        _state.value = _state.value.copy(replyTo = message)
    }

    fun send(body: String) {
        val replyId = _state.value.replyTo?.id
        _state.value = _state.value.copy(replyTo = null)
        viewModelScope.launch {
            sendUseCase(conversationId, body, replyId)
                .onSuccess {
                    PulseFx.fire(PulseFx.BurstKind.BURST, count = 26)
                    repo.setTyping(conversationId, viewerName(), false)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failure.message)
                }
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { repo.react(messageId, emoji) }
    }

    fun retry() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.refreshMessages(conversationId).fold(
                onSuccess = { _state.value = _state.value.copy(loading = false) },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) },
            )
        }
    }
}

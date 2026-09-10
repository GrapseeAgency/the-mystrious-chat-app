package app.pulse.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.StoryCell
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot user-facing notice — the snackbar/toast copy channel (web toast parity). */
data class HomeNotice(val text: String, val isError: Boolean = false)

/**
 * Chats tab state holder — live inbox from Room + relay events, plus the
 * N10 home-page chrome state: filter, stories rail, folders rail, mention
 * count, server search and the 6s list poll (web refetchInterval parity).
 */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
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

    private val _notice = MutableStateFlow<HomeNotice?>(null)
    val notice: StateFlow<HomeNotice?> = _notice.asStateFlow()

    /** Persisted Telegram-style list filter — "all" | "unread" | "groups". */
    val listFilter: StateFlow<String> = prefs.chatsListFilter
        .stateIn(viewModelScope, SharingStarted.Eagerly, "all")

    /** 24h status rail — empty (honest) whenever the stories endpoint can't answer. */
    private val _stories = MutableStateFlow<List<StoryCell>>(emptyList())
    val stories: StateFlow<List<StoryCell>> = _stories.asStateFlow()

    /** Signal-style folder rail — empty (honest) when the endpoint can't answer. */
    private val _folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    val folders: StateFlow<List<FolderSummary>> = _folders.asStateFlow()

    /** Live @mention count for the entry pill (0 when offline). */
    private val _mentionCount = MutableStateFlow(0)
    val mentionCount: StateFlow<Int> = _mentionCount.asStateFlow()

    /** Server-side message search results (debounced upstream in the screen). */
    private val _searchHits = MutableStateFlow<List<MessageHit>>(emptyList())
    val searchHits: StateFlow<List<MessageHit>> = _searchHits.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val chats: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val presence: StateFlow<Set<String>> = repo.observePresence()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var typingExpiryJob: Job? = null
    private var pollJob: Job? = null
    private var chromeLoadedFor: String? = null

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

    /** Visible refresh — PullToRefresh + first load; flips the loading flag. */
    fun refresh() {
        if (repo.viewerId == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = repo.refreshConversations()
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false) },
                onFailure = { _state.value.copy(loading = false, error = it.message) },
            )
            if (result.isSuccess) loadChrome()
        }
    }

    /** Background 6s poll — no loading flips, exactly like web background refetch. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(6_000)
                if (repo.viewerId != null) {
                    repo.refreshConversations()
                    loadChrome(quiet = true)
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Stories rail + folders + mention count — honest empty on transport failure. */
    fun loadChrome(quiet: Boolean = false) {
        val viewer = repo.viewerId ?: return
        if (!quiet && chromeLoadedFor == viewer) return
        chromeLoadedFor = viewer
        viewModelScope.launch {
            _stories.value = repo.stories().getOrDefault(emptyList())
            _folders.value = repo.folders().getOrDefault(emptyList())
            _mentionCount.value = repo.mentions().getOrDefault(emptyList()).size
        }
    }

    fun setListFilter(value: String) {
        viewModelScope.launch { prefs.setChatsListFilter(value) }
    }

    /** Debounced server message search — ≥2 chars, 250ms (web rhythm). */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchHits.value = emptyList()
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            delay(250)
            _searchHits.value = repo.searchMessages(trimmed).getOrDefault(emptyList())
            _searching.value = false
        }
    }

    private var searchJob: Job? = null

    fun consumeNotice() {
        _notice.value = null
    }

    private fun notify(text: String, isError: Boolean = false) {
        _notice.value = HomeNotice(text, isError)
    }

    // ── row actions (web endpoint parity — see HOMEPAGE-SPEC §8/§14) ──

    fun togglePin(conversationId: String, pinned: Boolean) {
        viewModelScope.launch {
            repo.togglePin(conversationId, pinned)
                .onSuccess { notify(if (pinned) "Pinned to top" else "Unpinned") }
                .onFailure { notify("Could not update the pin", isError = true) }
        }
    }

    fun toggleMute(conversationId: String, muted: Boolean) {
        setMutedUntil(conversationId, if (muted) "8h" else null)
    }

    fun setMutedUntil(conversationId: String, until: String?) {
        viewModelScope.launch {
            repo.setMutedUntil(conversationId, until)
                .onSuccess {
                    notify(
                        when (until) {
                            null -> "Notifications unmuted"
                            "always" -> "Muted — always"
                            else -> {
                                val offsetMs = if (until == "1w") 7L * 24 * 60 * 60 * 1000 else 8L * 60 * 60 * 1000
                                val stamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs)
                                    .atZone(ZoneId.systemDefault())
                                if (stamp.toLocalDate() == java.time.LocalDate.now()) {
                                    "Muted until " + stamp.format(DateTimeFormatter.ofPattern("HH:mm"))
                                } else {
                                    "Muted until " + stamp.format(DateTimeFormatter.ofPattern("d MMM"))
                                }
                            }
                        },
                    )
                }
                .onFailure { notify("Could not update the mute", isError = true) }
        }
    }

    fun markUnread(conversationId: String, on: Boolean) {
        viewModelScope.launch {
            repo.markUnread(conversationId, on)
                .onSuccess { notify(if (on) "Marked as unread" else "Marked as read") }
                .onFailure { notify("Could not update the unread flag", isError = true) }
        }
    }

    fun archive(conversationId: String, archived: Boolean) {
        viewModelScope.launch {
            repo.archive(conversationId, archived)
                .onSuccess { notify(if (archived) "Chat archived" else "Chat unarchived") }
                .onFailure { notify("Could not update the archive", isError = true) }
        }
    }

    fun markRead(conversationId: String) {
        viewModelScope.launch { repo.markRead(conversationId) }
    }

    // ── batch multi-select (web copies) ─────────────────────────

    fun batchArchive(ids: List<String>, archived: Boolean) {
        viewModelScope.launch {
            var done = 0
            for (id in ids) {
                if (repo.archive(id, archived).isSuccess) done += 1
            }
            notify("Archived $done ${if (done == 1) "chat" else "chats"}")
        }
    }

    fun batchMute8h(ids: List<String>) {
        viewModelScope.launch {
            var done = 0
            for (id in ids) {
                if (repo.setMutedUntil(id, "8h").isSuccess) done += 1
            }
            notify("Muted $done ${if (done == 1) "chat" else "chats"} for 8 hours")
        }
    }

    /** Returns the ids that FAILED so the screen keeps the mode on partial failure. */
    fun batchMarkRead(ids: List<String>, onDone: (failed: List<String>) -> Unit) {
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            for (id in ids) {
                if (repo.markRead(id).isFailure) failed += id
            }
            val read = ids.size - failed.size
            if (read > 0) notify("$read ${if (read == 1) "chat" else "chats"} marked as read")
            if (failed.isNotEmpty()) {
                notify("${failed.size} ${if (failed.size == 1) "chat" else "chats"} could not be marked read — try again", isError = true)
            }
            onDone(failed)
        }
    }

    // ── Note to Self ────────────────────────────────────────────

    fun createSelfChat(onOpened: (String) -> Unit) {
        viewModelScope.launch {
            repo.createSelfChat()
                .onSuccess { onOpened(it.id) }
                .onFailure { notify("Could not open Note to Self", isError = true) }
        }
    }

    // ── export / clear (long-press sheet) ───────────────────────

    fun exportChat(conversationId: String) {
        viewModelScope.launch {
            repo.exportChat(conversationId)
                .onSuccess { notify("Chat exported — Saved $it") }
                .onFailure { notify("Could not export this chat", isError = true) }
        }
    }

    fun clearChat(conversationId: String) {
        viewModelScope.launch {
            repo.clearMyMessages(conversationId)
                .onSuccess { cleared ->
                    if (cleared == 0) {
                        notify("Nothing to clear — none of your messages are left in this chat.")
                    } else {
                        notify("Cleared $cleared ${if (cleared == 1) "message" else "messages"}")
                    }
                }
                .onFailure { notify("Could not clear this chat", isError = true) }
        }
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

    /** Composer restore seed — local draft table first, server myDraft fallback. */
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()

    val messages: StateFlow<List<Message>> = repo.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            // Wave 0 draft restore: the local draft table wins; when it is
            // empty fall back to the server-mirrored myDraft on the summary.
            val local = runCatching { repo.observeDraft(conversationId).firstOrNull() }.getOrNull()
            if (!local.isNullOrBlank()) {
                _initialDraft.value = local
            } else {
                val server = runCatching {
                    repo.observeConversations()
                        .map { list -> list.firstOrNull { it.id == conversationId }?.myDraft }
                        .firstOrNull { !it.isNullOrBlank() }
                }.getOrNull()
                if (!server.isNullOrBlank()) _initialDraft.value = server
            }
        }
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
    private var draftSaveJob: Job? = null

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
        // Wave 0 draft persistence — 600ms debounce (web pulse-drafts parity).
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(600)
            runCatching { repo.saveDraft(conversationId, text) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
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
                .onSuccess { message ->
                    if (message.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX)) {
                        // Network-class failure → queued in the outbox. The
                        // composer text has left for the queue — clear the
                        // draft and let the pending bubble + banner tell it.
                        runCatching { repo.clearDraft(conversationId) }
                    } else {
                        PulseFx.fire(PulseFx.BurstKind.BURST, count = 26)
                        repo.setTyping(conversationId, viewerName(), false)
                        runCatching { repo.clearDraft(conversationId) }
                    }
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

package app.pulse.feature.chat

import kotlinx.coroutines.flow.collect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.StoryCell
import app.pulse.domain.model.StoryGroup
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One-shot user-facing notice - the snackbar/toast copy channel (web toast parity). */
data class HomeNotice(val text: String, val isError: Boolean = false)

/**
 * Chats tab state holder - live inbox from Room + relay events, plus the
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

    /** Persisted Telegram-style list filter - "all" | "unread" | "groups". */
    val listFilter: StateFlow<String> = prefs.chatsListFilter
        .stateIn(viewModelScope, SharingStarted.Eagerly, "all")

    /** 24h status rail - empty (honest) whenever the stories endpoint can't answer. */
    private val _stories = MutableStateFlow<List<StoryCell>>(emptyList())
    val stories: StateFlow<List<StoryCell>> = _stories.asStateFlow()

    /** Signal-style folder rail - empty (honest) when the endpoint can't answer. */
    private val _folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    val folders: StateFlow<List<FolderSummary>> = _folders.asStateFlow()

    /** Live @mention count for the entry pill (0 when offline). */
    private val _mentionCount = MutableStateFlow(0)
    val mentionCount: StateFlow<Int> = _mentionCount.asStateFlow()

    /** Server-side message search results (debounced upstream in the screen). */
    private val _searchHits = MutableStateFlow<List<MessageHit>>(emptyList())
    val searchHits: StateFlow<List<MessageHit>> = _searchHits.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    private var searchGeneration = 0L
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** R2-C item 7 - spotlight recent searches (last 5, web pulse.spotlight.recents.v1). */
    val spotlightRecents: StateFlow<List<String>> = prefs.spotlightRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Push on result-open (web pushRecent-on-select parity). */
    fun pushSpotlightRecent(query: String) {
        viewModelScope.launch { prefs.pushSpotlightRecent(query) }
    }

    fun clearSpotlightRecents() {
        viewModelScope.launch { prefs.clearSpotlightRecents() }
    }

    // R14 gap 14 - spotlight Actions block (web spotlight.tsx:237-262)

    /** Check-in flight flag - the row label flips to "Checking in…" while pending. */
    data class CheckinUi(val pending: Boolean = false)

    private val _checkin = MutableStateFlow(CheckinUi())
    val checkin: StateFlow<CheckinUi> = _checkin.asStateFlow()

    /**
     * The spotlight "Check in to Hub" action - the REAL POST /api/hub/wallet/checkin
     * (repo.checkinWallet), 409 "Already checked in today." surfaces verbatim
     * through the same notice channel the surface renders.
     */
    fun checkinToHub() {
        if (_checkin.value.pending) return
        if (repo.viewerId == null) {
            notify("Pick an identity before checking in.", isError = true)
            return
        }
        _checkin.value = CheckinUi(pending = true)
        viewModelScope.launch {
            repo.checkinWallet().fold(
                onSuccess = { result ->
                    notify("Checked in - +${result.reward} PC · ${result.streak}-day streak")
                },
                onFailure = { notify(it.message ?: "Check-in failed", isError = true) },
            )
            _checkin.value = CheckinUi(pending = false)
        }
    }

    val chats: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local DraftDao drafts keyed by conversation - the "local wins" merge over myDraft. */
    val drafts: StateFlow<Map<String, String>> = repo.observeDrafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    /** Visible refresh - PullToRefresh + first load; flips the loading flag. */
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

    /** Background 6s poll - no loading flips, exactly like web background refetch. */
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

    /**
     * Stories rail + folders + mention count - honest empty on transport failure.
     *
     * Wave 4 polling decision (documented): the RAIL feed stays owned by this
     * VM and is refetched by the EXISTING 6s poll loop below (startPolling →
     * loadChrome(quiet = true)) - stricter than the web's 60s refetchInterval,
     * zero new machinery. The full-screen viewer/composer instead boot their
     * own StoriesViewModel (nav-entry scoped) with a fresh fetch + 60s poll on
     * entry; optimistic seen/delete/publish reconcile onto this rail within
     * one poll beat.
     */
    fun loadChrome(quiet: Boolean = false) {
        val viewer = repo.viewerId ?: return
        if (!quiet && chromeLoadedFor == viewer) return
        chromeLoadedFor = viewer
        viewModelScope.launch {
            // repo.stories() returns full StoryGroups (D2 expiry-filtered);
            // the rail only needs one StoryCell per author (viewer machine
            // semantics: allSeen drives the animated ring).
            _stories.value = repo.stories().getOrDefault(emptyList()).mapNotNull { g ->
                val user = g.user
                if (user == null) {
                    null
                } else {
                    StoryCell(
                        userId = user.id,
                        name = user.name,
                        color = user.color,
                        mine = g.mine,
                        unseen = !g.allSeen,
                    )
                }
            }
            _folders.value = repo.folders().getOrDefault(emptyList())
            _mentionCount.value = repo.mentions().getOrDefault(emptyList()).size
        }
    }

    fun setListFilter(value: String) {
        viewModelScope.launch { prefs.setChatsListFilter(value) }
    }

    /** Debounced server message search - ≥2 chars, 250ms (web rhythm). */
    fun search(query: String) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val epoch = repo.attachmentSessionKey()
        val account = repo.boundToCurrentAccount()
        _searchError.value = null
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchHits.value = emptyList()
            _searching.value = false
            return
        }
        _searchHits.value = emptyList()
        _searching.value = true
        searchJob = viewModelScope.launch {
            delay(250)
            account.observeMessageSearch(trimmed).collect { result ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != searchGeneration || repo.attachmentSessionKey() != epoch) return@collect
                _searchError.value = result.exceptionOrNull()?.message
                _searchHits.value = result.getOrDefault(emptyList())
                _searching.value = false
            }
        }
    }

    private var searchJob: Job? = null

    fun consumeNotice() {
        _notice.value = null
    }

    private fun notify(text: String, isError: Boolean = false) {
        _notice.value = HomeNotice(text, isError)
    }

    // Synchronously claim all target rooms and capture one repository before
    // launching: a queued/batch action must never retarget a later account.
    private var actionOwner: String? = null
    private val roomActions = mutableMapOf<String, java.util.UUID>()
    private fun performConversationActions(
        ids: List<String>, success: (Int) -> String, failure: String,
        onDone: (List<String>) -> Unit = {},
        request: suspend (PulseRepository, String) -> Result<Unit>,
    ) {
        val epoch = repo.attachmentSessionKey() ?: return
        val owned = repo.boundToCurrentAccount()
        val targets = ids.distinct().filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        if (actionOwner != epoch) { actionOwner = epoch; roomActions.clear() }
        if (targets.any { it in roomActions }) { notify("This chat is still updating. Please wait."); return }
        val ticket = java.util.UUID.randomUUID()
        targets.forEach { roomActions[it] = ticket }
        viewModelScope.launch {
            try {
                val failed = mutableListOf<String>()
                for (id in targets) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (repo.attachmentSessionKey() != epoch) return@launch
                    val result = try { request(owned, id) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (repo.attachmentSessionKey() != epoch) return@launch
                    if (result.isFailure) failed += id
                }
                val done = targets.size - failed.size
                notify(when {
                    failed.isEmpty() -> success(done)
                    done == 0 -> failure
                    else -> "${success(done)}. ${failed.size} changes could not be confirmed; retry them."
                }, isError = failed.isNotEmpty())
                onDone(failed)
            } finally {
                if (actionOwner == epoch) targets.forEach { if (roomActions[it] == ticket) roomActions.remove(it) }
            }
        }
    }

    fun togglePin(conversationId: String, pinned: Boolean) = performConversationActions(
        listOf(conversationId), success = { if (pinned) "Pinned to top" else "Unpinned" }, failure = "Could not update the pin",
    ) { owned, id -> owned.togglePin(id, pinned) }

    fun toggleMute(conversationId: String, muted: Boolean) = setMutedUntil(conversationId, if (muted) "8h" else null)

    fun setMutedUntil(conversationId: String, until: String?) = performConversationActions(
        listOf(conversationId), success = { if (until == null) "Notifications unmuted" else "Notifications muted" },
        failure = "Could not update the mute",
    ) { owned, id -> owned.setMutedUntil(id, until) }

    fun markUnread(conversationId: String, on: Boolean) = performConversationActions(
        listOf(conversationId), success = { if (on) "Marked as unread" else "Marked as read" }, failure = "Could not update the unread flag",
    ) { owned, id -> owned.markUnread(id, on) }

    fun archive(conversationId: String, archived: Boolean) = performConversationActions(
        listOf(conversationId), success = { if (archived) "Chat archived" else "Chat unarchived" }, failure = "Could not update the archive",
    ) { owned, id -> owned.archive(id, archived) }

    fun markRead(conversationId: String) = performConversationActions(
        listOf(conversationId), success = { "Marked as read" }, failure = "Could not mark this chat read",
    ) { owned, id -> owned.markRead(id) }

    fun batchArchive(ids: List<String>, archived: Boolean, onDone: (List<String>) -> Unit = {}) = performConversationActions(
        ids, success = { "${if (archived) "Archived" else "Unarchived"} $it ${if (it == 1) "chat" else "chats"}" },
        failure = "Could not confirm the selected archive changes", onDone = onDone,
    ) { owned, id -> owned.archive(id, archived) }

    fun batchMute8h(ids: List<String>, onDone: (List<String>) -> Unit = {}) = performConversationActions(
        ids, success = { "Muted $it ${if (it == 1) "chat" else "chats"} for 8 hours" },
        failure = "Could not confirm the selected mutes", onDone = onDone,
    ) { owned, id -> owned.setMutedUntil(id, "8h") }

    /** Failed IDs remain selected; success is not a reason to remove failures. */
    fun batchMarkRead(ids: List<String>, onDone: (List<String>) -> Unit) = performConversationActions(
        ids, success = { "$it ${if (it == 1) "chat" else "chats"} marked as read" },
        failure = "Could not confirm the selected read changes", onDone = onDone,
    ) { owned, id -> owned.markRead(id) }

    // Note to Self

    private var selfOpeningOwner: String? = null
    private var selfOpeningTicket: java.util.UUID? = null
    private var selfOpeningTask: Job? = null

    fun cancelSelfOpening() {
        selfOpeningTicket = null
        selfOpeningTask?.cancel(); selfOpeningTask = null
    }

    fun createSelfChat(onOpened: (String) -> Unit) {
        val epoch = repo.attachmentSessionKey() ?: return
        val owner = repo.viewerId ?: return
        if (selfOpeningOwner != epoch) { cancelSelfOpening(); selfOpeningOwner = epoch }
        if (selfOpeningTicket != null) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID(); selfOpeningTicket = ticket
        selfOpeningTask = viewModelScope.launch {
            fun owns() = repo.attachmentSessionKey() == epoch && selfOpeningTicket == ticket
            try {
                coroutineContext.ensureActive(); if (!owns()) return@launch
                val cached = owned.observeConversations().firstOrNull().orEmpty()
                coroutineContext.ensureActive(); if (!owns()) return@launch
                val notebook = app.pulse.domain.sync.resolvePersonalConversation(owner, owner, cached,
                    owned::createSelfChat, owned::createDm)
                coroutineContext.ensureActive(); if (!owns()) return@launch
                onOpened(notebook.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owns()) notify("Could not open Note to Self. Reconnect and try again.", isError = true)
            } finally {
                if (selfOpeningTicket == ticket) { selfOpeningTicket = null; selfOpeningTask = null }
            }
        }
    }

    // export / clear (long-press sheet)

    fun exportChat(conversationId: String) {
        viewModelScope.launch {
            repo.exportChat(conversationId)
                .onSuccess { notify("Chat exported - Saved $it") }
                .onFailure { notify(it.message ?: "Could not export this chat", isError = true) }
        }
    }

    fun clearChat(conversationId: String) {
        viewModelScope.launch {
            repo.clearMyMessages(conversationId)
                .onSuccess { cleared ->
                    if (cleared == 0) {
                        notify("Nothing to clear - none of your messages are left in this chat.")
                    } else {
                        notify("Cleared $cleared ${if (cleared == 1) "message" else "messages"}")
                    }
                }
                .onFailure { notify(it.message ?: "Could not clear this chat", isError = true) }
        }
    }
}


/** Remove only confirmed targets, retaining failures and newly selected rooms. */
internal fun remainingBatchSelection(current: Set<String>, attempted: Set<String>, failed: List<String>): Set<String> =
    current - (attempted - failed.toSet())

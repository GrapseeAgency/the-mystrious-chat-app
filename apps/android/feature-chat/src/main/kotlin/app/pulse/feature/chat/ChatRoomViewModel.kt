package app.pulse.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.media.PulseMedia
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.Message
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.model.ScheduledItem
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.KanbanPageDto
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.RemindersPageDto
import app.pulse.protocol.WhiteboardPageDto
import app.pulse.protocol.WhiteboardStrokePostDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.TournamentSummaryDto
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.pulse.domain.model.SafetyState
import app.pulse.domain.model.Topic
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot room-level snackbar/toast notice (web toast parity). */
data class RoomNotice(val text: String, val isError: Boolean = false)

/** A media attachment staged above the composer (upload lifecycle lives here). */
data class StagedMedia(
    val kind: Kind,
    val localUri: String,
    val fileName: String?,
    val fileSize: Long?,
    val mime: String? = null,
    val uploading: Boolean = false,
    val uploadedPath: String? = null,
    val error: String? = null,
    /** Wave 2 view-once (IMAGE kind only — wire requires imagePath when true). */
    val viewOnce: Boolean = false,
) {
    enum class Kind { IMAGE, FILE }
}

/** A downloaded file the UI must hand to the system (open or share). */
data class FileReady(val path: String, val mime: String, val share: Boolean)

/**
 * Chat room state holder — messages, live typing/presence, sends + reactions
 * (Wave 0 core) plus the Wave 1 messaging surface: pagination, ticks, edit/
 * delete/pin/save/forward/info, room search + jump, staged media sends,
 * offline honesty and the OutboxDropped notice channel.
 */
@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: PulseRepository,
    private val sendUseCase: SendMessageUseCase,
    /** The ONE active voice player — room + thread share the singleton. */
    val voicePlayer: VoicePlayer,
) : ViewModel() {

    /** Wave 8 — server-backed prefs for bubble corners / density / wallpaper. */
    val prefs: StateFlow<WirePulsePrefs> = repo.pulsePrefs
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            app.pulse.protocol.PulseWave8Logic.DEFAULTS,
        )

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    /** Global-search / notification jump — the room scrolls + flashes on arrival. */
    private val initialJumpMessageId: String? = savedStateHandle.get<String>("jump")

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val replyTo: Message? = null,
        val partnerTypingName: String? = null,
        val partnerLastReadAt: Long? = null,
        // ── Wave 1 ─────────────────────────────────────────────
        val editing: Message? = null,
        val hasMore: Boolean = false,
        val loadingOlder: Boolean = false,
        val pins: List<Message> = emptyList(),
        val connected: Boolean? = null,
        val notice: RoomNotice? = null,
        val flashMessageId: String? = null,
        val searchOpen: Boolean = false,
        val searchResults: List<Message> = emptyList(),
        val searching: Boolean = false,
        val staged: StagedMedia? = null,
        val downloadingFileId: String? = null,
        val openedFile: FileReady? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Composer restore seed — local draft table first, server myDraft fallback. */
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()

    // ── Wave 2 topics (spec §1 row 9): null = General = WHOLE room ──────
    private val _activeTopicId = MutableStateFlow<String?>(null)
    val activeTopicId: StateFlow<String?> = _activeTopicId.asStateFlow()

    /** Live topic chips (General is NOT a row — the UI prepends it). */
    val topics: StateFlow<List<Topic>> = repo.observeTopics(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Wave 2 voice recording (spec §1 row 1 + row 11) ─────────────────
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartedAtMs: Long = 0
    private var recordTicker: Job? = null

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Live record timer — the composer bar renders it as "m:ss". */
    private val _recordMs = MutableStateFlow(0L)
    val recordMs: StateFlow<Long> = _recordMs.asStateFlow()

    private val _sendingVoice = MutableStateFlow(false)
    val sendingVoice: StateFlow<Boolean> = _sendingVoice.asStateFlow()

    /** Voice notes currently awaiting their transcript (pill → spinner). */
    private val _transcribingIds = MutableStateFlow<Set<String>>(emptySet())
    val transcribingIds: StateFlow<Set<String>> = _transcribingIds.asStateFlow()

    /** MAIN RIVER — thread replies (threadRootId != null) live on the ThreadScreen only. */
    val messages: StateFlow<List<Message>> =
        combine(
            repo.observeMessages(conversationId),
            _activeTopicId,
        ) { rows, topicId ->
            // Topic views (Wave 2 §1 row 9): null = General = WHOLE room
            // (unfiltered); non-null filters the client-side Room flow —
            // incoming message:new rows carry topicId so live views update
            // without extra fetches.
            rows.filter { it.threadRootId == null && (topicId == null || it.topicId == topicId) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Wave 6 — broadcast channel lock: THIS viewer's role from the server
    // detail (ConversationMemberDto.role); non-admins get the glass notice.
    private val _channelRole = MutableStateFlow<String?>(null)
    val channelRole: StateFlow<String?> = _channelRole.asStateFlow()

    fun loadComposerLock() {
        viewModelScope.launch {
            _channelRole.value = runCatching { repo.myRole(conversationId).getOrNull() }.getOrNull()
        }
    }

    // ── REM-A group meta (TTL chip, admin surfaces, invite link) ──────
    /** Live server truth — null until the first load, kept for GroupInfo deep links. */
    private val _groupMeta = MutableStateFlow<GroupMeta?>(null)
    val groupMeta: StateFlow<GroupMeta?> = _groupMeta.asStateFlow()

    fun loadGroupMeta() {
        viewModelScope.launch {
            _groupMeta.value = runCatching { repo.groupMeta(conversationId).getOrNull() }.getOrNull()
        }
    }

    /** Refresh after any group mutation — the TTL/broadcast chips stay honest. */
    fun refreshGroupMeta() = loadGroupMeta()

    // ── REM-A slow-mode lockout (F-MS-20, web retryAfter parity) ─────
    /** Epoch-ms deadline of an active slow-mode lockout — null/0 = composer free. */
    private val _slowModeUntilMs = MutableStateFlow(0L)
    private val _slowModeRemainingSec = MutableStateFlow(0)

    /** Seconds left on the composer lockout (0 = unlocked) — ticks once per second. */
    val slowModeRemainingSec: StateFlow<Int> = _slowModeRemainingSec.asStateFlow()

    private var slowModeTicker: Job? = null

    private fun armSlowMode(seconds: Int) {
        val wait = seconds.coerceAtLeast(1)
        _slowModeUntilMs.value = System.currentTimeMillis() + wait * 1000L
        _slowModeRemainingSec.value = wait
        slowModeTicker?.cancel()
        slowModeTicker = viewModelScope.launch {
            while (true) {
                val remaining = ((_slowModeUntilMs.value - System.currentTimeMillis()) / 1000L).toInt()
                _slowModeRemainingSec.value = remaining.coerceAtLeast(0)
                if (remaining <= 0) break
                delay(250)
            }
            _slowModeUntilMs.value = 0
            _slowModeRemainingSec.value = 0
        }
    }

    /** Send-failure classifier — a 429 arms the composer countdown (mm:ss). */
    private fun failureNotice(failure: Throwable): String {
        val retryAfter = (failure as? PulseApiException)?.retryAfter
        if (retryAfter != null) armSlowMode(retryAfter)
        return failure.message ?: "Couldn't send the message"
    }

    // ── REM-A scheduled sends (F-MS-18, Telegram-style) ──────────────
    private val _scheduled = MutableStateFlow<List<ScheduledItem>>(emptyList())
    val scheduled: StateFlow<List<ScheduledItem>> = _scheduled.asStateFlow()

    private val _scheduledLoading = MutableStateFlow(false)
    val scheduledLoading: StateFlow<Boolean> = _scheduledLoading.asStateFlow()

    fun loadScheduled() {
        viewModelScope.launch {
            _scheduledLoading.value = true
            _scheduled.value = runCatching { repo.scheduledMessages(conversationId).getOrDefault(emptyList()) }
                .getOrDefault(emptyList())
            _scheduledLoading.value = false
        }
    }

    /** POST the pending row, then refresh the manager list (web parity toast). */
    fun scheduleSend(content: String, scheduledAtIso: String) {
        viewModelScope.launch {
            repo.scheduleMessage(conversationId, content, scheduledAtIso)
                .onSuccess { item ->
                    notify("Scheduled for ${formatScheduleStamp(item.scheduledAtIso)} — it sends itself")
                    loadScheduled()
                }
                .onFailure { notify(failureNotice(it), isError = true) }
        }
    }

    fun cancelScheduled(scheduledId: String) {
        viewModelScope.launch {
            runCatching { repo.cancelScheduled(scheduledId) }
                .onSuccess {
                    notify("Scheduled message cancelled")
                    loadScheduled()
                }
                .onFailure { notify("Couldn't cancel the scheduled message", isError = true) }
        }
    }

    private fun formatScheduleStamp(iso: String): String = runCatching {
        val zoned = java.time.OffsetDateTime.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(zoned)
    }.getOrDefault(iso)

    // Wave 6 — DM safety-number sheet (settle-confirmed; NO optimistic lies).
    data class SafetyUi(val peerId: String, val state: SafetyState?, val busy: Boolean = false)

    private val _safety = MutableStateFlow<SafetyUi?>(null)
    val safety: StateFlow<SafetyUi?> = _safety.asStateFlow()

    fun loadSafety(peerId: String) {
        viewModelScope.launch {
            _safety.value = SafetyUi(peerId, null, busy = true)
            _safety.value = SafetyUi(peerId, repo.safetyState(peerId).getOrNull())
        }
    }

    fun verifySafety() {
        val current = _safety.value ?: return
        viewModelScope.launch {
            _safety.value = current.copy(busy = true)
            val result = runCatching { repo.verifyPeer(current.peerId).getOrNull() }
            _safety.value = SafetyUi(current.peerId, result.getOrNull() ?: current.state)
        }
    }

    fun unverifySafety() {
        val current = _safety.value ?: return
        viewModelScope.launch {
            _safety.value = current.copy(busy = true)
            val result = runCatching { repo.unverifyPeer(current.peerId).getOrNull() }
            _safety.value = SafetyUi(current.peerId, result.getOrNull() ?: current.state)
        }
    }

    fun closeSafety() {
        _safety.value = null
    }

    /** Every chat — the ForwardSheet target list (spec §1.1 forward = client re-POST). */
    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "N replies ↳" chip counts — one batched Room query per river change, live. */
    val replyCounts: StateFlow<Map<String, Int>> = messages
        .map { rows ->
            val parents = rows.filter { !it.id.startsWith(TEMP_MESSAGE_PREFIX) }.map { it.id }
            repo.threadReplyCounts(parents)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Jump target awaiting enough of the window to be loaded (search/pin/quote/global). */
    private val _jumpTarget = MutableStateFlow<String?>(null)
    val jumpTarget: StateFlow<String?> = _jumpTarget.asStateFlow()

    private var typingJob: Job? = null
    private var draftSaveJob: Job? = null
    private var readJob: Job? = null
    private var searchJob: Job? = null
    private var flashJob: Job? = null
    private var jumpExpansionJob: Job? = null

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
            runCatching { repo.messagesPage(conversationId, before = null, limit = 200) }
                .onSuccess { (_, hasMore) ->
                    _state.value = _state.value.copy(loading = false, hasMore = hasMore)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                }
            scheduleRead()
            loadPins()
            initialJumpMessageId?.takeIf { it.isNotBlank() }?.let { jumpTo(it) }
        }
        viewModelScope.launch {
            repo.observeConnected().collect { connected ->
                _state.value = _state.value.copy(connected = connected)
            }
        }
        viewModelScope.launch {
            // Wave 2 topics — load the rail on open (UI re-ticks every 15s).
            runCatching { repo.refreshTopics(conversationId) }
        }
        viewModelScope.launch {
            // Self-heal: a topic that disappeared from the rail (deleted
            // elsewhere) resets the view to General — messages fall back.
            topics.collect { list ->
                val active = _activeTopicId.value ?: return@collect
                if (list.none { it.id == active }) _activeTopicId.value = null
            }
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
                        val at = PulseTime.parse(event.at)?.toInstant()?.toEpochMilli()
                        if (at != null) {
                            _state.value = _state.value.copy(
                                partnerLastReadAt = maxOf(_state.value.partnerLastReadAt ?: 0, at),
                            )
                        }
                    }
                    is PulseEvent.MessageReceived -> if (event.conversationId == conversationId) {
                        scheduleRead()
                    }
                    is PulseEvent.OutboxDropped -> notify("Message couldn't be delivered", isError = true)
                    else -> Unit
                }
            }
        }
    }

    // ── read receipts (spec row 3): debounced 600ms while the room is open ──

    private fun scheduleRead() {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            delay(600)
            runCatching { repo.markRead(conversationId) }
        }
    }

    // ── drafts (Wave 0 core) + typing ───────────────────────────

    fun onDraftChanged(text: String) {
        if (_state.value.editing != null) return // composer is editing a row, not drafting
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
        // 600ms debounced local save; the repo mirrors every mutation to the
        // server draft endpoint (fire-and-forget, blank clears — spec row 6).
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

    fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = RoomNotice(text, isError))
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    // ── send / edit ─────────────────────────────────────────────

    fun send(body: String) {
        val editing = _state.value.editing
        if (editing != null) {
            sendEdit(editing.id, body)
            return
        }
        val replyId = _state.value.replyTo?.id
        _state.value = _state.value.copy(replyTo = null)
        viewModelScope.launch {
            // Quote replies DO file to the active topic (only THREAD replies
            // are excluded — the repo drops topicId on parentId sends).
            sendUseCase(conversationId, body, replyId, topicId = _activeTopicId.value)
                .onSuccess { message ->
                    if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        // Network-class failure → queued in the outbox. The
                        // composer text has left for the queue — clear the
                        // draft and let the pending bubble + banner tell it.
                        runCatching { repo.clearDraft(conversationId) }
                    } else {
                        app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                        repo.setTyping(conversationId, viewerName(), false)
                        runCatching { repo.clearDraft(conversationId) }
                        afterOwnSend(message)
                    }
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failureNotice(failure))
                }
        }
    }

    /**
     * REM-A rich send — sticker payloads (F-MS-24), effect-carried text
     * (F-MS-23) and the incognito flag (F-MS-17) all ride ONE repo method.
     * Echo/offline semantics match [send].
     */
    fun sendRich(
        body: String,
        kind: String = "text",
        payload: String? = null,
        anon: Boolean = false,
        onDelivered: ((Message) -> Unit)? = null,
    ) {
        val editing = _state.value.editing
        if (editing != null) {
            sendEdit(editing.id, body)
            return
        }
        val replyId = _state.value.replyTo?.id
        _state.value = _state.value.copy(replyTo = null)
        viewModelScope.launch {
            repo.sendRichMessage(
                conversationId = conversationId,
                body = body,
                kind = kind,
                payload = payload,
                anon = anon,
                replyToId = replyId,
                topicId = _activeTopicId.value,
            )
                .onSuccess { message ->
                    if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        runCatching { repo.clearDraft(conversationId) }
                    } else {
                        app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                        repo.setTyping(conversationId, viewerName(), false)
                        runCatching { repo.clearDraft(conversationId) }
                        afterOwnSend(message)
                        onDelivered?.invoke(message)
                    }
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failureNotice(failure))
                }
        }
    }

    /**
     * Post-send hooks for a DELIVERED row (Wave 2 §1 rows 8/9):
     * - link hint in the body → fire-and-forget unfurl (the sender's client
     *   triggers it, exactly once, only on direct send success — never on the
     *   outbox flush path, web parity);
     * - topic rail refresh (+ the active topic window so counts stay warm).
     */
    private fun afterOwnSend(message: Message) {
        if (message.poll == null && app.pulse.core.media.PulseMedia.isUnfurlCandidate(message.body)) {
            viewModelScope.launch { repo.unfurlMessage(message.id) }
        }
        viewModelScope.launch { runCatching { repo.refreshTopics(conversationId) } }
        _activeTopicId.value?.let { topicId ->
            viewModelScope.launch { runCatching { repo.refreshMessages(conversationId, topicId) } }
        }
    }

    /** PATCH edit — online-only by design (spec row 4); failure is an honest toast. */
    private fun sendEdit(messageId: String, content: String) {
        viewModelScope.launch {
            repo.editMessage(messageId, content.trim())
                .onSuccess {
                    _state.value = _state.value.copy(editing = null)
                }
                .onFailure { failure ->
                    notify(failure.message ?: "Couldn't update the message", isError = true)
                }
        }
    }

    fun beginEdit(message: Message) {
        _state.value = _state.value.copy(editing = message, replyTo = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    // ── pin / save / delete / forward ───────────────────────────

    fun loadPins() {
        viewModelScope.launch {
            runCatching { repo.pinnedMessages(conversationId) }
                .onSuccess { pins -> _state.value = _state.value.copy(pins = pins) }
        }
    }

    fun toggleMessagePin(messageId: String) {
        viewModelScope.launch {
            repo.toggleMessagePin(messageId)
                .onSuccess { message ->
                    notify(if (message.pinnedAt != null) "Message pinned" else "Message unpinned")
                    loadPins()
                }
                .onFailure { notify("Couldn't update the pin", isError = true) }
        }
    }

    fun toggleMessageSave(messageId: String) {
        viewModelScope.launch {
            repo.toggleMessageSave(messageId)
                .onSuccess { saved -> notify(if (saved) "Saved to library" else "Removed from library") }
                .onFailure { notify("Couldn't save the message", isError = true) }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repo.deleteMessage(messageId)
                .onSuccess {
                    notify("Message deleted")
                    // The tombstone row arrives from the server truth — refetch
                    // the window so "🚫 Message deleted" renders immediately.
                    runCatching { repo.refreshMessages(conversationId) }
                }
                .onFailure { notify("Couldn't delete the message", isError = true) }
        }
    }

    fun forwardTo(source: Message, targetIds: List<String>) {
        if (targetIds.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            for (target in targetIds) {
                if (runCatching { repo.forwardMessage(target, source) }.isSuccess) done += 1
            }
            if (done == 0) {
                notify("Couldn't forward — check your connection", isError = true)
            } else {
                notify("Forwarded to $done ${if (done == 1) "chat" else "chats"}")
            }
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { repo.react(messageId, emoji) }
    }

    fun retry() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.messagesPage(conversationId, before = null, limit = 200) }
                .onSuccess { (_, hasMore) -> _state.value = _state.value.copy(loading = false, hasMore = hasMore) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    // ── pagination (spec row 2): before= cursor pages of 40 ────

    /** Oldest REAL row (temps are echoes, never cursors). */
    private fun oldestLoaded(): Message? = messages.value.firstOrNull { !it.id.startsWith(TEMP_MESSAGE_PREFIX) }

    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.hasMore) return
        val cursor = oldestLoaded()?.createdAt ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingOlder = true)
            runCatching { repo.messagesPage(conversationId, before = cursor, limit = PAGE_SIZE) }
                .onSuccess { (rows, more) ->
                    _state.value = _state.value.copy(
                        loadingOlder = false,
                        hasMore = more && rows.isNotEmpty(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loadingOlder = false,
                        notice = RoomNotice("Couldn't load older messages", isError = true),
                    )
                }
        }
    }

    // ── jump + flash (search / pins / quotes / global hits) ────

    fun jumpTo(messageId: String) {
        if (messageId.isBlank()) return
        if (messages.value.any { it.id == messageId }) {
            flash(messageId)
        } else {
            _jumpTarget.value = messageId
        }
    }

    fun consumeJumpTarget() {
        _jumpTarget.value = null
    }

    /**
     * Bounded window expansion (web parity ≤14 rounds): page older until the
     * jump target surfaces or the history is honestly exhausted. Cancel-and-
     * replace: a new target (or a racing loadOlder) never strands the gate.
     */
    fun expandForJump(messageId: String) {
        // Jump surfaces (search/pins/quotes/saved) are whole-room — a topic
        // filter would hide the target. Fall back to General first.
        _activeTopicId.value = null
        jumpExpansionJob?.cancel()
        jumpExpansionJob = viewModelScope.launch {
            var found = false
            try {
                var rounds = 0
                while (rounds < JUMP_MAX_ROUNDS && _state.value.hasMore) {
                    if (messages.value.any { it.id == messageId }) {
                        found = true
                        break
                    }
                    val cursor = oldestLoaded()?.createdAt ?: break
                    _state.value = _state.value.copy(loadingOlder = true)
                    val pageResult = runCatching { repo.messagesPage(conversationId, before = cursor, limit = PAGE_SIZE) }
                    if (pageResult.isFailure) {
                        _state.value = _state.value.copy(loadingOlder = false)
                        break
                    }
                    val (rows, more) = pageResult.getOrThrow()
                    _state.value = _state.value.copy(loadingOlder = false, hasMore = more && rows.isNotEmpty())
                    if (messages.value.any { it.id == messageId }) {
                        found = true
                        break
                    }
                    rounds += 1
                }
            } finally {
                _state.value = _state.value.copy(loadingOlder = false)
            }
            if (found) {
                flash(messageId)
            } else {
                notify("Couldn't reach that message in this chat's history", isError = true)
            }
            _jumpTarget.value = null
        }
    }

    /** Amber ring pulse on one bubble (~1.5s), then it fades and clears. */
    fun flash(messageId: String) {
        flashJob?.cancel()
        _state.value = _state.value.copy(flashMessageId = messageId)
        flashJob = viewModelScope.launch {
            delay(1_600)
            if (_state.value.flashMessageId == messageId) {
                _state.value = _state.value.copy(flashMessageId = null)
            }
        }
    }

    // ── room search (server hits + local window, deduped by id) ──

    fun setSearchOpen(open: Boolean) {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchOpen = open,
            searchResults = emptyList(),
            searching = false,
        )
    }

    fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            delay(250)
            val serverHits = runCatching { repo.searchInConversation(conversationId, q) }
                .getOrDefault(emptyList())
            val localHits = messages.value.filter { it.body.contains(q, ignoreCase = true) }
            val merged = (serverHits + localHits)
                .distinctBy { it.id }
                .filter { !it.isDeleted }
                .sortedBy { it.createdAt }
            _state.value = _state.value.copy(searchResults = merged, searching = false)
        }
    }

    // ── staged media (spec row 9 — never queued, honest errors) ──

    fun onImagePicked(uri: Uri) {
        _state.value = _state.value.copy(
            staged = StagedMedia(kind = StagedMedia.Kind.IMAGE, localUri = uri.toString(), fileName = null, fileSize = null, uploading = true, error = null),
        )
        viewModelScope.launch {
            MediaSupport.imageToDataUrl(context, uri)
                .onSuccess { dataUrl -> uploadStaged(dataUrl, null) }
                .onFailure { stageFailed(it.message ?: "Couldn't prepare the photo") }
        }
    }

    fun onDocumentPicked(uri: Uri) {
        viewModelScope.launch {
            val prepared = MediaSupport.documentToDataUrl(context, uri)
            prepared
                .onSuccess { doc ->
                    _state.value = _state.value.copy(
                        staged = StagedMedia(
                            kind = StagedMedia.Kind.FILE,
                            localUri = uri.toString(),
                            fileName = doc.fileName,
                            fileSize = doc.sizeBytes,
                            mime = doc.mime,
                            uploading = true,
                            error = null,
                        ),
                    )
                    uploadStaged(doc.dataUrl, doc.fileName)
                }
                .onFailure { failure ->
                    notify(failure.message ?: "Couldn't attach the document", isError = true)
                }
        }
    }

    /** Upload (or re-upload) the staged attachment — one route, both kinds. */
    private fun uploadStaged(dataUrl: String, fileName: String?) {
        val staged = _state.value.staged ?: return
        _state.value = _state.value.copy(staged = staged.copy(uploading = true, error = null))
        viewModelScope.launch {
            repo.uploadMedia(dataUrl)
                .onSuccess { path ->
                    val current = _state.value.staged ?: return@launch
                    _state.value = _state.value.copy(
                        staged = current.copy(uploading = false, uploadedPath = path),
                    )
                }
                .onFailure { stageFailed(it.message ?: "Upload failed") }
        }
    }

    private fun stageFailed(message: String) {
        val current = _state.value.staged ?: return
        _state.value = _state.value.copy(staged = current.copy(uploading = false, error = message))
    }

    /** Retry re-runs the upload; an already-uploaded staged card skips it. */
    fun retryStaged() {
        val staged = _state.value.staged ?: return
        if (staged.uploadedPath != null) {
            _state.value = _state.value.copy(staged = staged.copy(error = null))
            return
        }
        viewModelScope.launch {
            when (staged.kind) {
                StagedMedia.Kind.IMAGE -> MediaSupport.imageToDataUrl(context, Uri.parse(staged.localUri))
                    .onSuccess { dataUrl -> uploadStaged(dataUrl, staged.fileName) }
                    .onFailure { stageFailed(it.message ?: "Upload failed") }
                StagedMedia.Kind.FILE -> MediaSupport.documentToDataUrl(context, Uri.parse(staged.localUri))
                    .onSuccess { doc ->
                        _state.value = _state.value.copy(
                            staged = (_state.value.staged ?: staged).copy(
                                fileName = doc.fileName,
                                fileSize = doc.sizeBytes,
                                mime = doc.mime,
                            ),
                        )
                        uploadStaged(doc.dataUrl, doc.fileName)
                    }
                    .onFailure { stageFailed(it.message ?: "Upload failed") }
            }
        }
    }

    fun cancelStaged() {
        _state.value = _state.value.copy(staged = null)
    }

    /** Send the staged attachment with its caption (≤500 — UI enforces). */
    fun sendStaged(caption: String) {
        val staged = _state.value.staged ?: return
        val path = staged.uploadedPath
        if (path == null) {
            stageFailed("Still uploading — hold on a second")
            return
        }
        viewModelScope.launch {
            val topicId = _activeTopicId.value
            val result = when (staged.kind) {
                StagedMedia.Kind.IMAGE -> repo.sendMediaMessage(
                    conversationId = conversationId,
                    body = caption.trim(),
                    imagePath = path,
                    // Wire contract: viewOnce===true REQUIRES imagePath — image kind only.
                    viewOnce = if (staged.viewOnce) true else null,
                    topicId = topicId,
                )
                StagedMedia.Kind.FILE -> repo.sendMediaMessage(
                    conversationId = conversationId,
                    body = caption.trim(),
                    filePath = path,
                    fileName = staged.fileName ?: path.substringAfterLast('/'),
                    fileSize = staged.fileSize,
                    kind = "file",
                    topicId = topicId,
                )
            }
            result
                .onSuccess {
                    _state.value = _state.value.copy(staged = null)
                    app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                    afterOwnSend(it)
                }
                .onFailure { stageFailed(it.message ?: "Couldn't send — you appear to be offline") }
        }
    }

    /** The staged photo's view-once switch (image only, Wave 2 §1 row 5). */
    fun setStagedViewOnce(value: Boolean) {
        val staged = _state.value.staged ?: return
        _state.value = _state.value.copy(staged = staged.copy(viewOnce = value))
    }

    // ── file download + open/share (FileProvider hand-off) ─────

    fun openFile(message: Message, share: Boolean) {
        val path = message.filePath ?: return
        if (_state.value.downloadingFileId != null) return
        _state.value = _state.value.copy(downloadingFileId = message.id)
        viewModelScope.launch {
            repo.downloadMedia(path)
                .onSuccess { absolutePath ->
                    _state.value = _state.value.copy(
                        downloadingFileId = null,
                        openedFile = FileReady(
                            path = absolutePath,
                            mime = PulseMedia.mimeForFileName(message.fileName ?: absolutePath.substringAfterLast('/')),
                            share = share,
                        ),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(downloadingFileId = null)
                    notify("Couldn't download the file", isError = true)
                }
        }
    }

    fun consumeOpenedFile() {
        if (_state.value.openedFile != null) _state.value = _state.value.copy(openedFile = null)
    }

    // ── Wave 2: voice recording (spec §1 row 1 / row 11) ───────────────

    /**
     * Tap-to-start (NOT hold, web parity). RECORD_AUDIO is requested at the
     * UI layer BEFORE this call. MediaRecorder → AAC in MPEG_4, max 10 min,
     * temp file cacheDir/voice-<uuid>.m4a; a 100ms ticker drives the timer.
     */
    fun startRecording() {
        if (_recording.value || _sendingVoice.value) return
        val file = File(context.cacheDir, "voice-${java.util.UUID.randomUUID()}.m4a")
        val mr = MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setMaxDuration(600_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (t: Throwable) {
            runCatching { mr.release() }
            file.delete()
            notify("Couldn't start recording — check microphone access", isError = true)
            return
        }
        recorder = mr
        recordFile = file
        recordStartedAtMs = SystemClock.elapsedRealtime()
        _recordMs.value = 0L
        _recording.value = true
        recordTicker?.cancel()
        recordTicker = viewModelScope.launch {
            while (isActive) {
                delay(100)
                _recordMs.value += 100
            }
        }
    }

    /** Cancel = stop + release + delete, NO minimum-discard toast on cancel (web parity). */
    fun cancelRecording() {
        stopRecorderLocked()
        recordFile?.delete()
        recordFile = null
        _recording.value = false
        _recordMs.value = 0L
    }

    /**
     * Stop → elapsed → `durationMs = max(1, round(ms/100)*100)` (web parity).
     * Under 600ms the note is DISCARDED with a notice. Never queued: upload
     * dataURL → repo.uploadMedia → sendMediaMessage(kind="audio") — kind MUST
     * be "audio" so /transcribe works (spec §1 row 1, web kind-omission bug).
     */
    fun stopAndSend() {
        if (!_recording.value) return
        stopRecorderLocked()
        val elapsedMs = if (recordStartedAtMs > 0) SystemClock.elapsedRealtime() - recordStartedAtMs else _recordMs.value
        val file = recordFile
        recordFile = null
        _recording.value = false
        _recordMs.value = 0L
        if (elapsedMs < app.pulse.core.media.PulseMedia.MIN_VOICE_MS || file == null) {
            file?.delete()
            notify("Too short — voice note discarded")
            return
        }
        val durationMs = app.pulse.core.media.PulseMedia.voiceDurationMs(elapsedMs)
        _sendingVoice.value = true
        viewModelScope.launch {
            MediaSupport.audioToDataUrl(context, file, mime = "audio/mp4")
                .onSuccess { dataUrl ->
                    repo.uploadMedia(dataUrl)
                        .onSuccess { path ->
                            repo.sendMediaMessage(
                                conversationId = conversationId,
                                body = "",
                                audioPath = path,
                                durationMs = durationMs,
                                kind = "audio",
                                topicId = _activeTopicId.value,
                            )
                                .onSuccess { sent ->
                                    app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                                    afterOwnSend(sent)
                                }
                                .onFailure { notify(it.message ?: "Couldn't send the voice note", isError = true) }
                        }
                        .onFailure { notify("Couldn't upload the voice note", isError = true) }
                }
                .onFailure { notify("Couldn't prepare the voice note", isError = true) }
            _sendingVoice.value = false
            file.delete()
        }
    }

    private fun stopRecorderLocked() {
        recordTicker?.cancel()
        recordTicker = null
        val mr = recorder ?: return
        recorder = null
        recordStartedAtMs = 0
        runCatching { mr.stop() }
        runCatching { mr.release() }
    }

    // ── Wave 2: transcription strip (spec §1 row 15) ───────────────────

    /** "Transcribe" pill action — 422/502 surface the honest toast. */
    fun transcribeVoice(messageId: String) {
        if (messageId.startsWith(TEMP_MESSAGE_PREFIX)) return
        if (messageId in _transcribingIds.value) return
        _transcribingIds.value = _transcribingIds.value + messageId
        viewModelScope.launch {
            repo.transcribeMessage(messageId)
                .onFailure { notify("Transcription unavailable", isError = true) }
                .onSuccess { if (it.cached) Unit } // Room row patched → the flow refreshes the strip
            _transcribingIds.value = _transcribingIds.value - messageId
        }
    }

    // ── Wave 2: view-once consumption (spec §1 rows 6/7) ───────────────

    /** Fire-and-forget POST /viewed — the lightbox opens WITHOUT waiting on it. */
    fun consumeViewOnce(message: Message) {
        viewModelScope.launch { repo.markMessageViewed(message.id) }
    }

    // ── Wave 2: polls (spec §1 rows 2/3/4) ─────────────────────────────

    fun createPoll(question: String, options: List<String>) {
        viewModelScope.launch {
            repo.createPoll(conversationId, question.trim(), options)
                .onSuccess {
                    app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                    afterOwnSend(it)
                }
                .onFailure { notify(it.message ?: "Couldn't create the poll", isError = true) }
        }
    }

    /** Single-choice, NO revote once picked (web parity, spec §1 row 3). */
    fun votePoll(pollId: String, optionId: String) {
        viewModelScope.launch {
            repo.votePoll(pollId, optionId)
                .onFailure { notify("Couldn't record your vote", isError = true) }
        }
    }

    fun closePoll(pollId: String) {
        viewModelScope.launch {
            repo.closePoll(pollId)
                .onSuccess { notify("Voting closed — results are final") }
                .onFailure { notify("Couldn't close the poll", isError = true) }
        }
    }

    // ── Wave 2: topics (spec §1 row 9) ─────────────────────────────────

    /** Rail re-tick — UI runs this on open + every 15s + after own sends. */
    fun refreshTopics() {
        viewModelScope.launch { runCatching { repo.refreshTopics(conversationId) } }
    }

    /**
     * null → General (whole room, unfiltered); non-null → also fetch the
     * server window for that topic (native keeps the client-side filter live
     * via the Room flow — improvement over the web 3.5s cache poll).
     */
    fun setActiveTopic(topicId: String?) {
        _activeTopicId.value = topicId
        if (topicId != null) {
            viewModelScope.launch { runCatching { repo.refreshMessages(conversationId, topicId) } }
        }
    }

    fun createTopic(name: String, emoji: String) {
        viewModelScope.launch {
            repo.createTopic(conversationId, name.trim(), emoji)
                .onSuccess { topic ->
                    runCatching { repo.refreshTopics(conversationId) }
                    _activeTopicId.value = topic.id
                    viewModelScope.launch { runCatching { repo.refreshMessages(conversationId, topic.id) } }
                    notify("Filing to $emoji ${topic.name} — next send lands there")
                }
                .onFailure { notify("Couldn't create the topic", isError = true) }
        }
    }

    // ── Wave 7 — collaboration & hub (F-RO-02…09) ──────────────────────

    val viewerId: String get() = repo.viewerId ?: ""

    /** True when THIS room is a group/channel (tournaments + leaderboard are group-only). */
    val isGroup: Boolean get() = conversation.value?.isGroupish == true

    /** Room admin? (channels: role from myRole; groups: creator heuristic on web = admins list) */
    fun isAdmin(): Boolean = channelRole.value == "admin"

    /** Sheet host bus — one flag per surface, all exclusive. */
    var redPacketOpen by androidx.compose.runtime.mutableStateOf(false)
    var gameOpen by androidx.compose.runtime.mutableStateOf(false)
    var tournamentOpen by androidx.compose.runtime.mutableStateOf(false)
    var kanbanOpen by androidx.compose.runtime.mutableStateOf(false)
    var whiteboardOpen by androidx.compose.runtime.mutableStateOf(false)
    var eventsOpen by androidx.compose.runtime.mutableStateOf(false)
    var remindersOpen by androidx.compose.runtime.mutableStateOf(false)
    var leaderboardOpen by androidx.compose.runtime.mutableStateOf(false)
    var redPacketDetailId by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Message→kanban conversion source (set from the message action sheet). */
    var kanbanSourceMessage by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Audit D2 fix: carry the anchored message body so the sheet prefills the derived title (web: server derives from content, cap 80). */
    var kanbanSourceTitle by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Non-surface helper — routes into the room snackbar. */
    fun notifySticky(message: String) = notify(message, isError = false)

    fun openRedPacket() { redPacketOpen = true }
    fun openGame() { gameOpen = true }
    fun openTournament() { tournamentOpen = true }
    fun openKanban() { kanbanOpen = true }
    fun openWhiteboard() { whiteboardOpen = true }
    fun openEvents() { eventsOpen = true }
    fun openReminders() { remindersOpen = true }
    fun openLeaderboard() { leaderboardOpen = true }
    fun openRedPacketDetail(id: String) { redPacketDetailId = id }

    /** POST /api/redpackets — honest 402/400 copy surfaces verbatim. */
    fun createRedPacket(total: Long, count: Int, note: String?) {
        viewModelScope.launch {
            repo.createRedPacket(conversationId, total, count, note)
                .onSuccess {
                    redPacketOpen = false
                    notify("Red packet sent — $total PC in $count grabs")
                }
                .onFailure { notify(it.message ?: "Could not send the red packet", isError = true) }
        }
    }

    fun createGame(opponentId: String?) {
        viewModelScope.launch {
            repo.createGame(conversationId, opponentId)
                .onSuccess {
                    gameOpen = false
                    notify("Tic-tac-toe challenge sent")
                }
                .onFailure { notify(it.message ?: "Could not start the game", isError = true) }
        }
    }

    fun createTournament(name: String) {
        viewModelScope.launch {
            repo.createTournament(conversationId, name.trim())
                .onSuccess {
                    tournamentOpen = false
                    notify("Tournament \"$name\" started")
                }
                .onFailure { notify(it.message ?: "Could not start the tournament", isError = true) }
        }
    }

    fun createKanbanCard(title: String, column: String, assigneeId: String?) {
        viewModelScope.launch {
            repo.createKanbanCard(conversationId, title.trim(), column, assigneeId, kanbanSourceMessage)
                .onSuccess {
                    kanbanSourceMessage = null
                    kanbanSourceTitle = null
                    notify("Task \"${it.title}\" added to the board")
                }
                .onFailure { notify(it.message ?: "Could not add the card", isError = true) }
        }
    }

    /** Message long-press → "Add to board" (web parity: message→card title cap 80). */
    fun addMessageToBoard(messageId: String, content: String) {
        kanbanSourceMessage = messageId
        kanbanSourceTitle = content
        kanbanOpen = true
    }

    // suspend fetchers used by the in-bubble cards (polling parity)

    suspend fun gameDetail(matchId: String): GameDetailDto? =
        runCatching { repo.game(matchId).getOrNull() }.getOrNull()

    fun gameMove(matchId: String, cell: Int) {
        viewModelScope.launch {
            repo.gameMove(matchId, cell)
                .onFailure { notify(it.message ?: "Move rejected", isError = true) }
        }
    }

    fun joinGame(matchId: String) {
        viewModelScope.launch {
            repo.joinGame(matchId)
                .onFailure { notify(it.message ?: "Could not join", isError = true) }
        }
    }

    suspend fun redPacketDetail(packetId: String): RedPacketDetailDto? =
        runCatching { repo.redPacket(packetId).getOrNull() }.getOrNull()

    /** Atomic grab — 409 copy (own packet / already grabbed / race) surfaces verbatim. */
    fun grabRedPacket(packetId: String) {
        viewModelScope.launch {
            repo.grabRedPacket(packetId)
                .onSuccess { notify("You grabbed ${it.amount} PC 🎉") }
                .onFailure { notify(it.message ?: "Could not grab the red packet", isError = true) }
        }
    }

    suspend fun tournamentDetail(tournamentId: String): TournamentSummaryDto? =
        runCatching { repo.tournament(tournamentId).getOrNull() }.getOrNull()

    fun joinTournament(tournamentId: String) {
        viewModelScope.launch {
            repo.joinTournament(tournamentId)
                .onSuccess { notify("You are in — play tic-tac-toe matches to score points") }
                .onFailure { notify(it.message ?: "Could not join the tournament", isError = true) }
        }
    }

    fun finishTournament(tournamentId: String) {
        viewModelScope.launch {
            repo.finishTournament(tournamentId)
                .onSuccess { notify("Tournament finished") }
                .onFailure { notify(it.message ?: "Could not finish the tournament", isError = true) }
        }
    }

    /** Reminder create via the sheet (absolute picker) — relative parse lives in PulseWave7Logic. */
    fun createReminder(note: String, remindAtIso: String, anchoredMessageId: String? = null) {
        viewModelScope.launch {
            repo.createReminder(conversationId, anchoredMessageId, note, remindAtIso)
                .onSuccess { remindersOpen = false }
                .onFailure { notify(it.message ?: "Could not set the reminder", isError = true) }
        }
    }

    /** Message long-press → "Remind me…" prefills the anchored message. */
    fun remindMe(messageId: String) {
        kanbanSourceMessage = null
        kanbanSourceTitle = null
        _reminderAnchor.value = messageId
        remindersOpen = true
    }

    private val _reminderAnchor = androidx.compose.runtime.mutableStateOf<String?>(null)
    val reminderAnchor: androidx.compose.runtime.State<String?> = _reminderAnchor

    // ── Wave 7 sheet-side data fetchers (suspend, called from sheets) ──

    /** Room member (id → name) pairs for the direct-invite game picker. */
    fun roomMembers(): List<Pair<String, String>> =
        conversation.value?.members?.map { it.id to it.name } ?: emptyList()

    suspend fun kanbanBoard(conversationId: String): KanbanPageDto? =
        runCatching { repo.kanbanBoard(conversationId).getOrNull() }.getOrNull()

    fun moveKanbanCard(cardId: String, column: String, position: Long?) {
        viewModelScope.launch {
            repo.updateKanbanCard(cardId, null, column, null, false, position)
                .onFailure { notify(it.message ?: "Couldn't move the card", isError = true) }
        }
    }

    fun deleteKanbanCard(cardId: String) {
        viewModelScope.launch {
            repo.deleteKanbanCard(cardId)
                .onFailure { notify(it.message ?: "Couldn't delete the card", isError = true) }
        }
    }

    suspend fun whiteboard(conversationId: String, since: Long?): WhiteboardPageDto? =
        runCatching { repo.whiteboard(conversationId, since).getOrNull() }.getOrNull()

    fun postWhiteboardStrokes(conversationId: String, strokes: List<WhiteboardStrokePostDto>) {
        viewModelScope.launch {
            repo.postWhiteboardStrokes(conversationId, strokes)
                .onFailure { notify(it.message ?: "Stroke upload failed — try again", isError = true) }
        }
    }

    fun undoWhiteboard(conversationId: String) {
        viewModelScope.launch {
            repo.undoWhiteboardStroke(conversationId).onFailure {
                notify(it.message ?: "Nothing to undo", isError = true)
            }
        }
    }

    fun clearWhiteboard(conversationId: String) {
        viewModelScope.launch {
            repo.clearWhiteboard(conversationId)
                .onSuccess { notify("Board cleared") }
                .onFailure { notify(it.message ?: "Couldn't clear the board", isError = true) }
        }
    }

    suspend fun events(conversationId: String): List<GroupEventDto>? =
        runCatching { repo.events(conversationId).getOrNull()?.events }.getOrNull()

    fun createEvent(title: String, startsAtIso: String, description: String?, location: String?) {
        viewModelScope.launch {
            repo.createEvent(conversationId, title, startsAtIso, description, location)
                .onSuccess {
                    eventsOpen = false
                    notify("Event scheduled — see you there")
                }
                .onFailure { notify(it.message ?: "Could not schedule the event.", isError = true) }
        }
    }

    fun rsvp(eventId: String, status: String) {
        viewModelScope.launch {
            repo.rsvpEvent(eventId, status)
                .onFailure { notify(it.message ?: "RSVP failed — try again.", isError = true) }
        }
    }

    fun checkin(eventId: String) {
        viewModelScope.launch {
            repo.checkinEvent(eventId)
                .onSuccess {
                    when {
                        it.alreadyCheckedIn -> notify("Already checked in.")
                        it.xpAwarded -> notify("Checked in — see you there · +15 XP")
                        else -> notify("Checked in — see you there")
                    }
                }
                .onFailure { notify(it.message ?: "Check-in failed — try again.", isError = true) }
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            repo.deleteEvent(eventId)
                .onSuccess { notify("Event deleted.") }
                .onFailure { notify(it.message ?: "Could not delete the event.", isError = true) }
        }
    }

    suspend fun reminders(): RemindersPageDto? =
        runCatching { repo.reminders(dueOnly = false).getOrNull() }.getOrNull()

    fun resolveReminder(reminderId: String) {
        viewModelScope.launch {
            repo.resolveReminder(reminderId).onFailure { notify(it.message ?: "Couldn't resolve the reminder", isError = true) }
        }
    }

    fun deleteReminder(reminderId: String) {
        viewModelScope.launch {
            repo.deleteReminder(reminderId)
                .onSuccess { notify("Reminder canceled") }
                .onFailure { notify(it.message ?: "Could not cancel the reminder", isError = true) }
        }
    }

    suspend fun leaderboard(conversationId: String?): LeaderboardPageDto? =
        runCatching { repo.leaderboard(conversationId).getOrNull() }.getOrNull()

    override fun onCleared() {
        // Recording must never outlive the room — stop + release + delete.
        stopRecorderLocked()
        recordFile?.delete()
        recordFile = null
        _recording.value = false
        voicePlayer.release()
        super.onCleared()
    }

    companion object {
        /** Older-page size — spec §1.1 pagination cursor rhythm. */
        const val PAGE_SIZE = 40

        /** Bounded jump-window expansion — web parity (≤14 before= rounds). */
        const val JUMP_MAX_ROUNDS = 14

        /** Web parity unfurl hint moved to PulseMedia.isUnfurlCandidate (JVM-pinned). */
    }
}

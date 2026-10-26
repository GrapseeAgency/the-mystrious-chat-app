package app.pulse.feature.chat

import app.pulse.domain.sync.MessageHistoryCursor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.media.PulseMedia
import app.pulse.core.time.PulseTime
// R1-W2F - per-conversation themes (F-FX-05) + location payloads (F-MD-07).
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.Message
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.ScheduledItem
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.nudgeText
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot room-level snackbar/toast notice (web toast parity). */
data class RoomNotice(val text: String, val isError: Boolean = false)

/**
 * R1-W2F - one-shot location fix state for the pin share (F-MD-07). The
 * last-known seed keeps the sheet honest while a fresh fix lands; the
 * permission gate lives at the UI layer (mic/camera precedent).
 */
data class LocationFix(
    val locating: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    /** No provider / LocationManager refused - the sheet offers a retry. */
    val failed: Boolean = false,
)

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
    /** Wave 2 view-once (IMAGE kind only - wire requires imagePath when true). */
    val viewOnce: Boolean = false,
    val selectionId: String = java.util.UUID.randomUUID().toString(),
    val sending: Boolean = false,
    val durationMs: Long? = null,
    val intendedTopicId: String? = null,
    val saved: Boolean = false,
    val caption: String = "",
    val revision: Long = 0,
    val sessionKey: String? = null,
) {
    enum class Kind { IMAGE, FILE, AUDIO }
}

/** A downloaded file the UI must hand to the system (open or share). */
data class FileReady(val path: String, val mime: String, val share: Boolean)

/**
 * Chat room state holder - messages, live typing/presence, sends + reactions
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
    /** The ONE active voice player - room + thread share the singleton. */
    val voicePlayer: VoicePlayer,
    // R1-W2I - PiP pane store (F-PI-01..03): the pop-out toggle writes the
    // same process-singleton the shell-level overlay renders from.
    private val pipStore: PulsePiPStore,
    // R2-C item 4 - the durable whiteboard pending-stroke draft store
    // (iOS PulseWhiteboardDraft parity; per-conversation DataStore keys).
    private val prefsStore: app.pulse.domain.repository.PulsePrefsStore,
) : ViewModel() {

    /** Wave 8 - server-backed prefs for bubble corners / density / wallpaper. */
    val prefs: StateFlow<WirePulsePrefs> = repo.pulsePrefs
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            app.pulse.protocol.PulseWave8Logic.PRIVATE_DEFAULTS,
        )

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    val personalScreenPrivacy = repo.observePersonalScreenPrivacy(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.pulse.domain.model.PersonalScreenPrivacy())

    // ── R1-W2I - PiP pane (F-PI-01..03), web usePipChat legacy contract ──

    /**
     * The focused pane's conversation (null = stack-only / none) - the room
     * header's pop-out toggle reads it exactly like web chat-room.tsx's
     * `pipConversationId` selector (`s.isOpen ? s.conversationId : null`).
     */
    val pipOverlayOpen: StateFlow<Boolean> = pipStore.state.map { it.isOpen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val pipFocusedConversationId: StateFlow<String?> = pipStore.state
        .map { s -> if (s.isOpen) s.conversationId else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Web header toggle parity: this room focused → close it; anything
     *  else (another pane focused / no pane) → open THIS conversation's pane. */
    fun togglePipPane() {
        if (pipFocusedConversationId.value == conversationId) pipStore.close() else pipStore.open(conversationId)
    }

    /** Global-search / notification jump - the room scrolls + flashes on arrival. */
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
        val flashRevision: Long = 0,
        val searchOpen: Boolean = false,
        val searchResults: List<Message> = emptyList(),
        val searching: Boolean = false,
        val staged: StagedMedia? = null,
        val downloadingFileId: String? = null,
        val openedFile: FileReady? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val missedArrivals = app.pulse.domain.sync.MissedMessageArrivals()
    private val _missedCount = MutableStateFlow(0)
    val missedCount: StateFlow<Int> = _missedCount.asStateFlow()

    fun setReadingTail(value: Boolean) {
        missedArrivals.configure(conversationId, repo.viewerId, _activeTopicId.value)
        missedArrivals.setReadingTail(value)
        _missedCount.value = missedArrivals.count
    }


    /** Composer restore seed - local draft table first, server myDraft fallback. */
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()

    // ── Wave 2 topics (spec §1 row 9): null = General = WHOLE room ──────
    private val _activeTopicId = MutableStateFlow<String?>(null)
    val activeTopicId: StateFlow<String?> = _activeTopicId.asStateFlow()

    /** Live topic chips (General is NOT a row - the UI prepends it). */
    val topics: StateFlow<List<Topic>> = repo.observeTopics(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Wave 2 voice recording (spec §1 row 1 + row 11) ─────────────────
    private val recorderAudioToken = java.util.UUID.randomUUID().toString()
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartedAtMs: Long = 0
    private var recordTicker: Job? = null

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Live record timer - the composer bar renders it as "m:ss". */
    private val _recordMs = MutableStateFlow(0L)
    val recordMs: StateFlow<Long> = _recordMs.asStateFlow()

    private val _sendingVoice = MutableStateFlow(false)
    val sendingVoice: StateFlow<Boolean> = _sendingVoice.asStateFlow()

    /**
     * D31 live waveform - normalized mic amplitudes (0f..1f) sampled from
     * MediaRecorder.maxAmplitude every 100 ms while the hold lasts. The
     * composer renders the last [PulseMedia.RECORD_WAVEFORM_BARS] entries as
     * bars; the list is DETERMINISTIC from the amplitude samples (no random).
     */
    private val _recordAmps = MutableStateFlow<List<Float>>(emptyList())
    val recordAmps: StateFlow<List<Float>> = _recordAmps.asStateFlow()

    /** Voice notes currently awaiting their transcript (pill → spinner). */
    private val _transcribingIds = MutableStateFlow<Set<String>>(emptySet())
    val transcribingIds: StateFlow<Set<String>> = _transcribingIds.asStateFlow()

    /** Share one raw Room/expiry stream before filtering the main river. */
    private val roomRows = repo.observeMessages(conversationId)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** MAIN RIVER - thread replies (threadRootId != null) live on the ThreadScreen only. */
    val messages: StateFlow<List<Message>> =
        combine(
            roomRows,
            _activeTopicId,
        ) { rows, topicId ->
            // Topic views (Wave 2 §1 row 9): null = General = WHOLE room
            // (unfiltered); non-null filters the client-side Room flow -
            // incoming message:new rows carry topicId so live views update
            // without extra fetches.
            rows.filter { it.threadRootId == null && (topicId == null || it.topicId == topicId) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Wave 6 - broadcast channel lock: THIS viewer's role from the server
    // detail (ConversationMemberDto.role); non-admins get the glass notice.
    private val _channelRole = MutableStateFlow<String?>(null)
    val channelRole: StateFlow<String?> = _channelRole.asStateFlow()

    fun loadComposerLock() {
        viewModelScope.launch {
            _channelRole.value = runCatching { repo.myRole(conversationId).getOrNull() }.getOrNull()
        }
    }

    // ── REM-A group meta (TTL chip, admin surfaces, invite link) ──────
    /** Live server truth - null until the first load, kept for GroupInfo deep links. */
    private val _groupMeta = MutableStateFlow<GroupMeta?>(null)
    val groupMeta: StateFlow<GroupMeta?> = _groupMeta.asStateFlow()

    fun loadGroupMeta() {
        viewModelScope.launch {
            _groupMeta.value = runCatching { repo.groupMeta(conversationId).getOrNull() }.getOrNull()
        }
    }

    /** Refresh after any group mutation - the TTL/broadcast chips stay honest. */
    fun refreshGroupMeta() = loadGroupMeta()

    // ── REM-A slow-mode lockout (F-MS-20, web retryAfter parity) ─────
    /** Epoch-ms deadline of an active slow-mode lockout - null/0 = composer free. */
    private val _slowModeUntilMs = MutableStateFlow(0L)
    private val _slowModeRemainingSec = MutableStateFlow(0)

    /** Seconds left on the composer lockout (0 = unlocked) - ticks once per second. */
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

    /** Send-failure classifier - a 429 arms the composer countdown (mm:ss). */
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

    private val _scheduledBusy = MutableStateFlow(false)
    val scheduledBusy: StateFlow<Boolean> = _scheduledBusy.asStateFlow()
    private val _scheduledError = MutableStateFlow<String?>(null)
    val scheduledError: StateFlow<String?> = _scheduledError.asStateFlow()
    private var scheduledRevision = 0L

    fun loadScheduled() {
        if (_scheduledBusy.value) return
        val epoch = repo.attachmentSessionKey() ?: return
        val ticket = ++scheduledRevision
        _scheduledLoading.value = true
        viewModelScope.launch {
            try {
                val rows = repo.scheduledMessages(conversationId).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() == epoch && ticket == scheduledRevision) {
                    _scheduled.value = rows; _scheduledError.value = null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch && ticket == scheduledRevision) {
                    if (error is app.pulse.domain.sync.PrivateScheduleUnavailableException) _scheduled.value = emptyList()
                    _scheduledError.value = error.message ?: "Couldn't load scheduled messages"
                }
            } finally {
                if (repo.attachmentSessionKey() == epoch && ticket == scheduledRevision) _scheduledLoading.value = false
            }
        }
    }

    fun scheduleSend(content: String, scheduledAtIso: String, confirmed: () -> Unit = {}) {
        if (_scheduledBusy.value) return
        val epoch = repo.attachmentSessionKey() ?: return
        _scheduledBusy.value = true; scheduledRevision++; _scheduledLoading.value = false
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                val item = repo.scheduleMessage(conversationId, content, scheduledAtIso).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                _scheduled.value = (_scheduled.value.filterNot { it.id == item.id } + item).sortedBy { it.scheduledAtIso }
                confirmed()
                repo.acknowledgeScheduledCreation(item).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                notify("Scheduled for ${formatScheduleStamp(item.scheduledAtIso)}")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) notify(failureNotice(error), isError = true)
            } finally { if (repo.attachmentSessionKey() == epoch) _scheduledBusy.value = false }
        }
    }

    fun cancelScheduled(scheduledId: String) {
        if (_scheduledBusy.value) return
        val epoch = repo.attachmentSessionKey() ?: return
        _scheduledBusy.value = true; scheduledRevision++; _scheduledLoading.value = false
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                repo.cancelScheduled(scheduledId).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                _scheduled.value = _scheduled.value.filterNot { it.id == scheduledId }
                notify("Scheduled message cancelled")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) {
                    _scheduledError.value = error.message ?: "Couldn't cancel the scheduled message"
                    notify(_scheduledError.value.orEmpty(), isError = true)
                }
            } finally { if (repo.attachmentSessionKey() == epoch) _scheduledBusy.value = false }
        }
    }

    private fun formatScheduleStamp(iso: String): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()))
    }.getOrDefault(iso)

    // ── R3-B item 4 - F-MS-17 incognito arming (GROUPS only, session-scoped) ──
    /**
     * Web anonNext parity (chat-room.tsx:806): armed → the NEXT plain text
     * send posts anonymously; the server clamps it off on DMs, one-shot
     * (disarmed on acceptance), never persisted.
     */
    private val _anonNext = MutableStateFlow(false)
    val anonNext: StateFlow<Boolean> = _anonNext.asStateFlow()

    fun toggleIncognito() {
        _anonNext.value = !_anonNext.value
    }

    // Wave 6 - DM safety-number sheet (settle-confirmed; NO optimistic lies).
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

    /** Every chat - the ForwardSheet target list (spec §1.1 forward = client re-POST). */
    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Observe raw room rows: a reply-only change may leave the main river identical. */
    val replyCounts: StateFlow<Map<String, Int>> = roomRows
        .map { rows -> app.pulse.domain.sync.cachedThreadReplyCounts(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    data class ThreadJumpRequest(val rootId: String, val replyId: String, val revision: Long)
    private val _threadJump = MutableStateFlow<ThreadJumpRequest?>(null)
    val threadJump: StateFlow<ThreadJumpRequest?> = _threadJump.asStateFlow()
    fun consumeThreadJump(request: ThreadJumpRequest) {
        if (_threadJump.value == request) _threadJump.value = null
    }

    private var typingJob: Job? = null
    private var draftSaveJob: Job? = null
    private var searchJob: Job? = null
    private var activeSearchQuery = ""
    private var pinsJob: Job? = null
    private var flashJob: Job? = null
    private var historyJob: Job? = null
    private var historyRevision = 0L
    private var historyCursor: MessageHistoryCursor? = null

    private fun cancelHistory() {
        historyRevision++
        historyJob?.cancel()
        historyJob = null
        _state.value = _state.value.copy(loading = false, loadingOlder = false)
    }

    private var attachmentTask: Job? = null
    private var attachmentEpoch = 0L
    private var persistedAttachmentId: String? = null
    private var recordingAttachmentSession: String? = null

    private var composerRevision = 0L
    private var lastTypingSentAt = 0L
    private val typingPeers = linkedMapOf<String, String>()
    private val typingExpiry = mutableMapOf<String, Job>()

    private var readingAnchors: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            // This Room stream includes messages cached by pin/search requests,
            // and emits at TTL deadlines even without any network activity.
            roomRows.collect { current ->
                val now = System.currentTimeMillis()
                missedArrivals.configure(conversationId, repo.viewerId, _activeTopicId.value)
                missedArrivals.observe(current, now)
                _missedCount.value = missedArrivals.count
                _state.value = _state.value.copy(
                    pins = app.pulse.domain.sync.reconcileMessageSelection(_state.value.pins, current, now) { it.pinnedAt != null },
                    searchResults = app.pulse.domain.sync.reconcileMessageSelection(_state.value.searchResults, current, now) {
                        activeSearchQuery.isNotBlank() && it.body.contains(activeSearchQuery, ignoreCase = true)
                    },
                )
            }
        }

        restoreAttachmentDraft()
        viewModelScope.launch {
            // Wave 0 draft restore: the local draft table wins; when it is
            // empty fall back to the server-mirrored myDraft on the summary.
            val local = runCatching { repo.observeDraft(conversationId).firstOrNull() }.getOrNull()
            if (local != null) {
                if (composerRevision == 0L) _initialDraft.value = local
            } else {
                val server = runCatching {
                    repo.observeConversations()
                        .map { list -> list.firstOrNull { it.id == conversationId }?.myDraft }
                        .firstOrNull { !it.isNullOrBlank() }
                }.getOrNull()
                if (composerRevision == 0L && !server.isNullOrBlank()) _initialDraft.value = server
            }
        }
        retry()
        val initialHistoryTicket = historyRevision
        viewModelScope.launch {
            state.first { !it.loading }
            loadPins()
            if (historyRevision == initialHistoryTicket) initialJumpMessageId?.takeIf { it.isNotBlank() }?.let { jumpTo(it) }
        }
        viewModelScope.launch {
            repo.observeConnected().collect { connected ->
                _state.value = _state.value.copy(connected = connected)
                if (connected) scheduleRead()
            }
        }
        viewModelScope.launch {
            // Wave 2 topics - load the rail on open (UI re-ticks every 15s).
            runCatching { repo.refreshTopics(conversationId) }
        }
        viewModelScope.launch {
            // Self-heal: a topic that disappeared from the rail (deleted
            // elsewhere) resets the view to General - messages fall back.
            topics.collect { list ->
                val active = _activeTopicId.value ?: return@collect
                if (list.none { it.id == active }) _activeTopicId.value = null
            }
        }
        viewModelScope.launch {
            repo.events().collect { event ->
                when (event) {
                    is PulseEvent.Typing -> if (event.conversationId == conversationId && event.userId != repo.viewerId) {
                        typingExpiry.remove(event.userId)?.cancel()
                        if (event.isTyping) {
                            typingPeers[event.userId] = event.userName.ifBlank { "Someone" }
                            typingExpiry[event.userId] = viewModelScope.launch {
                                delay(4_000)
                                typingPeers.remove(event.userId)
                                typingExpiry.remove(event.userId)
                                _state.value = _state.value.copy(partnerTypingName = typingPeers.values.lastOrNull())
                            }
                        } else typingPeers.remove(event.userId)
                        _state.value = _state.value.copy(partnerTypingName = typingPeers.values.lastOrNull())
                    }
                    is PulseEvent.MessageRead -> if (event.conversationId == conversationId && event.userId != repo.viewerId) {
                        val at = PulseTime.parse(event.at)?.toInstant()?.toEpochMilli()
                        if (at != null) {
                            _state.value = _state.value.copy(
                                partnerLastReadAt = maxOf(_state.value.partnerLastReadAt ?: 0, at),
                            )
                        }
                    }
                    is PulseEvent.MessageReceived -> if (event.conversationId == conversationId) {
                        missedArrivals.configure(conversationId, repo.viewerId, _activeTopicId.value)
                        missedArrivals.receive(event.message, System.currentTimeMillis())
                        _missedCount.value = missedArrivals.count
                        // D29/F-MS-23 - incoming effect payloads fire the
                        // app-wide burst (web triggerEffectFor parity:
                        // confetti→confetti · sparkles→stars · lasers/echo→burst).
                        PulseEffects.burstOfPayload(event.message.payload)?.let { kind ->
                            app.pulse.core.fx.PulseFx.fire(kind)
                        }
                    }
                    is PulseEvent.OutboxDropped -> if (event.conversationId == conversationId && event.threadRootId == null) notify(event.reason, isError = true)
                    else -> Unit
                }
            }
        }
    }

    // ── read receipts (spec row 3): debounced 600ms while the room is open ──


    fun setReadingAnchors(messageIds: Set<String>) {
        if (readingAnchors == messageIds) return
        readingAnchors = messageIds
        readJobs.update(messageIds)
    }

    private val readScope by lazy { app.pulse.domain.sync.VisibleReadScope(repo, conversationId) }
    private val readIntents = mutableMapOf<String, app.pulse.domain.sync.VisibleReadIntent>()
    private val readJobs by lazy {
        app.pulse.domain.sync.VisibleReadJobs(viewModelScope,
            isCurrent = { it in readingAnchors },
            send = { id -> readIntents.getOrPut(id) { readScope.message(id) }.send() })
    }
    private fun scheduleRead() { readJobs.restart(readingAnchors) }

    // ── drafts (Wave 0 core) + typing ───────────────────────────

    fun onDraftChanged(text: String) {
        composerRevision++
        if (_state.value.editing != null) return // Track newer edit text, but do not persist it as a draft.
        repo.stageDraft(conversationId, text) // reserve/write locally before the debounce
        val draftToken = repo.captureDraftToken(conversationId)
        val userName = viewerName()
        val now = android.os.SystemClock.elapsedRealtime()
        if (text.isBlank()) {
            lastTypingSentAt = 0L
            viewModelScope.launch { repo.setTyping(conversationId, userName, false) }
        } else if (typingJob?.isActive != true || now - lastTypingSentAt >= 1_500L) {
            lastTypingSentAt = now
            viewModelScope.launch { repo.setTyping(conversationId, userName, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(1_200)
            repo.setTyping(conversationId, userName, false)
            typingJob = null
        }
        // 600ms debounced local save; the repo mirrors every mutation to the
        // server draft endpoint (fire-and-forget, blank clears - spec row 6).
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(600)
            runCatching { draftToken?.let { repo.saveStagedDraft(conversationId, text, it) } }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    private fun viewerName(): String {
        val conv = conversation.value ?: return repo.viewerId ?: ""
        val idx = conv.memberIds.indexOf(repo.viewerId)
        return conv.memberNames.getOrNull(idx) ?: repo.viewerId ?: ""
    }

    fun setReplyTo(message: Message?) {
        _state.value = _state.value.copy(replyTo = message?.let {
            if (it.viewOnce) it.copy(body = "View-once message", imagePath = null) else it
        })
    }

    fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = RoomNotice(text, isError))
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    // ── send / edit ─────────────────────────────────────────────

    fun send(body: String, onRejected: (() -> Unit)? = null) {
        val editing = _state.value.editing
        if (editing != null) {
            sendEdit(editing.id, body)
            return
        }
        val revision = composerRevision
        val draftToken = repo.captureDraftToken(conversationId)
        draftSaveJob?.cancel()
        val topic = _activeTopicId.value
        val reply = _state.value.replyTo
        val replyId = reply?.id
        _state.value = _state.value.copy(replyTo = null)
        // R3-B item 4 - the arming only consumes on a real send in GROUPS
        // (web chat-room.tsx:1670 gates on isGroup). The optimistic echo
        // already wears the deterministic "Adjective the Animal" alias the
        // server will store (web anonAliasPreview parity) - fnv1a + word
        // lists ported 1:1 in protocol IncognitoAlias (JVM-pinned).
        val anonArmed = _anonNext.value && isGroup
        val anonAliasPreview = if (anonArmed) {
            app.pulse.protocol.IncognitoAlias.aliasFor(repo.viewerId ?: "", conversationId)
        } else {
            null
        }
        viewModelScope.launch {
            // Quote replies DO file to the active topic (only THREAD replies
            // are excluded - the repo drops topicId on parentId sends).
            safelySend { sendUseCase(conversationId, body, replyId, topicId = topic, anon = anonArmed, anonAliasPreview = anonAliasPreview, draftToken = draftToken) }
                .onSuccess { receipt ->
                    if (receipt.message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        // Network-class failure → queued in the outbox. The
                        // composer text has left for the queue - clear the
                        // draft and let the pending bubble + banner tell it.
                        // (Incognito sends never reach this branch - the repo
                        // refuses to queue an un-maskable row.)
                        consumeComposer(revision)
                    } else {
                        // One-shot: web disarms the mask after a successful send
                        // (chat-room.tsx:1747-1750).
                        if (anonArmed) _anonNext.value = false
                        app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                        repo.setTyping(conversationId, viewerName(), false)
                        consumeComposer(revision)
                        afterOwnSend(receipt.message)
                        // R31-a parity - the web-verbatim streak nudge (fires
                        // only when THIS send GREW the streak, second-or-later
                        // consecutive day).
                        maybeStreakNudge(receipt.streak)
                    }
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failureNotice(failure))
                    if (composerRevision == revision) {
                        if (_state.value.replyTo == null) _state.value = _state.value.copy(replyTo = reply)
                        onRejected?.invoke()
                    }
                }
        }
    }

    /**
     * REM-A rich send - sticker payloads (F-MS-24), effect-carried text
     * (F-MS-23) and the incognito flag (F-MS-17) all ride ONE repo method.
     * Echo/offline semantics match [send].
     */
    fun sendRich(
        body: String,
        kind: String = "text",
        payload: String? = null,
        anon: Boolean = false,
        onDelivered: ((Message) -> Unit)? = null,
        onRejected: (() -> Unit)? = null,
        consumeDraft: Boolean = false,
        /** Send burst kind - effect sends map to their web EFFECT_PARTICLES kind. */
        fxKind: app.pulse.core.fx.PulseFx.BurstKind? = app.pulse.core.fx.PulseFx.BurstKind.BURST,
    ) {
        val editing = _state.value.editing
        if (editing != null) {
            sendEdit(editing.id, body)
            return
        }
        val revision = composerRevision
        val draftToken = if (consumeDraft) repo.captureDraftToken(conversationId) else null
        if (consumeDraft) draftSaveJob?.cancel()
        val topic = _activeTopicId.value
        val reply = _state.value.replyTo
        val replyId = reply?.id
        _state.value = _state.value.copy(replyTo = null)
        viewModelScope.launch {
            safelySend { repo.sendRichMessage(
                conversationId = conversationId,
                body = body,
                kind = kind,
                payload = payload,
                anon = anon,
                replyToId = replyId,
                topicId = topic,
                draftToken = draftToken,
            ) }
                .onSuccess { receipt ->
                    if (receipt.message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        if (consumeDraft) consumeComposer(revision)
                    } else {
                        fxKind?.let { kind -> app.pulse.core.fx.PulseFx.fire(kind, count = 26) }
                        repo.setTyping(conversationId, viewerName(), false)
                        if (consumeDraft) consumeComposer(revision)
                        afterOwnSend(receipt.message)
                        onDelivered?.invoke(receipt.message)
                        maybeStreakNudge(receipt.streak)
                    }
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failureNotice(failure))
                    if (composerRevision == revision) {
                        if (_state.value.replyTo == null) _state.value = _state.value.copy(replyTo = reply)
                        onRejected?.invoke()
                    }
                }
        }
    }

    private suspend fun <T> safelySend(block: suspend () -> Result<T>): Result<T> {
        return try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun consumeComposer(revision: Long) {
        // A response for message A does not own the newer draft B.
        if (composerRevision == revision) {
            // Local consumption already committed with the outbox. An ACK is
            // not a new edit and must not overwrite a reopened room's draft.
            composerRevision++
            draftSaveJob?.cancel()
            viewModelScope.launch { repo.setTyping(conversationId, viewerName(), false) }
        }
    }

    /**
     * Post-send hooks for a DELIVERED row (Wave 2 §1 rows 8/9):
     * - link hint in the body → fire-and-forget unfurl (the sender's client
     *   triggers it, exactly once, only on direct send success - never on the
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

    /**
     * R31-a streak nudge - web chat-room.tsx:1736-1745 verbatim. Fires ONLY
     * when THIS send GREW the streak (second-or-later consecutive day):
     * same-day re-sends carry no streak, restarts stay silent (continued
     * false). The fire-condition + copy live in the pure protocol kernel
     * ([app.pulse.protocol.nudgeText]) so the JVM tests pin the web parity.
     */
    private fun maybeStreakNudge(streak: app.pulse.domain.model.StreakSnapshot?) {
        if (streak == null) return
        val dto = app.pulse.protocol.MessageStreakDto(
            count = streak.count,
            best = streak.best,
            continued = streak.continued,
        )
        dto.nudgeText()?.let { notify(it) }
    }

    /** PATCH edit - online-only by design (spec row 4); failure is an honest toast. */
    private var replyBeforeEdit: Message? = null
    private var editGeneration = 0L
    private var editSendPending = false
    private fun sendEdit(messageId: String, content: String) {
        if (editSendPending) return
        val epoch = repo.attachmentSessionKey() ?: return
        val ticket = editGeneration
        val revision = composerRevision
        editSendPending = true
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                repo.editMessage(messageId, content.trim()).getOrThrow()
                if (repo.attachmentSessionKey() == epoch && editGeneration == ticket && composerRevision == revision &&
                    _state.value.editing?.id == messageId) cancelEdit()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                if (repo.attachmentSessionKey() == epoch && editGeneration == ticket) {
                    notify(failure.message ?: "Couldn't update the message", isError = true)
                }
            } finally { editSendPending = false }
        }
    }

    fun beginEdit(message: Message) {
        if (_state.value.editing == null) replyBeforeEdit = _state.value.replyTo
        editGeneration++
        _state.value = _state.value.copy(editing = message, replyTo = null)
    }

    fun cancelEdit() {
        editGeneration++
        _state.value = _state.value.copy(editing = null, replyTo = replyBeforeEdit)
        replyBeforeEdit = null
    }

    // ── pin / save / delete / forward ───────────────────────────

    fun loadPins() {
        pinsJob?.cancel()
        pinsJob = viewModelScope.launch {
            try {
                val pins = repo.pinnedMessages(conversationId)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(pins = app.pulse.domain.sync.reconcileMessageSelection(pins, emptyList(), System.currentTimeMillis()) { it.pinnedAt != null })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notify("Couldn't refresh pinned messages", isError = true)
            }
        }
    }

    private val pinActions = mutableSetOf<String>()
    fun toggleMessagePin(messageId: String) {
        val epoch = repo.attachmentSessionKey() ?: return
        if (!pinActions.add(messageId)) return
        val selected = messages.value.firstOrNull { it.id == messageId } ?: _state.value.pins.firstOrNull { it.id == messageId }
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                if (selected?.pinnedAt != null) {
                    repo.unpinMessage(messageId)
                        .onSuccess { if (repo.attachmentSessionKey() == epoch) { notify("Message unpinned"); loadPins() } }
                        .onFailure { if (repo.attachmentSessionKey() == epoch) notify(it.message ?: "Couldn't remove the pin", isError = true) }
                } else {
                    repo.pinMessage(messageId)
                        .onSuccess { if (repo.attachmentSessionKey() == epoch) { notify(if (it.pinnedAt != null) "Message pinned" else "Message unpinned"); loadPins() } }
                        .onFailure { if (repo.attachmentSessionKey() == epoch) notify(it.message ?: "Couldn't update the pin", isError = true) }
                }
            } finally { pinActions.remove(messageId) }
        }
    }

    fun saveMessage(messageId: String) {
        val epoch = repo.attachmentSessionKey() ?: return
        val owned = repo.boundToCurrentAccount()
        viewModelScope.launch {
            if (repo.attachmentSessionKey() != epoch) return@launch
            owned.setMessageSaved(messageId, true)
                .onSuccess { if (repo.attachmentSessionKey() == epoch) notify("Saved to library") }
                .onFailure { if (repo.attachmentSessionKey() == epoch) notify(it.message ?: "Couldn't save the message", isError = true) }
        }
    }

    fun deleteMessage(messageId: String) {
        val epoch = repo.attachmentSessionKey() ?: return
        viewModelScope.launch {
            if (repo.attachmentSessionKey() != epoch) return@launch
            repo.deleteMessage(messageId)
                .onSuccess {
                    if (repo.attachmentSessionKey() != epoch) return@onSuccess
                    notify("Message deleted")
                    // The tombstone row arrives from the server truth - refetch
                    // the window so "Message deleted" renders immediately.
                    runCatching { repo.refreshMessages(conversationId) }
                }
                .onFailure { if (repo.attachmentSessionKey() == epoch) notify(it.message ?: "Couldn't delete the message", isError = true) }
        }
    }

    suspend fun forwardTo(source: Message, targetIds: List<String>, expectedOwner: String?): Map<String, String> {
        val bound = repo.boundToCurrentAccount()
        val owner = bound.viewerId
        val epoch = bound.attachmentSessionKey()
        if (owner == null || owner != expectedOwner) throw kotlinx.coroutines.CancellationException("Forward account changed")
        val failed = linkedMapOf<String, String>()
        val targets = targetIds.distinct()
        for (target in targets) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (bound.viewerId != owner || bound.attachmentSessionKey() != epoch) throw kotlinx.coroutines.CancellationException("Forward account changed")
            try {
                bound.forwardMessage(target, source).getOrThrow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                failed[target] = error.message ?: "Forward wasn't queued."
            }
        }
        val queued = targets.size - failed.size
        if (queued > 0) notify("Forward queued for $queued ${if (queued == 1) "chat" else "chats"}")
        return failed
    }

    fun react(message: Message, emoji: String) {
        val owner = repo.viewerId ?: return
        val epoch = repo.attachmentSessionKey()
        val reaction = app.pulse.protocol.reactionId(emoji)
        val present = message.reactions.none { it.userId == owner && app.pulse.protocol.reactionId(it.emoji) == reaction }
        viewModelScope.launch {
            if (repo.viewerId != owner || repo.attachmentSessionKey() != epoch) return@launch
            repo.react(message.id, reaction, present).onFailure { notify(it.message ?: "Couldn't update reaction", isError = true) }
        }
    }

    // ── R1-W2A - F-MS-24 sticker send + F-MS-23 effect send ──────────

    /** Sticker tile tap → REAL kind:"sticker" message, payload {emoji,pack}. */
    fun sendSticker(emoji: String, pack: String) {
        if (emoji.isBlank()) return
        val payload = app.pulse.protocol.PulseJson.encodeToString(
            app.pulse.protocol.StickerPayloadDto.serializer(),
            app.pulse.protocol.StickerPayloadDto(emoji = emoji, pack = pack.ifBlank { "Pulse" }),
        )
        sendRich(body = emoji, kind = "sticker", payload = payload)
    }

    /** /effects <name> [text] - effect rides the text row's payload blob. */
    fun sendEffect(effect: String, content: String, onRejected: (() -> Unit)? = null) {
        if (effect !in app.pulse.protocol.PulseWave7Logic.EFFECT_NAMES) return
        val payload = app.pulse.protocol.PulseJson.encodeToString(
            app.pulse.protocol.EffectPayloadDto.serializer(),
            app.pulse.protocol.EffectPayloadDto(effect = effect),
        )
        sendRich(
            body = content,
            kind = "text",
            payload = payload,
            // Own effect sends fire the mapped burst (web parity) - the
            // generic send burst is replaced, not stacked.
            fxKind = PulseEffects.burstKindOf(effect),
            onRejected = onRejected,
            consumeDraft = onRejected != null,
        )
    }

    fun retry() {
        cancelHistory()
        val ticket = historyRevision
        _state.value = _state.value.copy(loading = true, error = null)
        historyJob = viewModelScope.launch {
            try {
                val page = repo.historyPage(conversationId, before = null, limit = 200)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (historyRevision != ticket) return@launch
                historyCursor = page.nextCursor
                _state.value = _state.value.copy(loading = false, hasMore = page.hasMore)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (historyRevision == ticket) _state.value = _state.value.copy(loading = false, error = failure.message)
            }
        }
    }

    /** A raw page cursor survives pages containing only replies or other topics. */
    private suspend fun earlierPage(): List<Message> {
        // A sparse pin/search cache is not a consumed page. Start at the latest
        // response if no server boundary has been established yet.
        val cursor = historyCursor
        val page = repo.historyPage(conversationId, before = cursor, limit = PAGE_SIZE)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val next = page.nextCursor
        if (next != null && cursor != null && !next.precedes(cursor)) throw IllegalStateException("History returned a repeated page")
        historyCursor = next ?: cursor
        _state.value = _state.value.copy(hasMore = page.hasMore && next != null)
        return page.messages
    }

    fun loadOlder() {
        if (_state.value.loading || _state.value.loadingOlder || !_state.value.hasMore) return
        val ticket = historyRevision
        _state.value = _state.value.copy(loadingOlder = true)
        historyJob = viewModelScope.launch {
            try {
                repeat(5) {
                    val rows = earlierPage()
                    if (!_state.value.hasMore || rows.any { it.threadRootId == null &&
                            (_activeTopicId.value == null || it.topicId == _activeTopicId.value) &&
                            (it.expiresAtEpochMs?.let { expiry -> expiry > System.currentTimeMillis() } != false) }) return@launch
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (historyRevision == ticket) notify("Couldn't load older messages. Try again.", isError = true)
            } finally {
                if (historyRevision == ticket) _state.value = _state.value.copy(loadingOlder = false)
            }
        }
    }

    // A jump owns its paging task. UI recompositions must not restart it.
    fun jumpTo(messageId: String) {
        if (messageId.isBlank()) return
        cancelHistory()
        _threadJump.value = null
        _activeTopicId.value = null
        val ticket = historyRevision
        _state.value = _state.value.copy(loadingOlder = true)
        historyJob = viewModelScope.launch {
            try {
                var target = repo.observeMessages(conversationId).first().firstOrNull { it.id == messageId }
                var rounds = 0
                // A cold jump can cancel the initial load. It must seed a page
                // rather than interpreting the initial hasMore=false as exhaustion.
                if (target == null && historyCursor == null) {
                    target = earlierPage().firstOrNull { it.id == messageId }
                    rounds++
                }
                while (target == null && _state.value.hasMore && rounds < JUMP_MAX_ROUNDS) {
                    val page = earlierPage()
                    target = page.firstOrNull { it.id == messageId }
                    rounds++
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (target == null || target.isDeleted || (target.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true)) {
                    notify("Couldn't reach that message in this chat's history", isError = true)
                } else {
                    val rootId = target.threadRootId
                    if (rootId == null) flash(target.id)
                    else {
                        val (root, replies) = app.pulse.domain.sync.resolveThreadJump(repo, conversationId, rootId, target.id)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!app.pulse.domain.sync.threadRootAvailable(root, conversationId, rootId) ||
                            app.pulse.domain.sync.threadReplyTargetIndex(replies, root, target.id) == null) {
                            throw IllegalStateException("Reply unavailable")
                        }
                        _threadJump.value = ThreadJumpRequest(root.id, target.id, ticket)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (historyRevision == ticket) notify("Couldn't open that message. Try again.", isError = true)
            } finally {
                if (historyRevision == ticket) _state.value = _state.value.copy(loadingOlder = false)
            }
        }
    }

    /** Amber ring pulse on one bubble (~1.5s), then it fades and clears. */
    fun flash(messageId: String) {
        flashJob?.cancel()
        _state.value = _state.value.copy(flashMessageId = messageId, flashRevision = _state.value.flashRevision + 1)
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
        activeSearchQuery = ""
        _state.value = _state.value.copy(
            searchOpen = open,
            searchResults = emptyList(),
            searching = false,
        )
    }

    fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        activeSearchQuery = q
        if (q.length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            delay(250)
            val serverHits = runCatching { repo.searchInConversation(conversationId, q) }
                .getOrDefault(emptyList())
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (activeSearchQuery != q) return@launch
            val localHits = messages.value.filter { it.body.contains(q, ignoreCase = true) }
            val merged = (serverHits + localHits)
                .distinctBy { it.id }
                .filter { !it.isDeleted && (it.expiresAtEpochMs?.let { expiry -> expiry > System.currentTimeMillis() } != false) && it.body.contains(q, ignoreCase = true) }
                .sortedBy { it.createdAt }
            _state.value = _state.value.copy(searchResults = merged, searching = false)
        }
    }

    // A saved selection is a draft, not an upload or a send. Only Send transfers
    // its bytes into the account-owned outbox in one database transaction.
    private fun restoreAttachmentDraft() {
        val key = repo.attachmentSessionKey() ?: return
        val ticket = attachmentEpoch
        viewModelScope.launch {
            repo.loadAttachmentDraft(conversationId, key).onSuccess { draft ->
                if (draft == null || attachmentEpoch != ticket || _state.value.staged != null || repo.attachmentSessionKey() != key) return@onSuccess
                val kind = when (draft.kind) { "image" -> StagedMedia.Kind.IMAGE; "audio" -> StagedMedia.Kind.AUDIO; else -> StagedMedia.Kind.FILE }
                persistedAttachmentId = draft.selectionId
                _state.value = _state.value.copy(staged = StagedMedia(kind = kind,
                    localUri = if (kind == StagedMedia.Kind.IMAGE) draft.dataUrl else "",
                    fileName = draft.fileName, fileSize = draft.fileSize, viewOnce = draft.viewOnce,
                    selectionId = draft.selectionId, durationMs = draft.durationMs, intendedTopicId = draft.topicId,
                    saved = true, caption = draft.caption, revision = draft.revision, sessionKey = key))
            }.onFailure { notify("Saved attachment could not be restored; it has not been discarded", isError = true) }
        }
    }

    fun onImagePicked(uri: Uri) = selectAttachment(uri, StagedMedia.Kind.IMAGE)
    fun onDocumentPicked(uri: Uri) = selectAttachment(uri, StagedMedia.Kind.FILE)

    private fun selectAttachment(uri: Uri, kind: StagedMedia.Kind) {
        if (_recording.value || _state.value.staged?.kind == StagedMedia.Kind.AUDIO || _state.value.staged?.sending == true) {
            notify("Finish or discard the current recording first", isError = true)
            return
        }
        val key = repo.attachmentSessionKey() ?: run { notify("The account store is not ready", isError = true); return }
        attachmentEpoch++
        attachmentTask?.cancel()
        val selected = StagedMedia(kind = kind, localUri = uri.toString(), fileName = null,
            fileSize = null, uploading = true, sessionKey = key)
        _state.value = _state.value.copy(staged = selected)
        prepareAttachment(selected)
    }

    private fun prepareAttachment(selected: StagedMedia, autoSend: Boolean = false) {
        val id = selected.selectionId
        val key = selected.sessionKey ?: run { stageFailed(id, "The original account is no longer available"); return }
        attachmentTask = viewModelScope.launch {
            try {
                val dataUrl = when (selected.kind) {
                    StagedMedia.Kind.AUDIO -> MediaSupport.audioToDataUrl(context, File(selected.localUri)).getOrThrow()
                    StagedMedia.Kind.IMAGE -> MediaSupport.imageToDataUrl(context, Uri.parse(selected.localUri)).getOrThrow()
                    StagedMedia.Kind.FILE -> {
                        val doc = MediaSupport.documentToDataUrl(context, Uri.parse(selected.localUri)).getOrThrow()
                        if (!isActive || _state.value.staged?.selectionId != id) return@launch
                        _state.value = _state.value.copy(staged = _state.value.staged?.copy(fileName = doc.fileName, fileSize = doc.sizeBytes, mime = doc.mime))
                        doc.dataUrl
                    }
                }
                if (!isActive || _state.value.staged?.selectionId != id || repo.attachmentSessionKey() != key) return@launch
                val current = _state.value.staged ?: return@launch
                val kind = when (selected.kind) { StagedMedia.Kind.IMAGE -> "image"; StagedMedia.Kind.FILE -> "file"; StagedMedia.Kind.AUDIO -> "audio" }
                repo.saveAttachmentDraft(app.pulse.domain.model.AttachmentDraft(id, conversationId, kind, dataUrl,
                    current.fileName, current.fileSize, current.durationMs, current.caption, current.viewOnce,
                    current.intendedTopicId, current.revision), key).getOrThrow()
                if (!isActive || _state.value.staged?.selectionId != id || repo.attachmentSessionKey() != key) return@launch
                persistedAttachmentId = id
                _state.value = _state.value.copy(staged = _state.value.staged?.copy(uploading = false, saved = true, error = null))
                _state.value.staged?.let { persistAttachmentEdits(it) }
                if (selected.kind == StagedMedia.Kind.AUDIO) File(selected.localUri).delete() // The account-private journal owns the bytes now.
                if (autoSend) sendStaged(_state.value.staged?.caption.orEmpty())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (isActive) stageFailed(id, error.message ?: "Couldn't save this attachment")
            }
        }
    }

    private fun stageFailed(selectionId: String, message: String) {
        val current = _state.value.staged?.takeIf { it.selectionId == selectionId } ?: return
        _state.value = _state.value.copy(staged = current.copy(uploading = false, sending = false, error = message))
    }

    private fun persistAttachmentEdits(staged: StagedMedia) {
        val key = staged.sessionKey ?: return
        if (!staged.saved) return
        viewModelScope.launch {
            repo.editAttachmentDraft(conversationId, staged.selectionId, staged.caption, staged.viewOnce, staged.revision, key)
                .onFailure { stageFailed(staged.selectionId, "Latest attachment edits could not be saved") }
        }
    }

    fun setAttachmentCaption(value: String) {
        val staged = _state.value.staged?.takeUnless { it.sending } ?: return
        val updated = staged.copy(caption = value.take(500), revision = staged.revision + 1)
        _state.value = _state.value.copy(staged = updated)
        persistAttachmentEdits(updated)
    }

    fun retryStaged() {
        val current = _state.value.staged ?: return
        if (current.uploading || current.sending) return
        if (current.saved) {
            _state.value = _state.value.copy(staged = current.copy(error = null))
            persistAttachmentEdits(current)
            return
        }
        attachmentTask?.cancel()
        _state.value = _state.value.copy(staged = current.copy(uploading = true, error = null))
        prepareAttachment(current)
    }

    fun cancelStaged() {
        val current = _state.value.staged?.takeUnless { it.sending } ?: return
        val key = current.sessionKey ?: return
        attachmentEpoch++
        attachmentTask?.cancel(); attachmentTask = null
        val previous = persistedAttachmentId
        _state.value = _state.value.copy(staged = current.copy(sending = true))
        viewModelScope.launch {
            try {
                repo.discardAttachmentDraft(conversationId, current.selectionId, key).getOrThrow()
                if (previous != null && previous != current.selectionId) repo.discardAttachmentDraft(conversationId, previous, key).getOrThrow()
                if (current.kind == StagedMedia.Kind.AUDIO && current.localUri.isNotEmpty()) File(current.localUri).delete()
                persistedAttachmentId = null
                if (_state.value.staged?.selectionId == current.selectionId) _state.value = _state.value.copy(staged = null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                stageFailed(current.selectionId, "Attachment could not be discarded")
            }
        }
    }

    fun sendStaged(caption: String) {
        val staged = _state.value.staged ?: return
        if (staged.sending) return
        val key = staged.sessionKey ?: return
        if (!staged.saved || staged.uploading) {
            notify("Wait for this attachment to be saved on the device", isError = true)
            return
        }
        attachmentEpoch++
        val topicId = if (staged.kind == StagedMedia.Kind.AUDIO) staged.intendedTopicId else _activeTopicId.value
        _state.value = _state.value.copy(staged = staged.copy(sending = true, error = null))
        viewModelScope.launch {
            try {
                val sent = repo.queueAttachmentDraft(conversationId, staged.selectionId, caption.trim(), staged.viewOnce, topicId, key).getOrThrow()
                if (_state.value.staged?.selectionId == staged.selectionId) {
                    persistedAttachmentId = null
                    _state.value = _state.value.copy(staged = null)
                }
                afterOwnSend(sent)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                stageFailed(staged.selectionId, error.message ?: "Attachment could not be queued")
            }
        }
    }

    fun setStagedViewOnce(value: Boolean) {
        val staged = _state.value.staged?.takeIf { it.kind == StagedMedia.Kind.IMAGE && !it.sending } ?: return
        val updated = staged.copy(viewOnce = value, revision = staged.revision + 1)
        _state.value = _state.value.copy(staged = updated)
        persistAttachmentEdits(updated)
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
     * D31 hold-to-record - called the moment the mic button receives a press
     * (RECORD_AUDIO is requested at the UI layer BEFORE this call).
     * MediaRecorder → AAC in MPEG_4, max 10 min (the server durationMs cap),
     * temp file cacheDir/voice-<uuid>.m4a. The 100ms ticker drives the timer
     * (wall-clock honest, no drift), samples maxAmplitude into
     * [_recordAmps] for the live waveform and AUTO-SENDS the take when the
     * [PulseMedia.MAX_VOICE_MS] ceiling is reached mid-hold.
     */
    fun startRecording() {
        if (_recording.value || _sendingVoice.value || _state.value.staged != null) return
        val attachmentKey = repo.attachmentSessionKey() ?: return
        if (!app.pulse.core.audio.AudioOwnership.shared.acquireExclusive(recorderAudioToken)) {
            notify("Leave your live room/call or pause playback before recording", isError = true)
            return
        }
        val file = File(context.cacheDir, "voice-${java.util.UUID.randomUUID()}.m4a")
        val mr = try { MediaRecorder() } catch (_: Exception) {
            app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
            notify("Microphone unavailable", isError = true)
            return
        }
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioChannels(1)
            // 10 minutes at 32 kbps is ~2.4 MB, below the gateway media cap.
            mr.setAudioEncodingBitRate(32_000)
            mr.setMaxDuration(600_000)
            // D31 - metering: mr.maxAmplitude reports the raw 0..32767 range
            // while held (the sandbox platform jar omits setMaxAmplitude -
            // the ceiling call is a no-op re-scale, dropped honestly).
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (t: Throwable) {
            runCatching { mr.release() }
            app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
            file.delete()
            notify("Couldn't start recording - check microphone access", isError = true)
            return
        }
        recorder = mr
        recordingAttachmentSession = attachmentKey
        recordFile = file
        recordStartedAtMs = SystemClock.elapsedRealtime()
        _recordMs.value = 0L
        _recordAmps.value = emptyList()
        _recording.value = true
        recordTicker?.cancel()
        recordTicker = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val elapsed = if (recordStartedAtMs > 0) SystemClock.elapsedRealtime() - recordStartedAtMs else _recordMs.value
                _recordMs.value = elapsed
                // Live waveform sample - maxAmplitude is the peak since the
                // last call, which IS the correct per-tick reading.
                val mr = recorder
                if (mr != null) {
                    val amp = runCatching { PulseMedia.normalizeRecordAmplitude(mr.maxAmplitude) }.getOrDefault(0f)
                    _recordAmps.value = (_recordAmps.value + amp).takeLast(PulseMedia.RECORD_WAVEFORM_BARS)
                }
                // Hold reached the wire cap (server refuses durationMs > 600000):
                // send what we have instead of recording a take that would 400.
                if (elapsed >= PulseMedia.MAX_VOICE_MS) {
                    stopAndSend()
                    break
                }
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
        _recordAmps.value = emptyList()
    }

    /**
     * Stop → elapsed → `durationMs = max(1, round(ms/100)*100)` (web parity).
     * Under 600ms the note is DISCARDED with a notice. Never queued: upload
     * dataURL → repo.uploadMedia → sendMediaMessage(kind="audio") - kind MUST
     * be "audio" so /transcribe works (spec §1 row 1, web kind-omission bug).
     */
    fun stopAndSend() {
        if (!_recording.value) return
        val elapsedMs = if (recordStartedAtMs > 0) SystemClock.elapsedRealtime() - recordStartedAtMs else _recordMs.value
        val attachmentKey = recordingAttachmentSession
        val finalized = stopRecorderLocked()
        val file = recordFile
        recordFile = null
        _recording.value = false
        _recordMs.value = 0L
        _recordAmps.value = emptyList()
        if (!finalized) {
            file?.delete()
            notify("Recording could not be finalized. Please record again.", isError = true)
            return
        }
        if (elapsedMs < app.pulse.core.media.PulseMedia.MIN_VOICE_MS || file == null) {
            file?.delete()
            notify("Too short - voice note discarded")
            return
        }
        val durationMs = app.pulse.core.media.PulseMedia.voiceDurationMs(elapsedMs)
        // Keep the source while an upload is uncertain; expose the same staged
        // retry/discard controls as a file. Delete only after durable enqueue.
        val selected = StagedMedia(kind = StagedMedia.Kind.AUDIO, localUri = file.absolutePath,
            fileName = "Voice note.m4a", fileSize = file.length(), mime = "audio/mp4",
            uploading = true, durationMs = durationMs, intendedTopicId = _activeTopicId.value,
            sessionKey = attachmentKey)
        attachmentEpoch++
        _state.value = _state.value.copy(staged = selected)
        prepareAttachment(selected, autoSend = true)
    }

    private fun stopRecorderLocked(): Boolean {
        recordingAttachmentSession = null
        recordTicker?.cancel()
        recordTicker = null
        val mr = recorder ?: return true
        recorder = null
        recordStartedAtMs = 0
        val finalized = runCatching { mr.stop() }.isSuccess
        runCatching { mr.release() }
        app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
        return finalized
    }

    // ── Wave 2: transcription strip (spec §1 row 15) ───────────────────

    /** "Transcribe" pill action - 422/502 surface the honest toast. */
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

    /** Render only after this request receives a one-time grant. */
    suspend fun consumeViewOnce(message: Message, expectedViewerId: String?): Message? {
        val bound = repo.boundToCurrentAccount()
        if (expectedViewerId == null || bound.viewerId != expectedViewerId || message.conversationId != conversationId) return null
        val granted = bound.consumeViewOnce(message.id)
            .onFailure { notify(it.message ?: "Couldn't open this photo", isError = true) }.getOrNull()
        return granted.takeIf { bound.viewerId == expectedViewerId && repo.viewerId == expectedViewerId }
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

    /** Explicit single-choice selection; server owns open/closed availability. */
    fun votePoll(pollId: String, optionId: String) {
        val owner = repo.viewerId
        val epoch = repo.attachmentSessionKey()
        viewModelScope.launch {
            if (owner == null || repo.viewerId != owner || repo.attachmentSessionKey() != epoch) return@launch
            repo.votePoll(pollId, optionId)
                .onFailure { notify(it.message ?: "Couldn't record your vote", isError = true) }
        }
    }

    fun closePoll(pollId: String) {
        val owner = repo.viewerId
        val epoch = repo.attachmentSessionKey()
        viewModelScope.launch {
            if (owner == null || repo.viewerId != owner || repo.attachmentSessionKey() != epoch) return@launch
            repo.closePoll(pollId)
                .onSuccess { notify("Voting closed - results are final") }
                .onFailure { notify(it.message ?: "Couldn't close the poll", isError = true) }
        }
    }

    // ── Wave 2: topics (spec §1 row 9) ─────────────────────────────────

    /** Rail re-tick - UI runs this on open + every 15s + after own sends. */
    fun refreshTopics() {
        viewModelScope.launch { runCatching { repo.refreshTopics(conversationId) } }
    }

    /**
     * null → General (whole room, unfiltered); non-null → also fetch the
     * server window for that topic (native keeps the client-side filter live
     * via the Room flow - improvement over the web 3.5s cache poll).
     */
    fun setActiveTopic(topicId: String?) {
        cancelHistory()
        _threadJump.value = null
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
                    notify("Filing to $emoji ${topic.name} - next send lands there")
                }
                .onFailure { notify("Couldn't create the topic", isError = true) }
        }
    }

    // ── Wave 7 - collaboration & hub (F-RO-02…09) ──────────────────────

    val viewerId: String get() = repo.viewerId ?: ""

    /** True when THIS room is a group/channel (tournaments + leaderboard are group-only). */
    val isGroup: Boolean get() = conversation.value?.isGroupish == true

    /** Room admin? (channels: role from myRole; groups: creator heuristic on web = admins list) */
    fun isAdmin(): Boolean = channelRole.value == "admin"

    /** Sheet host bus - one flag per surface, all exclusive. */
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

    /** Non-surface helper - routes into the room snackbar. */
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

    // ── R6 - BE7: room-header reminders badge (web header button + count) ──
    /** Upcoming (not fired, remindAt in the future) reminder count for the header badge. */
    private val _remindersUpcoming = MutableStateFlow(0)
    val remindersUpcoming: StateFlow<Int> = _remindersUpcoming.asStateFlow()

    fun loadRemindersUpcoming() {
        viewModelScope.launch {
            val page = runCatching { repo.reminders(dueOnly = false).getOrNull() }.getOrNull()
            val now = System.currentTimeMillis()
            _remindersUpcoming.value = page?.items.orEmpty().count { item ->
                item.firedAt == null && PulseTime.epochMs(item.remindAt) > now
            }
        }
    }

    /** POST /api/redpackets - honest 402/400 copy surfaces verbatim. */
    fun createRedPacket(total: Long, count: Int, note: String?) {
        viewModelScope.launch {
            repo.createRedPacket(conversationId, total, count, note)
                .onSuccess {
                    redPacketOpen = false
                    notify("Red packet sent - $total PC in $count grabs")
                }
                .onFailure { notify(it.message ?: "Could not send the red packet", isError = true) }
        }
    }

    suspend fun createGame(opponentId: String?): Boolean =
        gameRequest { repo.createGame(conversationId, opponentId).getOrThrow() }
            .onSuccess { gameOpen = false; notify(if (it.replayed) "Recovered the previous challenge" else "Tic-tac-toe challenge sent") }
            .onFailure { notify(it.message ?: "Could not start the game", isError = true) }.isSuccess

    // ── R7 item 3 - game rematch (web game-tictactoe-card.tsx:213-236) ──
    /**
     * Finished match + I'm a player → POST the SAME create flow the attach
     * sheet uses against the ORIGINAL opponent (mySide == 'X' → playerOId,
     * else playerXId). The fresh challenge message rides the normal message
     * stream (repo.createGame upserts the response message - the Android
     * analogue of web's GAME_EXTERNAL_MESSAGE_EVENT hand-off). Toast copy
     * verbatim; the card disables itself while the POST is in flight.
     */
    suspend fun rematch(match: app.pulse.protocol.GameMatchDto): Boolean {
        if (match.status == "active") return false
        val mySide = app.pulse.protocol.PulseWave7Logic.sideOf(match, viewerId) ?: return false
        val opponentId = if (mySide == 'X') match.playerOId else match.playerXId
        if (opponentId.isNullOrBlank()) return false
        return gameRequest { repo.createGame(match.conversationId.ifBlank { conversationId }, opponentId).getOrThrow() }
            .onSuccess { notify(if (it.replayed) "Recovered the previous rematch" else "Rematch sent - new challenge in the chat.") }
            .onFailure { notify(it.message ?: "Rematch failed - try again.", isError = true) }.isSuccess
    }

    suspend fun createTournament(name: String): Boolean =
        gameRequest { repo.createTournament(conversationId, name.trim()).getOrThrow() }
            .onSuccess { tournamentOpen = false; notify(if (it.replayed) "Recovered the previous tournament" else "Tournament \"$name\" started") }
            .onFailure { notify(it.message ?: "Could not start the tournament", isError = true) }.isSuccess

    suspend fun createKanbanCard(title: String, column: String, assigneeId: String?): Boolean {
        val source = kanbanSourceMessage
        val accepted = confirmedRoomAction("board:create:$conversationId") { owned ->
            val card = owned.createKanbanCard(conversationId, title.trim(), column, assigneeId, source).getOrThrow()
            "Task added to the board: ${card.title}"
        }
        if (accepted && kanbanSourceMessage == source) { kanbanSourceMessage = null; kanbanSourceTitle = null }
        return accepted
    }

    /** Message long-press → "Add to board" (web parity: message→card title cap 80). */
    fun addMessageToBoard(messageId: String, content: String) {
        kanbanSourceMessage = messageId
        kanbanSourceTitle = content
        kanbanOpen = true
    }

    // ── R3-B item 6 - web "Convert to task" (text messages) ──────────────
    /**
     * Web chat-room.tsx:2100-2109 - the long-press action POSTs
     * { userId, messageId } to /api/conversations/{id}/kanban and the SERVER
     * derives the card title from the message (first 80 chars, route.ts:172-190).
     * Toast copy verbatim: 'Task created from message' / 'Could not create
     * the task'. Busy while the round-trip runs (the sheet row spins).
     */
    private val _taskPending = MutableStateFlow(false)
    val taskPending: StateFlow<Boolean> = _taskPending.asStateFlow()

    fun convertMessageToTask(messageId: String) {
        if (_taskPending.value) return
        val epoch = repo.attachmentSessionKey() ?: return
        _taskPending.value = true
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                confirmedRoomAction("board:source:$messageId") { owned ->
                    owned.createKanbanCard(conversationId, null, null, null, messageId).getOrThrow()
                    "Task created from message"
                }
            } finally { _taskPending.value = false }
        }
    }

    // suspend fetchers used by the in-bubble cards (polling parity)

    suspend fun gameDetail(matchId: String): GameDetailDto? =
        repo.boundToCurrentAccount().game(matchId).getOrThrow()

    suspend fun gameMove(matchId: String, cell: Int): GameDetailDto? =
        gameRequest { repo.gameMove(matchId, cell).getOrThrow() }
            .onFailure { notify(it.message ?: "Move rejected", isError = true) }.getOrNull()

    suspend fun joinGame(matchId: String): GameDetailDto? =
        gameRequest { repo.joinGame(matchId).getOrThrow() }
            .onFailure { notify(it.message ?: "Could not join", isError = true) }.getOrNull()

    suspend fun redPacketDetail(packetId: String): RedPacketDetailDto? =
        runCatching { repo.redPacket(packetId).getOrNull() }.getOrNull()

    /** Atomic grab - 409 copy (own packet / already grabbed / race) surfaces verbatim. */
    fun grabRedPacket(packetId: String) {
        viewModelScope.launch {
            repo.grabRedPacket(packetId)
                .onSuccess { notify(if (it.alreadyGrabbed) "Already received ${it.amount} PC" else "You grabbed ${it.amount} PC") }
                .onFailure { notify(it.message ?: "Could not grab the red packet", isError = true) }
        }
    }

    suspend fun tournamentDetail(tournamentId: String): TournamentSummaryDto? =
        repo.boundToCurrentAccount().tournament(tournamentId).getOrThrow()

    suspend fun joinTournament(tournamentId: String): Boolean =
        gameRequest { repo.joinTournament(tournamentId).getOrThrow() }
            .onSuccess { notify("You are in - play tic-tac-toe matches to score points") }
            .onFailure { notify(it.message ?: "Could not join the tournament", isError = true) }.isSuccess

    suspend fun finishTournament(tournamentId: String): Boolean =
        gameRequest { repo.finishTournament(tournamentId).getOrThrow() }
            .onSuccess { notify("Tournament finished") }
            .onFailure { notify(it.message ?: "Could not finish the tournament", isError = true) }.isSuccess

    /** Reminder create via the sheet (absolute picker) - relative parse lives in PulseWave7Logic. */
    suspend fun createReminder(note: String, remindAtIso: String, anchoredMessageId: String? = null): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val epoch = repo.attachmentSessionKey() ?: return false
        val account = repo.boundToCurrentAccount()
        return try {
            account.createReminder(conversationId, anchoredMessageId, note, remindAtIso).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repo.attachmentSessionKey() != epoch) return false
            loadRemindersUpcoming()
            true // The submitting sheet, not an unrelated later screen, owns draft cleanup.
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
            if (repo.attachmentSessionKey() == epoch) notify(error.message ?: "Could not set the reminder", isError = true)
            false
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

    suspend fun kanbanBoard(conversationId: String): KanbanPageDto? {
        val epoch = repo.attachmentSessionKey() ?: return null
        val owned = repo.boundToCurrentAccount()
        return try {
            val page = owned.kanbanBoard(conversationId).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repo.attachmentSessionKey() == epoch) page else null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }

    suspend fun moveKanbanCard(cardId: String, column: String, position: Long?): Boolean =
        confirmedRoomAction("board:$cardId") { owned ->
            owned.updateKanbanCard(cardId, null, column, null, false, position).getOrThrow()
            null
        }

    suspend fun deleteKanbanCard(cardId: String): Boolean = confirmedRoomAction("board:$cardId") { owned ->
        owned.deleteKanbanCard(cardId).getOrThrow()
        "Task deleted."
    }

    suspend fun whiteboard(conversationId: String, since: Long?): WhiteboardPageDto? =
        runCatching { repo.whiteboard(conversationId, since).getOrNull() }.getOrNull()

    /**
     * R2-C item 4 - the flush now returns the server VERDICT: the sheet
     * purges the durable draft only when the POST succeeded (strokes stay
     * pending through failures and re-sync on the next open), and failures
     * surface the honest toast.
     */
    suspend fun postWhiteboardStrokes(conversationId: String, strokes: List<WhiteboardStrokePostDto>): Boolean =
        repo.postWhiteboardStrokes(conversationId, strokes)
            .onFailure { notify(it.message ?: "Stroke upload failed - try again", isError = true) }
            .isSuccess

    // ── R2-C item 4 - durable whiteboard draft (per-conversation prefs) ──

    suspend fun whiteboardDraft(conversationId: String): List<WhiteboardStrokePostDto> =
        prefsStore.whiteboardDraft(conversationId)

    fun appendWhiteboardDraft(conversationId: String, stroke: WhiteboardStrokePostDto) {
        viewModelScope.launch { prefsStore.appendWhiteboardDraft(conversationId, stroke) }
    }

    fun dropFirstWhiteboardDraft(conversationId: String, count: Int) {
        viewModelScope.launch { prefsStore.dropFirstWhiteboardDraft(conversationId, count) }
    }

    fun dropLastWhiteboardDraft(conversationId: String) {
        viewModelScope.launch { prefsStore.dropLastWhiteboardDraft(conversationId) }
    }

    fun replaceAllWhiteboardDraft(conversationId: String, strokes: List<WhiteboardStrokePostDto>) {
        viewModelScope.launch { prefsStore.replaceAllWhiteboardDraft(conversationId, strokes) }
    }

    fun clearWhiteboardDraft(conversationId: String) {
        viewModelScope.launch { prefsStore.clearWhiteboardDraft(conversationId) }
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

    suspend fun events(conversationId: String): List<GroupEventDto>? {
        val epoch = repo.attachmentSessionKey() ?: return null
        val owned = repo.boundToCurrentAccount()
        return try {
            val page = owned.events(conversationId).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repo.attachmentSessionKey() == epoch) page.events else null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { null }
    }

    private val roomResourceActions = mutableSetOf<Pair<String, String>>()
    private suspend fun confirmedRoomAction(key: String, operation: suspend (PulseRepository) -> String?): Boolean {
        val epoch = repo.attachmentSessionKey() ?: return false
        val owned = repo.boundToCurrentAccount()
        val ticket = epoch to key
        if (!roomResourceActions.add(ticket)) return false
        return try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val success = operation(owned)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repo.attachmentSessionKey() != epoch) return false
            success?.let { notify(it) }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (repo.attachmentSessionKey() == epoch) notify(error.message ?: "Could not update this item.", isError = true)
            false
        } finally { roomResourceActions.remove(ticket) }
    }

    suspend fun createEvent(title: String, startsAtIso: String, description: String?, location: String?): Boolean =
        confirmedRoomAction("create:$conversationId") { owned ->
            owned.createEvent(conversationId, title, startsAtIso, description, location).getOrThrow()
            "Event scheduled - see you there"
        }

    suspend fun rsvp(eventId: String, status: String): Boolean = confirmedRoomAction(eventId) { owned ->
        owned.rsvpEvent(eventId, status).getOrThrow()
        null
    }

    suspend fun checkin(eventId: String): Boolean = confirmedRoomAction(eventId) { owned ->
        val receipt = owned.checkinEvent(eventId).getOrThrow()
        when {
            receipt.alreadyCheckedIn -> "Already checked in."
            receipt.xpAwarded -> "Checked in - see you there · +15 XP"
            else -> "Checked in - see you there"
        }
    }

    suspend fun deleteEvent(eventId: String): Boolean = confirmedRoomAction(eventId) { owned ->
        owned.deleteEvent(eventId).getOrThrow()
        "Event deleted."
    }

    suspend fun reminders(): RemindersPageDto? =
        runCatching { repo.reminders(dueOnly = false).getOrNull() }.getOrNull()

    fun resolveReminder(reminderId: String) {
        viewModelScope.launch {
            repo.resolveReminder(reminderId)
                .onSuccess { loadRemindersUpcoming() } // R6 - BE7: badge refresh
                .onFailure { notify(it.message ?: "Couldn't resolve the reminder", isError = true) }
        }
    }

    fun deleteReminder(reminderId: String) {
        viewModelScope.launch {
            repo.deleteReminder(reminderId)
                .onSuccess {
                    notify("Reminder canceled")
                    loadRemindersUpcoming() // R6 - BE7: badge refresh
                }
                .onFailure { notify(it.message ?: "Could not cancel the reminder", isError = true) }
        }
    }

    suspend fun leaderboard(conversationId: String?): LeaderboardPageDto? =
        runCatching { repo.leaderboard(conversationId).getOrNull() }.getOrNull()

    // ── R1-W2A - quick phrases (F-MS-29) ──────────────────────────────

    private val _phrases = MutableStateFlow<List<app.pulse.domain.model.QuickPhrase>>(emptyList())
    val phrases: StateFlow<List<app.pulse.domain.model.QuickPhrase>> = _phrases.asStateFlow()

    private val _phrasesBusy = MutableStateFlow(false)
    val phrasesBusy: StateFlow<Boolean> = _phrasesBusy.asStateFlow()

    // R6 - M2: public (was private + zero callers → the manage sheet could
    // never open). Hosted by ChatRoomScreen beside the other sheet flags.
    var phrasesOpen by androidx.compose.runtime.mutableStateOf(false)

    fun openPhrases() { phrasesOpen = true }
    fun closePhrases() { phrasesOpen = false }

    private var phraseReadRevision = 0L
    private var phraseReadJob: kotlinx.coroutines.Job? = null

    /** Failed refreshes preserve confirmed chips; pre-mutation reads cannot undo writes. */
    fun loadPhrases() {
        if (_phrasesBusy.value) return
        val epoch = repo.attachmentSessionKey() ?: return
        val owner = repo.viewerId ?: return
        val target = repo.boundToCurrentAccount()
        if (target.viewerId != owner) return
        val ticket = ++phraseReadRevision
        phraseReadJob?.cancel()
        phraseReadJob = viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch || repo.viewerId != owner) return@launch
                val fetched = target.phrases().getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() == epoch && ticket == phraseReadRevision) _phrases.value = fetched
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch && ticket == phraseReadRevision) notify(error.message ?: "Couldn't refresh quick phrases", isError = true)
            }
        }
    }

    fun addPhrase(text: String, confirmed: () -> Unit = {}) {
        val trimmed = text.trim()
        val epoch = repo.attachmentSessionKey() ?: return
        if (trimmed.isEmpty() || trimmed.length > 120 || _phrasesBusy.value) return
        _phrasesBusy.value = true; phraseReadRevision++; phraseReadJob?.cancel()
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                val phrase = repo.addPhrase(trimmed).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() != epoch) return@launch
                _phrases.value = _phrases.value.filterNot { it.id == phrase.id } + phrase
                confirmed()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) notify(error.message ?: "Couldn't add the phrase", isError = true)
            } finally { if (repo.attachmentSessionKey() == epoch) _phrasesBusy.value = false }
        }
    }

    fun deletePhrase(phraseId: String) {
        val epoch = repo.attachmentSessionKey() ?: return
        if (_phrasesBusy.value) return
        _phrasesBusy.value = true; phraseReadRevision++; phraseReadJob?.cancel()
        viewModelScope.launch {
            try {
                if (repo.attachmentSessionKey() != epoch) return@launch
                repo.deletePhrase(phraseId).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.attachmentSessionKey() == epoch) _phrases.value = _phrases.value.filterNot { it.id == phraseId }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) notify(error.message ?: "Couldn't delete the phrase", isError = true)
            } finally { if (repo.attachmentSessionKey() == epoch) _phrasesBusy.value = false }
        }
    }

    // ── R2-A item 5 - AI recap (web chat-room.tsx:2178-2208) ─────────

    /** Live recap card content - null = no card (web `recap` state parity). */
    data class RecapUi(val text: String, val basedOn: Int)

    private val _recap = MutableStateFlow<RecapUi?>(null)
    val recap: StateFlow<RecapUi?> = _recap.asStateFlow()

    private val _recapLoading = MutableStateFlow(false)
    val recapLoading: StateFlow<Boolean> = _recapLoading.asStateFlow()

    fun consumeRecap() {
        _recap.value = null
    }

    /**
     * Header-menu / palette /recap entry - POST /api/ai/recap { userId,
     * conversationId }. Gate: real activity only (≥5 live messages, web
     * requestRecap parity). Failures surface the server's honest copy.
     */
    fun requestRecap() {
        if (_recapLoading.value) return
        val liveCount = messages.value.count { !it.isDeleted }
        if (liveCount < RECAP_MIN_MESSAGES) {
            notify("Recap needs at least $RECAP_MIN_MESSAGES messages in this chat")
            return
        }
        _recapLoading.value = true
        viewModelScope.launch {
            val outcome: Result<app.pulse.protocol.AiRecapDto> = repo.aiRecap(conversationId)
            outcome
                .onSuccess { dto -> _recap.value = RecapUi(text = dto.recap, basedOn = dto.basedOn) }
                .onFailure { failure ->
                    val raw = (failure as? PulseApiException)?.message
                    val copy = raw?.let { m -> m.substringAfter(": ").takeIf { t -> t.isNotBlank() && t != m } }
                    notify(copy ?: "Recap is unavailable right now", isError = true)
                }
            _recapLoading.value = false
        }
    }

    // ── R1-W2F - F-MD-06 translation ─────────────────────────────────

    /** The message whose LLM translation is in flight (menu row → spinner). */
    private val _translatingId = MutableStateFlow<String?>(null)
    val translatingId: StateFlow<String?> = _translatingId.asStateFlow()

    /**
     * messageId → translated text - conversationId-INDEPENDENT (the server
     * persists per language; this map is the session's render cache).
     * Re-translating a message overwrites its entry.
     */
    private val _translated = MutableStateFlow<Map<String, String>>(emptyMap())
    val translated: StateFlow<Map<String, String>> = _translated.asStateFlow()

    /**
     * Long-press "Translate" → POST /api/messages/{id}/translate {userId}.
     * One LLM round-trip at a time; failures surface the server's honest copy
     * verbatim ("Deleted messages cannot be translated.", 403 non-participant…).
     */
    fun translateMessage(messageId: String) {
        if (messageId.isBlank() || messageId.startsWith(TEMP_MESSAGE_PREFIX)) return
        if (_translatingId.value != null) return
        _translatingId.value = messageId
        viewModelScope.launch {
            val outcome: Result<String> = repo.translateMessage(messageId)
            outcome
                .onSuccess { text ->
                    _translated.value = _translated.value + (messageId to text)
                }
                .onFailure { failure ->
                    // Strip the typed "KIND: " prefix when there is one; a bare
                    // transport error keeps the generic copy.
                    val raw = (failure as? PulseApiException)?.message
                    val copy = raw?.let { m -> m.substringAfter(": ").takeIf { t -> t.isNotBlank() && t != m } }
                    notify(copy ?: "Translation unavailable", isError = true)
                }
            _translatingId.value = null
        }
    }

    // ── R1-W2F - F-MD-07 location share ──────────────────────────────

    /** Live one-shot fix the share sheet renders (locating → fix / failure). */
    private val _locationFix = MutableStateFlow(LocationFix())
    val locationFix: StateFlow<LocationFix> = _locationFix.asStateFlow()

    /** Registered one-shot update listener - removed on first fix / timeout. */
    private var locationListener: android.location.LocationListener? = null

    /**
     * One-shot location read - NO continuous tracking (a static pin needs no
     * background location, web/iOS parity). Provider priority NETWORK → GPS →
     * PASSIVE: coarse is fine for a pin (spec F-MD-07 PERMS). The last-known
     * reading seeds the sheet immediately so it never renders empty while the
     * fresh fix lands.
     */
    fun requestLocationFix() {
        if (_locationFix.value.locating) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm == null) {
            _locationFix.value = LocationFix(failed = true)
            return
        }
        val provider = listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (provider == null) {
            _locationFix.value = LocationFix(failed = true)
            return
        }
        val seed = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        _locationFix.value = LocationFix(locating = true, lat = seed?.latitude, lng = seed?.longitude)
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                _locationFix.value = LocationFix(
                    locating = false,
                    lat = location.latitude,
                    lng = location.longitude,
                )
                cancelLocationFix()
            }
            // Pre-API 30 runtimes declare these abstract - explicit overrides
            // keep old devices from throwing AbstractMethodError on status flips.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        val requested = runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
        }.isSuccess
        if (!requested) {
            _locationFix.value = LocationFix(failed = true)
            return
        }
        locationListener = listener
        // Honesty timeout - a cold GPS can stall forever; give up after 12 s
        // (a stale seed may already be showing; Retry re-runs the request).
        viewModelScope.launch {
            delay(LOCATION_FIX_TIMEOUT_MS)
            val current = _locationFix.value
            if (current.locating && current.lat == null) {
                cancelLocationFix()
                _locationFix.value = LocationFix(failed = true)
            }
        }
    }

    /** Detach the one-shot listener (also the onCleared hygiene). */
    fun cancelLocationFix() {
        locationListener?.let { listener ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            runCatching { lm?.removeUpdates(listener) }
        }
        locationListener = null
        if (_locationFix.value.locating) {
            _locationFix.value = _locationFix.value.copy(locating = false)
        }
    }

    /**
     * Confirm-sheet → REAL kind:"location" message, payload {lat,lng,label}
     * (web sendLocation parity: content rides EMPTY - the label lives in the
     * payload blob; wire kind whitelist text|image|audio|sticker|location|file).
     */
    fun sendLocation(lat: Double, lng: Double, label: String) {
        val resolved = label.trim().take(80).ifBlank { "Current location" }
        val payload = app.pulse.protocol.PulseJson.encodeToString(
            app.pulse.domain.model.LocationPayload.serializer(),
            app.pulse.domain.model.LocationPayload(lat = lat, lng = lng, label = resolved),
        )
        sendRich(body = "", kind = "location", payload = payload)
    }

    // ── R1-W2F - F-FX-05 per-conversation themes ─────────────────────

    /** Live `chat.convThemes` overrides (LRU-capped at 48 in the prefs store). */
    val convThemes: StateFlow<Map<String, ConvTheme>> = repo.convThemes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** This room's override - null = follows the global Appearance default. */
    val convTheme: ConvTheme? get() = convThemes.value[conversationId]

    /** Wallpaper the room renders: override ?? global prefs (web effectiveConvWallpaper). */
    val effectiveWallpaper: String
        get() = convTheme?.wallpaper ?: prefs.value.wallpaper ?: "none"

    /** Live-apply one swatch tap (web ConvThemePicker commit parity, local prefs). */
    fun applyConvTheme(wallpaper: String, tint: String?) {
        if (wallpaper !in ConvTheme.WALLPAPERS) return
        if (tint != null && tint !in ConvTheme.TINTS) return
        viewModelScope.launch {
            runCatching { repo.setConvTheme(conversationId, ConvTheme(wallpaper = wallpaper, tint = tint)) }
        }
    }

    /** Drop the override - the room falls back to the global default. */
    fun clearConvTheme() {
        viewModelScope.launch { runCatching { repo.setConvTheme(conversationId, null) } }
    }

    // ── R1-W2A - D34 verified badge (quiet header state, NO sheet) ────

    /** DM-peer verification state for the header badge (null = unknown/loading). */
    private val _peerVerified = MutableStateFlow<Boolean?>(null)
    val peerVerified: StateFlow<Boolean?> = _peerVerified.asStateFlow()

    /**
     * Quiet safety fetch for the D34 header badge - deliberately does NOT
     * open the safety sheet (that is loadSafety's job when the badge is tapped).
     */
    fun loadPeerVerification(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            val outcome: Result<SafetyState> = repo.safetyState(peerId)
            _peerVerified.value = outcome.getOrNull()?.verified
        }
    }

    // ── R1-W2A - D29 incoming effect FX + rich sends ──────────────────

    override fun onCleared() {
        // Recording must never outlive the room - stop + release + delete.
        stopRecorderLocked()
        recordFile?.delete()
        recordFile = null
        _recording.value = false
        // R1-W2F - the one-shot location listener must never outlive the room.
        cancelLocationFix()
        voicePlayer.release()
        super.onCleared()
    }

    companion object {
        /** Older-page size - spec §1.1 pagination cursor rhythm. */
        const val PAGE_SIZE = 40

        /** Bounded jump-window expansion - web parity (≤14 before= rounds). */
        const val JUMP_MAX_ROUNDS = 14

        /** R1-W2F - one-shot location fix honesty timeout (web geolocation 9s). */
        const val LOCATION_FIX_TIMEOUT_MS = 12_000L

        /** R2-A item 5 - recap activity gate (web requestRecap: liveCount < 5). */
        const val RECAP_MIN_MESSAGES = 5

        /** Web parity unfurl hint moved to PulseMedia.isUnfurlCandidate (JVM-pinned). */
    }
}

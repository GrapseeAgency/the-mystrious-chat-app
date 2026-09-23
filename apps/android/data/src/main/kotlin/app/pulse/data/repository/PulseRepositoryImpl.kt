package app.pulse.data.repository

import android.content.Context
import android.util.Log
import app.pulse.core.result.PulseResult
import app.pulse.core.time.PulseTime
import app.pulse.data.local.CallLogDao
import app.pulse.data.local.CallLogCacheEntity
import app.pulse.data.local.CallLogQueueEntity
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.ConversationEntity
import app.pulse.data.local.DraftDao
import app.pulse.data.local.DraftEntity
import app.pulse.data.local.MessageDao
import app.pulse.data.local.MessageEntity
import app.pulse.data.local.OutboxDao
import app.pulse.data.local.OutboxEntity
import app.pulse.data.local.PulsePrefsLocalStore
import app.pulse.data.local.SavedDao
import app.pulse.data.local.SessionTokenStore
import app.pulse.data.local.StoryCacheEntity
import app.pulse.data.local.Wave7CacheEntity
import app.pulse.data.local.StoryDao
import app.pulse.data.local.SavedMessageEntity
import app.pulse.data.local.TopicDao
import app.pulse.data.local.TopicEntity
import app.pulse.data.local.toInfo
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.model.BlockedAccount
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.CallStatus
import app.pulse.domain.model.Channel
// R1-W2F — per-conversation themes (F-FX-05).
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.GroupLeave
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.InviteJoinOutcome
import app.pulse.domain.model.InvitePreview
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.MentionItem
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.Reaction
import app.pulse.domain.model.SavedItem
import app.pulse.domain.model.SafetyState
import app.pulse.domain.model.ScheduledItem
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.model.StoryUser
import app.pulse.domain.model.StoryViewer
import app.pulse.domain.model.Topic
import app.pulse.domain.model.TranscribeOutcome
import app.pulse.domain.model.User
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.FlushOutboxUseCase
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.CallAnswerDto
import app.pulse.protocol.CallCancelDto
import app.pulse.protocol.CallHangupDto
import app.pulse.protocol.CallIceDto
import app.pulse.protocol.CallLogCreatedDto
import app.pulse.protocol.CallLogItemDto
import app.pulse.protocol.CallOfferDto
import app.pulse.protocol.CallRejectDto
import app.pulse.protocol.ChannelDto
import app.pulse.protocol.CheckinResultDto
import app.pulse.protocol.CheckinWalletResultDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.EventRsvpDto
import app.pulse.protocol.EventsPageDto
import app.pulse.protocol.AppCommunityDto
import app.pulse.protocol.AppInstallResultDto
import app.pulse.protocol.AppInstallStateDto
import app.pulse.protocol.FolderDto
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.GameMatchCreateResultDto
import app.pulse.protocol.GamesPageDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.HubLogDto
import app.pulse.protocol.HubLogsPageDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.HubTasksPageDto
import app.pulse.protocol.KanbanCardDto
import app.pulse.protocol.KanbanPageDto
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.MarketBuyResultDto
import app.pulse.protocol.MarketListingDto
import app.pulse.protocol.MarketPageDto
import app.pulse.protocol.ReminderItemDto
import app.pulse.protocol.ReminderResolveDto
import app.pulse.protocol.RemindersPageDto
import app.pulse.protocol.RedPacketCreateResultDto
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.RedPacketGrabResultDto
import app.pulse.protocol.RsvpResultDto
import app.pulse.protocol.SwapPageDto
import app.pulse.protocol.SwapResultDto
import app.pulse.protocol.TournamentCreateResultDto
import app.pulse.protocol.TournamentJoinResultDto
import app.pulse.protocol.TournamentSummaryDto
import app.pulse.protocol.TournamentsPageDto
import app.pulse.protocol.TransferResultDto
import app.pulse.protocol.WalletPageDto
import app.pulse.protocol.WhiteboardClearResultDto
import app.pulse.protocol.WhiteboardPageDto
import app.pulse.protocol.WhiteboardPostResultDto
import app.pulse.protocol.WhiteboardStrokePostDto
import app.pulse.protocol.WhiteboardUndoResultDto
import app.pulse.protocol.FullUserDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseVoiceUser
import app.pulse.protocol.SavedItemDto
import app.pulse.protocol.SpaceStatePayload
import app.pulse.protocol.StageEndedPayload
import app.pulse.protocol.StageStatePayload
import app.pulse.protocol.StoriesPageDto
import app.pulse.protocol.StoryItemDto
import app.pulse.protocol.ScheduledItemDto
import app.pulse.protocol.TopicDto
import app.pulse.protocol.UserDto
import app.pulse.protocol.VoiceChunkPayload
import app.pulse.protocol.VoicePttPayload
import app.pulse.protocol.VoiceRosterPayload
import app.pulse.protocol.VoiceTranscriptPayload
import app.pulse.protocol.VoiceTranscriptResultDto
import app.pulse.protocol.decodeLinkPreviewDto
import app.pulse.protocol.decodePollDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Stories live exactly 24h — the same TTL the gateway stamps (route.ts STORY_TTL_MS). */
private const val STORY_TTL_MS: Long = 24L * 60 * 60 * 1000

/**
 * REAL remote-first, Room-cached repository (N3 UI-era surface).
 * Reads flow from Room; refreshes pull the gateway and upsert; writes POST
 * and cache the returned row. The socket pump turns relay signals into
 * Room upserts + [PulseEvent]s so every screen is live without polling.
 */
@Singleton
class PulseRepositoryImpl @Inject constructor(
    private val api: PulseApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val draftDao: DraftDao,
    private val topicDao: TopicDao,
    private val savedDao: SavedDao,
    private val callLogDao: CallLogDao,
    private val storyDao: StoryDao,
    private val wave7Dao: app.pulse.data.local.Wave7Dao,
    private val socket: PulseSocketClient,
    private val prefsLocalStore: PulsePrefsLocalStore,
    private val sessionTokenStore: SessionTokenStore,
    @ApplicationContext private val context: Context,
) : PulseRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsBus = MutableSharedFlow<PulseEvent>(
        replay = 0, extraBufferCapacity = 128,
    )
    private val onlineIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Relay connection truth for the room offline banner. Seeded from the
     * configured-endpoint state (offline-first deployments ARE offline) and
     * corrected by every socket CONNECT/DISCONNECT/ERROR signal.
     */
    private val connectedFlow = MutableStateFlow(app.pulse.core.PulseEndpoints.isConfigured)

    /** Backoff so a dead network doesn't re-pay the timeout on every keystroke. */
    @Volatile private var offlineUntil: Long = 0L

    @Volatile override var viewerId: String? = null
        private set

    @Volatile private var pumpStarted = false

    private val flushMutex = Mutex()
    private var selfHealJob: Job? = null

    override fun start(userId: String) {
        if (viewerId == userId && socket.isJoined) return
        viewerId = userId
        startPump()
        socket.connect(userId)
        // Wave 8 — load the persisted session token into the synchronous cache
        // BEFORE the refresh wave so the Bearer header rides from request one,
        // then merge the server prefs blob over the local store (server wins).
        scope.launch {
            runCatching { sessionTokenStore.load() }
                .onFailure { Log.w(TAG, "token load failed", it) }
            fetchSettings()
        }
        scope.launch { refreshConversations() }
        // Flush trigger: app start with a pending queue (web pwa-provider parity).
        scope.launch {
            runCatching {
                if (outboxDao.count() > 0) flushOutbox()
            }.onFailure { Log.w(TAG, "start flush failed", it) }
        }
        // Call-log queue rides the same trigger (single-writer rows that
        // died offline must land as soon as the gateway answers).
        scope.launch {
            runCatching { if (callLogDao.queueCount() > 0) flushCallLogQueue() }
                .onFailure { Log.w(TAG, "start call-log flush failed", it) }
        }
    }

    /**
     * Wave 8 — server prefs fetch (GET /api/settings). The server blob is
     * already defaults-merged + clamped; the tolerant resolve keeps the local
     * store junk-proof even when a proxy mangles the envelope. Failure is a
     * no-op (offline-first: the local blob stays authoritative).
     */
    private suspend fun fetchSettings() {
        val id = viewerId ?: return
        when (val r = api.settings(id)) {
            is PulseResult.Success -> {
                r.value.preferences?.let { prefsLocalStore.replaceFromServer(it) }
            }
            is PulseResult.Failure -> Log.i(TAG, "settings fetch skipped: ${r.kind}")
        }
    }

    override val pulsePrefs: Flow<WirePulsePrefs> = prefsLocalStore.prefs

    // ── R1-W2F — F-FX-05 per-conversation themes (`chat.convThemes`) ──

    override val convThemes: Flow<Map<String, ConvTheme>> = prefsLocalStore.convThemes

    override suspend fun setConvTheme(conversationId: String, theme: ConvTheme?) {
        prefsLocalStore.setConvTheme(conversationId, theme)
    }

    override suspend fun updatePulsePrefs(patch: WirePulsePrefs): Result<Unit> {
        // Optimistic FIRST — the toggle lands in the UI instantly.
        prefsLocalStore.applyLocal(patch)
        val id = viewerId ?: return Result.failure(IllegalStateException("No viewer identity"))
        return when (val r = api.updateSettings(id, patch)) {
            is PulseResult.Success -> {
                // the server response is the authoritative clamp — re-anchor
                r.value.preferences?.let { prefsLocalStore.replaceFromServer(it) }
                Result.success(Unit)
            }
            is PulseResult.Failure ->
                // honest offline parity: the LOCAL change stays, the caller hints
                Result.failure(IllegalStateException(r.message ?: "Couldn't reach the Pulse server"))
        }
    }

    override suspend fun login(name: String): Result<User> =
        when (val r = api.login(name)) {
            is PulseResult.Success -> {
                // ROTATION: the raw token shows up exactly once — persist it now.
                r.value.token?.let { t -> runCatching { sessionTokenStore.save(t) } }
                val user = r.value.user
                    ?: return Result.failure(IllegalStateException("Malformed login response"))
                Result.success(user.toDomain())
            }
            is PulseResult.Failure ->
                // 404 copy "No identity with that name on this Pulse." rides verbatim
                Result.failure(OnboardingError.of(r))
        }

    /** Identity forget/switch — the credential must not outlive the identity. */
    override suspend fun clearSessionToken() {
        sessionTokenStore.clear()
    }

    /** Data & Storage — user-initiated drop of one held outbox row (no verdict event). */
    override suspend fun discardOutboxEntry(clientId: String) {
        outboxDao.deleteByClientId(clientId)
        messageDao.deleteById(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId)
    }

    /** Data & Storage — drop every held outbox row + its optimistic temp bubble. */
    override suspend fun clearOutbox() {
        outboxDao.clearAll()
        messageDao.deleteAllTempMessages()
    }

    /** Data & Storage — drop every composer draft at once. */
    override suspend fun clearAllDrafts() {
        draftDao.clearAll()
    }

    /** One collector, started once per process: signals → Room + events. */
    private fun startPump() {
        if (pumpStarted) return
        pumpStarted = true
        scope.launch {
            socket.signals.collect { signal ->
                when (signal) {
                    is PulseSocketClient.Signal.Connection -> {
                        connectedFlow.value = signal.connected
                        if (signal.connected) {
                            // Flush trigger: the relay came back — drain the outbox first
                            // so queued sends overtake anything new.
                            scope.launch {
                                runCatching { flushOutbox() }
                                    .onFailure { Log.w(TAG, "reconnect flush failed", it) }
                            }
                            scope.launch {
                                runCatching { if (callLogDao.queueCount() > 0) flushCallLogQueue() }
                                    .onFailure { Log.w(TAG, "reconnect call-log flush failed", it) }
                            }
                        }
                    }
                    is PulseSocketClient.Signal.SessionRejected -> {
                        // Wave 8 — the relay verified our PRESENTED token and
                        // refused it (rotated/invalid). Clear the credential;
                        // the app layer routes to the honest re-login flow.
                        scope.launch { runCatching { sessionTokenStore.markInvalid(signal.error) } }
                    }
                    is PulseSocketClient.Signal.Joined -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.PresenceSnapshot -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.MessageEnvelope -> onMessageEnvelope(signal)
                    is PulseSocketClient.Signal.Read -> eventsBus.tryEmit(
                        PulseEvent.MessageRead(signal.conversationId, signal.userId, signal.lastReadAt),
                    )
                    is PulseSocketClient.Signal.Typing -> eventsBus.tryEmit(
                        PulseEvent.Typing(signal.conversationId, signal.userId, signal.userName, signal.isTyping),
                    )
                    is PulseSocketClient.Signal.ConversationUpdated -> scheduleConversationsRefresh()
                    is PulseSocketClient.Signal.VoiceRoster -> eventsBus.tryEmit(
                        PulseEvent.VoiceRoster(
                            VoiceRosterPayload(conversationId = signal.conversationId, roster = signal.roster),
                        ),
                    )
                    is PulseSocketClient.Signal.VoicePtt -> eventsBus.tryEmit(
                        PulseEvent.VoicePtt(
                            VoicePttPayload(signal.conversationId, signal.userId, signal.active),
                        ),
                    )
                    is PulseSocketClient.Signal.VoiceChunk -> eventsBus.tryEmit(
                        PulseEvent.VoiceChunk(
                            VoiceChunkPayload(signal.conversationId, signal.userId, signal.seq, signal.data),
                        ),
                    )
                    is PulseSocketClient.Signal.VoiceTranscript -> eventsBus.tryEmit(
                        PulseEvent.VoiceTranscript(
                            VoiceTranscriptPayload(signal.conversationId, signal.speakerId, signal.text),
                        ),
                    )
                    is PulseSocketClient.Signal.StageState -> eventsBus.tryEmit(
                        PulseEvent.StageState(StageStatePayload(signal.conversationId, signal.state)),
                    )
                    is PulseSocketClient.Signal.StageEnded -> eventsBus.tryEmit(
                        PulseEvent.StageEnded(StageEndedPayload(signal.conversationId)),
                    )
                    is PulseSocketClient.Signal.SpaceState -> eventsBus.tryEmit(
                        PulseEvent.SpaceState(SpaceStatePayload(signal.conversationId, signal.state)),
                    )
                    is PulseSocketClient.Signal.CallSignal -> eventsBus.tryEmit(
                        PulseEvent.CallSignal(callSignalToDomain(signal.signal)),
                    )
                }
            }
        }
    }

    /** Typed per-event socket union → the domain envelope the engine consumes. */
    private fun callSignalToDomain(s: PulseSocketClient.CallSignal): app.pulse.domain.model.CallSignalData =
        when (s) {
            is PulseSocketClient.CallSignal.Offer -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_OFFER,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                sdp = s.dto.sdp, callerName = s.dto.callerName,
                callerColor = s.dto.callerColor, callerAvatar = s.dto.callerAvatar,
            )
            is PulseSocketClient.CallSignal.Answer -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_ANSWER,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                sdp = s.dto.sdp,
            )
            is PulseSocketClient.CallSignal.Ice -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_ICE,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                candidate = s.dto.candidate, sdpMid = s.dto.sdpMid, sdpMLineIndex = s.dto.sdpMLineIndex,
            )
            is PulseSocketClient.CallSignal.Reject -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_REJECT,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                reason = s.dto.reason,
            )
            is PulseSocketClient.CallSignal.Cancel -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_CANCEL,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                reason = s.dto.reason,
            )
            is PulseSocketClient.CallSignal.Hangup -> app.pulse.domain.model.CallSignalData(
                event = app.pulse.protocol.SocketEvents.CALL_HANGUP,
                callId = s.dto.callId, conversationId = s.dto.conversationId,
                from = s.dto.from, to = s.dto.to, kind = app.pulse.domain.model.CallKind.of(s.dto.kind),
                durationSec = s.dto.durationSec,
            )
        }

    /**
     * Every message:* envelope carries the authoritative row — upsert it and
     * fan the event out. message:deleted keeps its tombstone semantics.
     */
    private suspend fun onMessageEnvelope(signal: PulseSocketClient.Signal.MessageEnvelope) {
        val dto = signal.dto ?: return
        val message = dto.toDomain()
        if (signal.event == app.pulse.protocol.SocketEvents.MESSAGE_DELETED) {
            eventsBus.tryEmit(
                PulseEvent.MessageDeleted(signal.conversationId, message.id),
            )
        } else {
            messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
            dedupeTempEchoes(message)
            eventsBus.tryEmit(PulseEvent.MessageReceived(signal.conversationId, message))
        }
        scheduleConversationsRefresh()
    }

    /**
     * Optimistic dedupe (spec §1.2): when a REAL row lands, every still-queued
     * `local_` echo of the same sender+body that predates it is retired — the
     * flush engine's own clientId swap stays the primary reconcile path.
     */
    private suspend fun dedupeTempEchoes(real: Message) {
        if (real.body.isBlank() || real.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX)) return
        val realAt = PulseTime.epochMs(real.createdAt)
        if (realAt <= 0L) return
        val echoes = runCatching {
            messageDao.tempEchoes(real.conversationId, real.authorId, real.body, real.id)
        }.getOrDefault(emptyList())
        val stale = echoes.filter { PulseTime.epochMs(it.createdAt) <= realAt }.map { it.id }
        if (stale.isNotEmpty()) messageDao.deleteByIds(stale)
    }

    private var refreshScheduled = false

    /** Debounced inbox refetch — keeps previews/unreads honest after live hits. */
    private fun scheduleConversationsRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        scope.launch {
            delay(500)
            refreshScheduled = false
            if (isActive) refreshConversations()
        }
    }

    // ── reads ───────────────────────────────────────────────────
    override fun observeConversations(query: String): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows ->
            rows.map { it.toDomain() }
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeFor(conversationId).map { rows -> rows.map { it.toDomain() } }

    override fun observeThreadMessages(rootId: String): Flow<List<Message>> =
        messageDao.observeThread(rootId).map { rows -> rows.map { it.toDomain() } }

    override fun observeDrafts(): Flow<Map<String, String>> = draftDao.observeAll().map { rows ->
        rows.filter { it.text.isNotBlank() }.associate { it.conversationId to it.text }
    }

    override fun observePresence(): Flow<Set<String>> = onlineIds.asStateFlow()

    override fun observeConnected(): Flow<Boolean> = connectedFlow.asStateFlow()

    override fun events(): Flow<PulseEvent> = eventsBus.asSharedFlow()

    override suspend fun refreshConversations(): Result<Unit> = when (val r = api.conversations(viewerId ?: "")) {
        is PulseResult.Success -> {
            conversationDao.upsertAll(r.value.conversations.map { ConversationEntity.from(it.toDomain(viewerId)) })
            Result.success(Unit)
        }
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> =
        refreshMessagesWindow(conversationId, limit = limit)

    /** Wave 2 topic-filtered refresh — only rows filed under `topicId`. */
    override suspend fun refreshMessages(conversationId: String, topicId: String?): Result<Unit> =
        refreshMessagesWindow(conversationId, topicId = topicId)

    private suspend fun refreshMessagesWindow(
        conversationId: String,
        limit: Int = 200,
        before: String? = null,
        topicId: String? = null,
    ): Result<Unit> =
        when (val r = api.messages(conversationId, limit, before, topicId)) {
            is PulseResult.Success -> {
                messageDao.upsertAll(
                    r.value.messages.map { dto ->
                        val domain = dto.toDomain()
                        MessageEntity.from(domain, reactionsJsonOf(domain))
                    },
                )
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── users / identity ────────────────────────────────────────
    override suspend fun users(query: String): Result<List<User>> = when (val r = api.users()) {
        is PulseResult.Success -> Result.success(
            r.value.users.map { it.toDomain() }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.handle.contains(query, ignoreCase = true) },
        )
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun createIdentity(name: String, color: String?, username: String?): Result<User> =
        when (val r = api.createUser(name, color, username)) {
            is PulseResult.Success -> {
                // Wave 8 — the create response carries the raw session token
                // (shown once server-side); persist it before returning.
                r.value.token?.let { t -> runCatching { sessionTokenStore.save(t) } }
                val user = r.value.user
                    ?: return Result.failure(IllegalStateException("Malformed create response"))
                Result.success(user.toDomain())
            }
            is PulseResult.Failure ->
                when {
                    // Definitive live-server verdicts surface untouched —
                    // 409 name clash / username_taken keep the web flows.
                    r.status == 400 || r.status == 409 -> Result.failure(OnboardingError.of(r))
                    // No reachable server → local identity. Onboarding completes
                    // offline; the inbox renders the honest offline state.
                    else -> {
                        offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
                        Result.success(localIdentity(name, color, username))
                    }
                }
        }

    /** Offline identity — stable random id, kept in prefs like a server row. */
    private fun localIdentity(name: String, color: String?, username: String?): User = User(
        id = "local_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12),
        name = name,
        handle = username ?: "",
        color = color ?: "emerald",
    )

    /**
     * Availability never blocks onboarding: server → static CDN registry →
     * local rules. The worst case (fully offline) still answers in one blink
     * after the backoff, instead of spinning forever like a dead gateway.
     */
    override suspend fun checkHandle(handle: String): Result<HandleCheck> =
        when (val r = api.checkUsername(handle)) {
            is PulseResult.Success ->
                Result.success(HandleCheck(available = r.value.available, suggestion = r.value.suggestion))
            is PulseResult.Failure ->
                // 400 invalid / 409 taken are definitive live-server verdicts —
                // never second-guess them. Anything else (unreachable host,
                // route-less static CDN, 5xx) falls through to the registry.
                if (r.status == 400 || r.status == 409) {
                    Result.failure(OnboardingError.of(r))
                } else {
                    registryOrLocalVerdict(handle)
                }
        }

    /** Fresh registry verdict unless a recent attempt already proved offline. */
    private suspend fun registryOrLocalVerdict(handle: String): Result<HandleCheck> {
        if (System.currentTimeMillis() < offlineUntil) {
            return Result.success(localVerdict(handle))
        }
        return when (val reg = api.fetchHandleRegistry()) {
            is PulseResult.Success -> {
                val claimed = (reg.value.taken + reg.value.reserved).map { it.lowercase() }
                if (handle.lowercase() in claimed) {
                    Result.success(HandleCheck(available = false, suggestion = "${handle}_"))
                } else {
                    Result.success(HandleCheck(available = true, suggestion = null))
                }
            }
            is PulseResult.Failure -> {
                // fully offline — stop paying the timeout on every keystroke
                offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
                Result.success(localVerdict(handle))
            }
        }
    }

    /** Local rules — the last line of defense so onboarding always completes. */
    private fun localVerdict(handle: String): HandleCheck =
        if (handle.lowercase() in BUILT_IN_RESERVED) {
            HandleCheck(available = false, suggestion = "${handle}_")
        } else {
            HandleCheck(available = true, suggestion = null)
        }

    override suspend fun lookupUserByName(name: String): Result<User?> =
        when (val r = api.lookupUserByName(name)) {
            is PulseResult.Success -> Result.success(r.value.toDomain())
            is PulseResult.Failure ->
                // 404 = the name is free — the caller decides what that means
                if (r.kind == PulseResult.Failure.Kind.NOT_FOUND) Result.success(null)
                else Result.failure(OnboardingError.of(r))
        }

    override suspend fun createDm(otherUserId: String): Result<Conversation> = createConversation(listOf(otherUserId), isGroup = false, name = null)
    override suspend fun createGroup(name: String, memberIds: List<String>): Result<Conversation> = createConversation(memberIds, isGroup = true, name = name)

    private suspend fun createConversation(memberIds: List<String>, isGroup: Boolean, name: String?): Result<Conversation> =
        when (val r = api.createConversation(viewerId ?: "", memberIds, isGroup, name)) {
            is PulseResult.Success -> {
                conversationDao.upsertAll(listOf(ConversationEntity.from(r.value.toDomain(viewerId))))
                Result.success(r.value.toDomain(viewerId))
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun me(): User? = null // no /me route on the wire yet — viewer lives in prefs

    // ── writes ──────────────────────────────────────────────────
    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        replyToId: String?,
        parentId: String?,
        topicId: String?,
    ): Result<Message> {
        // Optimistic echo FIRST (spec §2 row 2): the bubble appears on the very
        // keystroke-to-send beat, not after the round-trip. The same clientId
        // then either reconciles with the real row, rides the outbox, or is
        // retracted on a definitive failure.
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "")
        val nowIso = java.time.OffsetDateTime.now()
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val temp = Message(
            id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId,
            conversationId = conversationId,
            authorId = viewerId ?: "",
            authorName = viewerId ?: "",
            kind = Message.Kind.TEXT,
            body = body,
            createdAt = nowIso,
            replyToId = replyToId,
            threadRootId = parentId,
            topicId = topicId,
        )
        if (!viewerId.isNullOrBlank()) {
            messageDao.upsertAll(listOf(MessageEntity.from(temp)))
        }
        return when (val r = api.sendMessage(
            conversationId = conversationId,
            senderId = viewerId ?: "",
            content = body,
            replyToId = replyToId,
            // THREAD REPLY rides `parentId` — NEVER conflated with replyToId (spec §1.1).
            parentId = parentId,
            // Wave 2 topic filing (spec §1 row 10): never on thread replies.
            topicId = if (parentId == null) topicId else null,
        )) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                dedupeTempEchoes(message)
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure ->
                // Network-class failure → the optimistic offline core: the temp
                // bubble STAYS + an outbox row waits for the flush engine.
                // 4xx verdicts and non-queueable sends (threads/quotes, spec
                // §1.2) retract the echo and surface the honest error.
                if (r.kind == PulseResult.Failure.Kind.NETWORK && queueableSend(replyToId, parentId) && !viewerId.isNullOrBlank()) {
                    outboxDao.insert(
                        OutboxEntity(
                            conversationId = conversationId,
                            clientId = clientId,
                            content = body,
                            createdAt = nowIso,
                        ),
                    )
                    outboxDao.trimBeyond(MAX_OUTBOX)
                    eventsBus.tryEmit(PulseEvent.OutboxQueued(clientId, conversationId))
                    Result.success(temp)
                } else {
                    messageDao.deleteById(temp.id)
                    Result.failure(apiExceptionOf(r))
                }
        }
    }

    /**
     * REM-A — rich TEXT-kind send (F-MS-17/24/23): carries the incognito flag
     * (group-only server-side) and the sticker/effects payload blob. Optimistic
     * echo + offline-outbox semantics mirror [sendMessage] exactly.
     */
    override suspend fun sendRichMessage(
        conversationId: String,
        body: String,
        kind: String,
        payload: String?,
        anon: Boolean,
        replyToId: String?,
        parentId: String?,
        topicId: String?,
    ): Result<Message> {
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "")
        val nowIso = java.time.OffsetDateTime.now()
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val temp = Message(
            id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId,
            conversationId = conversationId,
            authorId = viewerId ?: "",
            authorName = viewerId ?: "",
            kind = Message.Kind.TEXT,
            body = body,
            createdAt = nowIso,
            replyToId = replyToId,
            threadRootId = parentId,
            topicId = topicId,
            anon = anon,
        )
        if (!viewerId.isNullOrBlank()) {
            messageDao.upsertAll(listOf(MessageEntity.from(temp)))
        }
        return when (
            val r = api.sendMessage(
                conversationId = conversationId,
                senderId = viewerId ?: "",
                content = body,
                replyToId = replyToId,
                parentId = parentId,
                kind = kind,
                topicId = if (parentId == null) topicId else null,
                anon = anon.takeIf { it },
                payload = payload?.let { raw ->
                    runCatching { PulseJson.parseToJsonElement(raw) }.getOrNull() as? kotlinx.serialization.json.JsonObject
                },
            )
        ) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                dedupeTempEchoes(message)
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure ->
                if (r.kind == PulseResult.Failure.Kind.NETWORK && queueableSend(replyToId, parentId) && !viewerId.isNullOrBlank()) {
                    outboxDao.insert(
                        OutboxEntity(
                            conversationId = conversationId,
                            clientId = clientId,
                            content = body,
                            createdAt = nowIso,
                        ),
                    )
                    outboxDao.trimBeyond(MAX_OUTBOX)
                    eventsBus.tryEmit(PulseEvent.OutboxQueued(clientId, conversationId))
                    Result.success(temp)
                } else {
                    messageDao.deleteById(temp.id)
                    Result.failure(apiExceptionOf(r))
                }
        }
    }

    /** Legacy error text (`"KIND: message"`) + the typed retryAfter carrier. */
    private fun apiExceptionOf(f: PulseResult.Failure): PulseApiException = PulseApiException(
        kind = f.kind.name,
        message = f.message?.let { "${f.kind.name}: $it" } ?: f.kind.name,
        status = f.status,
        retryAfter = f.retryAfter,
    )

    // ── Wave 1 messaging surface (spec §1.1 — every route exists today) ──

    /**
     * MEDIA send — the online-only leg (spec §1.2: media is NEVER queued).
     * No optimistic echo: the staged card in the composer is the progress UI;
     * failures surface on it with a retry, never an outbox row.
     */
    override suspend fun sendMediaMessage(
        conversationId: String,
        body: String,
        imagePath: String?,
        audioPath: String?,
        durationMs: Long?,
        filePath: String?,
        fileName: String?,
        fileSize: Long?,
        kind: String?,
        viewOnce: Boolean?,
        topicId: String?,
    ): Result<Message> =
        when (
            val r = api.sendMessage(
                conversationId = conversationId,
                senderId = viewerId ?: "",
                content = body,
                imagePath = imagePath,
                audioPath = audioPath,
                durationMs = durationMs,
                filePath = filePath,
                fileName = fileName,
                fileSize = fileSize,
                kind = kind,
                viewOnce = viewOnce,
                topicId = topicId,
            )
        ) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    /** GET /api/uploads/{file} → bytes written under cacheDir/downloads. */
    override suspend fun downloadMedia(filePath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when (val r = api.downloadMedia(filePath)) {
                is PulseResult.Success -> r.value
                is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
            }
            val safeName = filePath.substringAfterLast('/').ifBlank {
                "pulse-${java.util.UUID.randomUUID()}"
            }
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            File(dir, safeName).apply { writeBytes(bytes) }.absolutePath
        }
    }

    /** Batched "N replies ↳" counts for the river — one grouped Room query. */
    override suspend fun threadReplyCounts(rootIds: List<String>): Map<String, Int> {
        if (rootIds.isEmpty()) return emptyMap()
        return runCatching {
            messageDao.countsByThread(rootIds).associate { it.rootId to it.cnt }
        }.getOrDefault(emptyMap())
    }

    /** The outbox queues PURE TEXT only — thread/quote sends are online-only. */
    private fun queueableSend(replyToId: String?, parentId: String?): Boolean =
        replyToId == null && parentId == null

    override suspend fun editMessage(messageId: String, content: String): Result<Message> =
        when (val r = api.editMessage(messageId, viewerId ?: "", content)) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun toggleMessagePin(messageId: String): Result<Message> =
        when (val r = api.toggleMessagePin(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun toggleMessageSave(messageId: String): Result<Boolean> =
        when (val r = api.toggleMessageSave(messageId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.saved)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun pinnedMessages(conversationId: String): List<Message> {
        val page = when (val r = api.pinnedMessages(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
        }
        val rows = page.messages.map { it.toDomain() }
        // Upsert keeps the Room cache (pin glyphs, banners) authoritative.
        messageDao.upsertAll(rows.map { MessageEntity.from(it, reactionsJsonOf(it)) })
        return rows
    }

    override suspend fun loadThread(rootId: String): Pair<Message, List<Message>> {
        val page = when (val r = api.thread(rootId, viewerId ?: "")) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
        }
        val parent = page.parent?.toDomain() ?: throw IllegalStateException("thread parent missing")
        val replies = page.replies.map { it.toDomain() } // wire order: createdAt asc
        // Upsert both so observeThread(rootId) rehydrates the sheet offline
        // and realtime reply appends merge with this window.
        messageDao.upsertAll((listOf(parent) + replies).map { MessageEntity.from(it, reactionsJsonOf(it)) })
        return parent to replies
    }

    override suspend fun messagesPage(conversationId: String, before: String?, limit: Int): Pair<List<Message>, Boolean> {
        val page = when (val r = api.messages(conversationId, limit, before)) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
        }
        val rows = page.messages.map { it.toDomain() } // wire order: asc
        // Pagination merge = plain upserts; loaded window grows in Room.
        messageDao.upsertAll(rows.map { MessageEntity.from(it, reactionsJsonOf(it)) })
        return rows to page.hasMore
    }

    override suspend fun searchInConversation(conversationId: String, query: String): List<Message> {
        if (query.isBlank()) return emptyList()
        val page = when (val r = api.searchInConversation(conversationId, query)) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
        }
        val rows = page.messages.map { it.toDomain() }
        messageDao.upsertAll(rows.map { MessageEntity.from(it, reactionsJsonOf(it)) })
        return rows
    }

    override suspend fun uploadMedia(dataUrl: String): Result<String> =
        when (val r = api.uploadMedia(dataUrl)) {
            is PulseResult.Success -> {
                val path = r.value.filePath
                if (path.isNullOrBlank()) {
                    Result.failure(IllegalStateException("upload returned no filePath"))
                } else {
                    Result.success(path)
                }
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    /**
     * Forward = re-POST the SAME body into the target conversation (no wire
     * endpoint — spec §1.1). Media is forwarded by reusing the stored paths;
     * the wire kind maps back through the whitelist (text|image|audio|sticker|
     * location|file). REM-A F-MS-10: a NETWORK-class failure on a PLAIN TEXT
     * forward rides the existing outbox (kind "text") instead of dying —
     * media stays online-only per spec §1.2.
     */
    override suspend fun forwardMessage(targetConversationId: String, source: Message): Result<Message> {
        val textOnly = source.kind == Message.Kind.TEXT &&
            source.imagePath == null && source.audioPath == null && source.filePath == null
        return when (
            val r = api.sendMessage(
                conversationId = targetConversationId,
                senderId = viewerId ?: "",
                content = source.body,
                imagePath = source.imagePath,
                audioPath = source.audioPath,
                durationMs = source.durationMs,
                filePath = source.filePath,
                fileName = source.fileName,
                fileSize = source.fileSize,
                kind = wireKindOf(source.kind),
                // R1-W2F (F-MD-07) — location pins keep their {lat,lng,label}
                // payload when forwarded, so the target room renders a REAL pin.
                payload = source.payload?.let { raw ->
                    runCatching { PulseJson.parseToJsonElement(raw) }.getOrNull()
                        as? kotlinx.serialization.json.JsonObject
                },
            )
        ) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure -> {
                if (r.kind == PulseResult.Failure.Kind.NETWORK && textOnly && !viewerId.isNullOrBlank()) {
                    val clientId = java.util.UUID.randomUUID().toString().replace("-", "")
                    val nowIso = java.time.OffsetDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    val temp = Message(
                        id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId,
                        conversationId = targetConversationId,
                        authorId = viewerId ?: "",
                        authorName = viewerId ?: "",
                        kind = Message.Kind.TEXT,
                        body = source.body,
                        createdAt = nowIso,
                    )
                    messageDao.upsertAll(listOf(MessageEntity.from(temp)))
                    outboxDao.insert(
                        OutboxEntity(
                            conversationId = targetConversationId,
                            clientId = clientId,
                            content = source.body,
                            kind = "text",
                            createdAt = nowIso,
                        ),
                    )
                    outboxDao.trimBeyond(MAX_OUTBOX)
                    eventsBus.tryEmit(PulseEvent.OutboxQueued(clientId, targetConversationId))
                    return Result.success(temp)
                }
                Result.failure(apiExceptionOf(r))
            }
        }
    }

    /** Domain kind → wire kind (whitelist text|image|audio|sticker|location|file). */
    private fun wireKindOf(kind: Message.Kind): String? = when (kind) {
        Message.Kind.TEXT -> "text"
        Message.Kind.IMAGE -> "image"
        Message.Kind.VOICE -> "audio"
        Message.Kind.FILE -> "file"
        // R1-W2F (F-MD-07) — pins re-POST with their wire kind + payload.
        Message.Kind.LOCATION -> "location"
        // VIDEO/POLL/RED_PACKET/SYSTEM are outside the send whitelist — omit
        // and let the server default to text (media fields still ride along).
        else -> null
    }

    override suspend fun setServerDraft(conversationId: String, draft: String) {
        val id = viewerId ?: return
        runCatching {
            api.setDraft(conversationId, id, draft.take(MAX_DRAFT))
        }
        // Fire-and-forget: the local draft wins; the server only seeds
        // cross-device restore — failures are silently ignored.
    }

    override suspend fun myRole(conversationId: String): Result<String?> =
        when (val r = api.conversationDetail(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                r.value.members.firstOrNull { it.id == viewerId }?.role,
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun conversationDetail(conversationId: String): Result<Conversation> =
        when (val r = api.conversationDetail(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val conversation = r.value.toDomain(viewerId)
                conversationDao.upsertAll(listOf(ConversationEntity.from(conversation)))
                Result.success(conversation)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    // ── REM-A group governance + scheduling (web group-info-sheet parity) ──

    /** Live group meta — ALSO upserts the Room conversation cache (rename TTL etc. flow into the list). */
    override suspend fun groupMeta(conversationId: String): Result<GroupMeta> =
        when (val r = api.conversationDetail(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val dto = r.value
                conversationDao.upsertAll(listOf(ConversationEntity.from(dto.toDomain(viewerId))))
                Result.success(
                    GroupMeta(
                        myRole = dto.members.firstOrNull { it.id == viewerId }?.role,
                        isGroup = dto.isGroup,
                        ttlSeconds = dto.ttlSeconds ?: 0,
                        broadcastMode = dto.broadcastMode == true,
                        slowModeSeconds = dto.slowModeSeconds ?: 0,
                        screenPrivacy = dto.screenPrivacy == true,
                        inviteCode = dto.inviteCode,
                    ),
                )
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    /** PATCH /api/conversations/{id} — every success carries the updated detail; refresh the cache. */
    private suspend fun patchGroupMeta(
        conversationId: String,
        name: String? = null,
        broadcast: Boolean? = null,
        screenPrivacy: Boolean? = null,
    ): Result<Unit> =
        when (val r = api.patchConversation(conversationId, viewerId ?: "", name = name, broadcast = broadcast, screenPrivacy = screenPrivacy)) {
            is PulseResult.Success -> {
                conversationDao.upsertAll(listOf(ConversationEntity.from(r.value.toDomain(viewerId))))
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun renameGroup(conversationId: String, name: String): Result<Unit> =
        patchGroupMeta(conversationId, name = name)

    override suspend fun setGroupBroadcast(conversationId: String, broadcast: Boolean): Result<Unit> =
        patchGroupMeta(conversationId, broadcast = broadcast)

    override suspend fun setScreenPrivacy(conversationId: String, on: Boolean): Result<Unit> =
        patchGroupMeta(conversationId, screenPrivacy = on)

    override suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Result<List<String>> =
        when (val r = api.addMembers(conversationId, viewerId ?: "", userIds)) {
            is PulseResult.Success -> Result.success(r.value.added)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setMemberRole(conversationId: String, userId: String, promote: Boolean): Result<Unit> =
        when (val r = api.promoteDemote(conversationId, viewerId ?: "", userId, promote)) {
            is PulseResult.Success -> {
                conversationDao.upsertAll(listOf(ConversationEntity.from(r.value.toDomain(viewerId))))
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun kickMember(conversationId: String, userId: String): Result<Unit> =
        when (val r = api.kickMember(conversationId, viewerId ?: "", userId)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun leaveGroup(conversationId: String): Result<GroupLeave> =
        when (val r = api.leaveGroup(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                // Departure = the row leaves the chats list (server truth).
                conversationDao.delete(conversationId)
                Result.success(GroupLeave(r.value.remainingMembers, r.value.promotedUserId))
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun createGroupInvite(conversationId: String, regenerate: Boolean): Result<String> =
        when (val r = api.createInvite(conversationId, viewerId ?: "", regenerate)) {
            is PulseResult.Success -> Result.success(r.value.inviteCode)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setDisappearingTtl(conversationId: String, ttlSeconds: Int): Result<Int> =
        when (val r = api.setDisappearingTtl(conversationId, viewerId ?: "", ttlSeconds)) {
            is PulseResult.Success -> {
                conversationDao.upsertAll(listOf(ConversationEntity.from(r.value.toDomain(viewerId))))
                Result.success(r.value.ttlSeconds ?: ttlSeconds)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setSlowMode(conversationId: String, seconds: Int): Result<Int> =
        when (val r = api.setSlowMode(conversationId, viewerId ?: "", seconds)) {
            is PulseResult.Success -> Result.success(r.value.slowModeSeconds)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun scheduledMessages(conversationId: String): Result<List<ScheduledItem>> =
        when (val r = api.scheduledMessages(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.items.map { it.toDomainScheduled() })
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun scheduleMessage(conversationId: String, content: String, scheduledAtIso: String): Result<ScheduledItem> =
        when (val r = api.scheduleMessage(conversationId, viewerId ?: "", content, scheduledAtIso)) {
            is PulseResult.Success -> Result.success(r.value.toDomainScheduled())
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun cancelScheduled(scheduledId: String): Result<Unit> =
        when (val r = api.cancelScheduled(scheduledId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    // ── offline outbox engine (Wave 0 — web pulse-outbox parity) ────

    override suspend fun enqueueOutbox(entry: OutboxEntry): Result<Unit> = try {
        outboxDao.insert(OutboxEntity.from(entry))
        outboxDao.trimBeyond(MAX_OUTBOX)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun observeOutbox(): Flow<List<OutboxEntry>> =
        outboxDao.observeAll().map { rows -> rows.map { it.toEntry() } }

    override suspend fun outboxPending(): List<OutboxEntry> = outboxDao.all().map { it.toEntry() }

    /** ONE POST attempt — classification rides on OutboxDeliveryException. */
    override suspend fun attemptOutboxSend(entry: OutboxEntry): Result<Message> =
        when (val r = api.sendMessage(entry.conversationId, viewerId ?: "", entry.content)) {
            is PulseResult.Success -> Result.success(r.value.toDomain())
            is PulseResult.Failure -> {
                val classification = when (r.kind) {
                    PulseResult.Failure.Kind.VALIDATION,
                    PulseResult.Failure.Kind.FORBIDDEN,
                    PulseResult.Failure.Kind.NOT_FOUND,
                    PulseResult.Failure.Kind.AUTH,
                    -> OutboxFailureClass.DROP
                    else -> OutboxFailureClass.RETRY
                }
                Result.failure(
                    OutboxDeliveryException(classification, r.message ?: r.kind.name),
                )
            }
        }

    override suspend fun resolveOutboxDelivery(entry: OutboxEntry, real: Message) {
        messageDao.upsertAll(listOf(MessageEntity.from(real, reactionsJsonOf(real))))
        messageDao.deleteById(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + entry.clientId)
        dedupeTempEchoes(real)
        outboxDao.deleteByClientId(entry.clientId)
        scheduleConversationsRefresh()
        eventsBus.tryEmit(PulseEvent.OutboxFlushed(entry.clientId, real))
    }

    override suspend fun dropOutboxEntry(clientId: String, reason: String) {
        outboxDao.deleteByClientId(clientId)
        messageDao.deleteById(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId)
        eventsBus.tryEmit(PulseEvent.OutboxDropped(clientId, reason))
    }

    override suspend fun bumpOutboxAttempts(clientId: String) {
        outboxDao.incrementAttempts(clientId)
    }

    /**
     * Every trigger funnels here (start / reconnect / foreground / worker /
     * self-heal). The drain policy itself is FlushOutboxUseCase — single
     * source of truth, unit-tested at the domain layer.
     */
    override suspend fun flushOutbox(): FlushReport = flushMutex.withLock {
        val report = FlushOutboxUseCase(this).invoke()
        if (report.pending > 0) scheduleSelfHeal()
        report
    }

    /** 20s self-heal — a pending queue never waits longer than one beat. */
    private fun scheduleSelfHeal() {
        if (selfHealJob?.isActive == true) return
        selfHealJob = scope.launch {
            delay(SELF_HEAL_MS)
            runCatching { flushOutbox() }
                .onFailure { Log.w(TAG, "self-heal flush failed", it) }
        }
    }

    // ── drafts (Wave 0 — web pulse-drafts parity) ──────────────────

    override suspend fun saveDraft(conversationId: String, text: String) {
        val trimmed = text.take(MAX_DRAFT)
        if (trimmed.isBlank()) {
            draftDao.delete(conversationId)
        } else {
            draftDao.upsert(
                DraftEntity(
                    conversationId = conversationId,
                    text = trimmed,
                    updatedAt = java.time.OffsetDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                ),
            )
        }
        // Server mirror (web R45 parity): every local mutation also rides the
        // PATCH /draft route ('' clears) so other devices restore it. Local
        // still wins; the mirror is fire-and-forget.
        setServerDraft(conversationId, trimmed)
    }

    override suspend fun clearDraft(conversationId: String) {
        draftDao.delete(conversationId)
        setServerDraft(conversationId, "") // web parity: clear paths sync too
    }

    override fun observeDraft(conversationId: String): Flow<String?> =
        draftDao.observe(conversationId).map { it?.text }

    override suspend fun markRead(conversationId: String): Result<Unit> {
        // Optimistic: zero the badge locally, revert if the server refuses.
        val before = conversationDao.byId(conversationId)?.toDomain()
        if (before != null) {
            mutateConversation(conversationId) { it.copy(unreadCount = 0, myManualUnread = false) }
        }
        val result = api.postAction(
            "/api/conversations/$conversationId/read",
            PulseApi.jsonOf("userId" to (viewerId ?: "")),
        ).toResult()
        if (result.isFailure && before != null) {
            mutateConversation(conversationId) { before }
        }
        return result
    }

    override suspend fun setTyping(conversationId: String, userName: String, typing: Boolean) {
        socket.emitTyping(recipients = emptyList(), conversationId, viewerId ?: "", userName = userName, isTyping = typing)
    }

    override suspend fun react(messageId: String, emoji: String): Result<Unit> {
        // Optimistic local toggle (viewer-scoped), then server truth via refetch.
        val row = messageDao.byId(messageId)
        if (row != null && viewerId != null) {
            val me = viewerId!!
            val current = row.toDomain()
            val mine = current.reactions.filter { it.userId == me }
            val hadSame = mine.any { it.emoji == emoji }
            val updated = if (hadSame) {
                current.reactions.filterNot { it.userId == me && it.emoji == emoji }
            } else {
                current.reactions + Reaction(emoji, me)
            }
            messageDao.upsertAll(
                listOf(MessageEntity.from(current.copy(reactions = updated), reactionsJsonOf(current.copy(reactions = updated)))),
            )
        }
        val result = api.react(messageId, viewerId ?: "", emoji)
        if (result is PulseResult.Success && row != null) {
            scope.launch {
                delay(500)
                runCatching { refreshMessages(row.conversationId) }
                    .onFailure { Log.w(TAG, "react refetch failed", it) }
            }
        }
        return result.toResult()
    }

    /**
     * Optimistic local mutation of one cached conversation row — the flag flips
     * instantly and the server call + refresh reconcile the truth afterwards.
     */
    private suspend fun mutateConversation(id: String, transform: (Conversation) -> Conversation) {
        val row = conversationDao.byId(id) ?: return
        conversationDao.upsertAll(listOf(ConversationEntity.from(transform(row.toDomain()))))
    }

    override suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit> {
        mutateConversation(conversationId) { it.copy(isPinned = pinned) }
        val result = api.patchAction(
            "/api/conversations/$conversationId/pin",
            PulseApi.jsonOf("userId" to (viewerId ?: "")),
        ).toResult()
        refreshConversations() // server toggles — reconcile from truth either way
        return result
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit> =
        setMutedUntil(conversationId, if (muted) "8h" else null)

    override suspend fun setMutedUntil(conversationId: String, until: String?): Result<Unit> {
        val epoch = when (until) {
            null -> 0L
            "8h" -> System.currentTimeMillis() + 8 * 60 * 60 * 1000L
            "1w" -> System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L
            "always" -> System.currentTimeMillis() + 50L * 365 * 24 * 60 * 60 * 1000L
            else -> 0L
        }
        mutateConversation(conversationId) {
            it.copy(mutedUntilEpoch = epoch, isMuted = epoch > System.currentTimeMillis())
        }
        val body = kotlinx.serialization.json.buildJsonObject {
            // Explicit JsonPrimitive/JsonNull — JsonObjectBuilder.put has no
            // String overload at the resolved serialization version.
            put("userId", kotlinx.serialization.json.JsonPrimitive(viewerId ?: ""))
            put("until", until?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        val result = api.patchAction("/api/conversations/$conversationId/mute", body).toResult()
        refreshConversations()
        return result
    }

    override suspend fun archive(conversationId: String, archived: Boolean): Result<Unit> {
        mutateConversation(conversationId) { it.copy(isArchived = archived) }
        val result = api.patchAction(
            "/api/conversations/$conversationId/archive",
            PulseApi.jsonOf("userId" to (viewerId ?: ""), "archived" to archived),
        ).toResult()
        refreshConversations()
        return result
    }

    override suspend fun markUnread(conversationId: String, on: Boolean): Result<Unit> {
        mutateConversation(conversationId) { it.copy(myManualUnread = on) }
        val result = api.patchAction(
            "/api/conversations/$conversationId/mark-unread",
            PulseApi.jsonOf("userId" to (viewerId ?: ""), "on" to on),
        ).toResult()
        if (result.isFailure) {
            mutateConversation(conversationId) { it.copy(myManualUnread = !on) }
        }
        return result
    }

    // Wave 6 route fixes (audit A): block carries the ACTOR in the body, and
    // unblock is DELETE /block?userId= — POST /unblock does NOT exist on the
    // wire (the same defect iOS shipped; both platforms converge now).
    override suspend fun block(userId: String): Result<Unit> =
        when (val r = api.blockUser(userId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun unblock(userId: String): Result<Unit> =
        when (val r = api.unblockUser(userId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun report(userId: String, reason: String, details: String?): Result<Unit> =
        when (val r = api.reportUser(userId, viewerId ?: "", reason, details)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── N10 home-page era ──────────────────────────────────────

    override suspend fun createSelfChat(): Result<Conversation> =
        when (val r = api.createSelfChat(viewerId ?: "")) {
            is PulseResult.Success -> {
                val conversation = r.value.toDomain(viewerId)
                conversationDao.upsertAll(listOf(ConversationEntity.from(conversation)))
                Result.success(conversation)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    private fun isoToEpochMs(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    override suspend fun stories(): Result<List<StoryGroup>> {
        val key = "stories:" + (viewerId ?: "")
        val nowMs = System.currentTimeMillis()
        return when (val r = api.stories(viewerId ?: "")) {
            is PulseResult.Success -> {
                // Offline cache: persist the CANONICAL wire page (pre-mapping)
                // so a cached replay walks the exact same mapper — including
                // the D2 expiry filter — as a live fetch.
                runCatching {
                    storyDao.upsert(
                        StoryCacheEntity(
                            key = key,
                            groupsJson = PulseJson.encodeToString(StoriesPageDto.serializer(), r.value),
                            updatedAt = nowMs,
                        ),
                    )
                }
                Result.success(r.value.toDomainGroups(nowMs))
            }
            is PulseResult.Failure -> {
                val cached = runCatching { storyDao.get(key) }.getOrNull()
                val page = cached?.let {
                    runCatching {
                        PulseJson.decodeFromString(StoriesPageDto.serializer(), it.groupsJson)
                    }.getOrNull()
                }
                if (page != null) {
                    Result.success(page.toDomainGroups(nowMs))
                } else {
                    // Honest error only when there is nothing cached to serve.
                    Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
                }
            }
        }
    }

    override suspend fun createStory(caption: String, background: String?, imagePath: String?): Result<StoryItem> =
        when (val r = api.postStory(viewerId ?: "", caption, background, imagePath)) {
            is PulseResult.Success -> {
                val dto = r.value.story
                val item = dto?.let { it.toStoryItem() }
                if (item == null) {
                    Result.failure(IllegalStateException("VALIDATION: story missing in 201 body"))
                } else {
                    Result.success(item)
                }
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun markStoryViewed(storyId: String): Result<Int> =
        when (val r = api.markStoryViewed(storyId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.viewCount)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun storyViewers(storyId: String): Result<List<StoryViewer>> =
        when (val r = api.storyViewers(storyId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                r.value.viewers.map {
                    StoryViewer(
                        userId = it.userId,
                        name = it.name,
                        username = it.username,
                        color = it.color,
                        viewedAtIso = it.viewedAt,
                    )
                },
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteStory(storyId: String): Result<Unit> =
        when (val r = api.deleteStory(storyId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    /** Wire DTO → domain story (NO expiry filtering here — filter lives in [toDomainGroups]/the machine). */
    private fun StoryItemDto.toStoryItem(): StoryItem? {
        if (id.isBlank()) return null
        val created = isoToEpochMs(createdAt) ?: 0L
        return StoryItem(
            id = id,
            kind = if (imagePath != null) "image" else (kind ?: "text"),
            imagePath = imagePath,
            caption = caption,
            background = if (imagePath != null) "emerald" else background.ifBlank { "emerald" },
            createdAtEpochMs = created,
            expiresAtEpochMs = isoToEpochMs(expiresAt) ?: (created + STORY_TTL_MS),
            viewCount = viewCount,
            viewedByMe = viewedByMe,
            createdAtIso = createdAt,
            expiresAtIso = expiresAt,
        )
    }

    /**
     * DTO page → domain groups. WEB DEFECT D2 (client-side expiry): stories
     * with expiresAt <= now are dropped HERE, offline, before any grouping —
     * no network probes. `allSeen` is recomputed over the survivors so a
     * group whose only unseen story expired reads as seen. Group order is
     * kept AS RETURNED BY THE SERVER (mine first, others by newest story).
     */
    private fun StoriesPageDto.toDomainGroups(nowMs: Long): List<StoryGroup> = groups.mapNotNull { g ->
        val user = g.user ?: return@mapNotNull null
        val stories = g.stories.mapNotNull { it.toStoryItem() }.filter { it.expiresAtEpochMs > nowMs }
        if (stories.isEmpty()) return@mapNotNull null
        StoryGroup(
            user = StoryUser(id = user.id, name = user.name, username = user.username, color = user.color),
            mine = g.mine,
            allSeen = stories.all { it.viewedByMe },
            stories = stories,
        )
    }

    override suspend fun folders(): Result<List<FolderSummary>> = when (val r = api.folders(viewerId ?: "")) {
        is PulseResult.Success -> Result.success(
            r.value.folders.map { f ->
                FolderSummary(id = f.id, name = f.name, emoji = f.emoji, conversationIds = f.conversationIds)
            },
        )
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun mentions(): Result<List<MentionItem>> = when (val r = api.mentions(viewerId ?: "")) {
        is PulseResult.Success -> Result.success(
            r.value.items.map { m ->
                MentionItem(
                    messageId = m.messageId,
                    conversationId = m.conversationId,
                    conversationName = m.conversationName,
                    authorName = m.author?.name ?: "",
                    snippet = m.snippet,
                    createdAt = m.createdAt,
                )
            },
        )
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun searchMessages(query: String): Result<List<MessageHit>> {
        val id = viewerId ?: return Result.success(emptyList())
        return when (val r = api.search(id, query)) {
            is PulseResult.Success -> Result.success(
                r.value.messages.map { hit ->
                    MessageHit(
                        id = hit.id,
                        conversationId = hit.conversationId,
                        conversationName = hit.conversationName ?: "Chat",
                        isGroup = hit.isGroup,
                        senderName = hit.sender?.name ?: "",
                        senderColor = hit.sender?.color,
                        content = hit.content,
                        createdAt = hit.createdAt,
                        deleted = hit.deletedAt != null,
                        imageOnly = hit.imagePath != null && hit.content.isBlank(),
                        isFile = hit.filePath != null,
                        fileName = hit.fileName,
                    )
                },
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }
    }

    override suspend fun fullHistory(conversationId: String): Result<List<Message>> =
        when (val r = api.messages(conversationId, 500)) {
            is PulseResult.Success -> Result.success(r.value.messages.map { it.toDomain() })
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        api.deleteMessage(messageId, viewerId ?: "").toResult()

    override suspend fun exportChat(conversationId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val history = fullHistory(conversationId).getOrThrow()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val fileName = "pulse-${conversationId.take(12)}-${java.time.LocalDate.now()}.txt"
            File(dir, fileName).writeText(
                buildString {
                    appendLine("Pulse — exported chat")
                    appendLine("Conversation: $conversationId")
                    appendLine("Messages: ${history.size}")
                    appendLine("Exported: ${java.time.LocalDateTime.now()}")
                    appendLine()
                    for (m in history) {
                        val body = if (m.isDeleted) "(message deleted)" else m.body
                        appendLine("[${PulseTime.clock(m.createdAt)}] ${m.authorName}: $body")
                    }
                },
            )
            fileName
        }
    }

    override suspend fun clearMyMessages(conversationId: String): Result<Int> {
        val history = fullHistory(conversationId).getOrElse { return Result.failure(it) }
        val mine = history.filter { it.authorId == viewerId && !it.isDeleted }
        var cleared = 0
        for (message in mine) {
            if (api.deleteMessage(message.id, viewerId ?: "") is PulseResult.Success) cleared += 1
            // keep going — clear as many of my own messages as the server allows
        }
        if (cleared > 0) messageDao.deleteByIds(mine.map { it.id })
        scheduleConversationsRefresh()
        return Result.success(cleared)
    }

    private fun <T> PulseResult<T>.toResult(): Result<T> = when (this) {
        is PulseResult.Success -> Result.success(value)
        is PulseResult.Failure -> Result.failure(IllegalStateException("${kind.name}: $message"))
    }

    private fun reactionsJsonOf(m: Message): String? = if (m.reactions.isEmpty()) null else PulseJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(app.pulse.protocol.ReactionDto.serializer()),
        m.reactions.map { app.pulse.protocol.ReactionDto(emoji = it.emoji, userId = it.userId) },
    )

    /** One-row cache write shared by every Wave-2 read-modify-write path. */
    private suspend fun upsertMessage(m: Message) {
        messageDao.upsertAll(listOf(MessageEntity.from(m, reactionsJsonOf(m))))
    }

    // ── Wave 2 messaging depth (spec §0/§1 — routes verified live) ────

    override suspend fun transcribeMessage(messageId: String): Result<TranscribeOutcome> =
        when (val r = api.transcribe(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val outcome = TranscribeOutcome(
                    transcript = r.value.transcript,
                    transcribedAt = PulseTime.epochMs(r.value.transcribedAt).takeIf { it > 0L },
                    cached = r.value.cached,
                )
                // Targeted patch — never rewrites the row's other columns;
                // Room's messages Flow re-emits the updated row.
                messageDao.updateTranscription(
                    id = messageId,
                    transcript = outcome.transcript,
                    transcribedAt = r.value.transcribedAt,
                )
                Result.success(outcome)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── R1-W2F — F-MD-06 translation ──────────────────────────────

    /**
     * POST /api/messages/{id}/translate — server-side LLM, persisted per
     * language; the fresh row's `translations[0].text` is what renders.
     * Failures carry the server's honest copy (403 non-participant, 400
     * deleted/empty, 502 service down) through [apiExceptionOf].
     */
    override suspend fun translateMessage(messageId: String): Result<String> =
        when (val r = api.translate(messageId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun markMessageViewed(messageId: String) {
        when (val r = api.markViewed(messageId, viewerId ?: "")) {
            is PulseResult.Success -> upsertMessage(r.value.toDomain())
            is PulseResult.Failure -> Log.w(TAG, "markViewed failed: ${r.kind}: ${r.message}")
        }
    }

    override suspend fun createPoll(conversationId: String, question: String, options: List<String>): Result<Message> =
        when (val r = api.createPoll(conversationId, viewerId ?: "", question, options)) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                upsertMessage(message)
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun votePoll(pollId: String, optionId: String): Result<Message> =
        when (val r = api.votePoll(pollId, viewerId ?: "", optionId)) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                upsertMessage(message)
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun closePoll(pollId: String): Result<Message> =
        when (val r = api.closePoll(pollId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                upsertMessage(message)
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun unfurlMessage(messageId: String) {
        // Fire-and-forget (spec §1 row 8): absent preview / failures are
        // silently ignored — the link:preview relay covers the rest.
        when (val r = api.unfurl(messageId, viewerId ?: "")) {
            is PulseResult.Success -> r.value?.let { upsertMessage(it.toDomain()) }
            is PulseResult.Failure -> Log.w(TAG, "unfurl failed: ${r.kind}: ${r.message}")
        }
    }

    override suspend fun refreshSavedLibrary(): Result<List<SavedItem>> =
        when (val r = api.savedList(viewerId ?: "")) {
            is PulseResult.Success -> {
                val items = r.value.items
                // 1) the carried message rows into the cache — the library
                //    renders them offline afterwards (spec §1 row 14).
                messageDao.upsertAll(
                    items.map { MessageEntity.from(it.message.toDomain(), reactionsJsonOf(it.message.toDomain())) },
                )
                // 2) the savedMessages index rows.
                val savedRows = items.map { it.toSavedEntity() }
                savedDao.upsertAll(savedRows)
                // 3) prune — server truth: rows it no longer lists are gone.
                val keep = savedRows.map { it.messageId }.toSet()
                val stale = savedDao.all().filter { it.messageId !in keep }.map { it.messageId }
                if (stale.isNotEmpty()) savedDao.deleteByIds(stale)
                Result.success(items.map { it.toSavedItem() })
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override fun observeSavedLibrary(): Flow<List<SavedItem>> =
        savedDao.observeAll().map { rows ->
            rows.mapNotNull { row ->
                val entity = messageDao.byId(row.messageId) ?: return@mapNotNull null
                val conversation = conversationDao.byId(row.conversationId)
                SavedItem(
                    savedAt = PulseTime.epochMs(row.savedAt),
                    conversationId = row.conversationId,
                    conversationName = conversation?.title,
                    isGroup = conversation?.kind == "GROUP",
                    message = entity.toDomain(),
                )
            }
        }

    override suspend fun unsaveMessage(messageId: String): Result<Boolean> {
        val result = toggleMessageSave(messageId)
        if (result.getOrNull() == false) savedDao.deleteById(messageId)
        return result
    }

    override suspend fun refreshTopics(conversationId: String): Result<Unit> =
        when (val r = api.topics(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val topics = r.value.topics
                topicDao.upsertAll(topics.map { TopicEntity.from(conversationId, it.toDomain()) })
                // Prune rows for this conversation the server no longer lists
                // (deleted topics drop off the rail without a socket event).
                val keep = topics.map { it.id }.toSet()
                val stale = topicDao.all(conversationId).filter { it.id !in keep }.map { it.id }
                stale.forEach { topicDao.deleteById(it) }
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override fun observeTopics(conversationId: String): Flow<List<Topic>> =
        topicDao.observeFor(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createTopic(conversationId: String, name: String, emoji: String): Result<Topic> =
        when (val r = api.createTopic(conversationId, viewerId ?: "", name, emoji)) {
            is PulseResult.Success -> {
                refreshTopics(conversationId) // counts + ordering reconcile from truth
                Result.success(r.value.toDomain())
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteTopic(conversationId: String, topicId: String): Result<Unit> =
        when (val r = api.deleteTopic(topicId, viewerId ?: "")) {
            is PulseResult.Success -> {
                topicDao.deleteById(topicId)
                refreshTopics(conversationId)
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── Wave 3: native 1:1 calls — history cache + single-writer queue ──

    override fun observeCallLog(): Flow<List<CallLogEntry>> =
        callLogDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshCallLog(): Result<List<CallLogEntry>> =
        when (val r = api.callLogs(viewerId ?: "")) {
            is PulseResult.Success -> {
                val items = r.value.items.map { it.toDomain() }
                callLogDao.upsertAll(items.map { CallLogCacheEntity.from(it) })
                // Server is truth — prune rows it no longer lists (cap 50).
                callLogDao.deleteNotIn(items.map { it.id })
                Result.success(items)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun writeCallLog(entry: CallLogEntry): Result<Unit> {
        val payload = buildJsonObject {
            put("userId", entry.callerId)
            put("conversationId", entry.conversationId)
            put("peerId", entry.calleeId)
            put("kind", entry.kind.wire)
            put("status", entry.status.wire)
            if (entry.durationSec > 0) put("durationSec", entry.durationSec)
        }
        // Instant local visibility — the refresh reconciles the server id later.
        callLogDao.upsert(CallLogCacheEntity.from(entry))
        return postCallLog(payload)
    }

    override suspend fun flushCallLogQueue(): Result<Int> {
        val rows = callLogDao.queued(MAX_CALL_LOG_QUEUE)
        if (rows.isEmpty()) return Result.success(0)
        var flushed = 0
        for (row in rows) {
            // `continue` inside an inline lambda is experimental Kotlin —
            // the null-check form is semantically identical and stable.
            val payload = runCatching {
                PulseJson.parseToJsonElement(row.payloadJson).jsonObject
            }.getOrNull()
            if (payload == null) {
                Log.w(TAG, "call-log queue row ${row.id} unparseable — dropped")
                callLogDao.dequeueById(row.id)
                continue
            }
            when (val r = postCallLogRaw(payload)) {
                is PulseResult.Success -> {
                    callLogDao.dequeueById(row.id)
                    flushed += 1
                }
                is PulseResult.Failure ->
                    if (r.kind == PulseResult.Failure.Kind.NETWORK) {
                        // FIFO stop-at-first-network-failure (outbox parity);
                        // the row stays with a bumped attempt counter.
                        callLogDao.bumpAttempts(row.id)
                        return Result.success(flushed)
                    } else {
                        // Definitive 4xx verdict — the row is dead weight.
                        callLogDao.dequeueById(row.id)
                    }
            }
        }
        return Result.success(flushed)
    }

    override suspend fun emitCall(signal: CallSignalOut) {
        when (signal.event) {
            app.pulse.protocol.SocketEvents.CALL_OFFER -> socket.emitCallOffer(
                CallOfferDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, sdp = signal.sdp ?: "",
                    callerName = signal.callerName, callerColor = signal.callerColor, callerAvatar = signal.callerAvatar,
                ),
            )
            app.pulse.protocol.SocketEvents.CALL_ANSWER -> socket.emitCallAnswer(
                CallAnswerDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, sdp = signal.sdp ?: "",
                ),
            )
            app.pulse.protocol.SocketEvents.CALL_ICE -> socket.emitCallIce(
                CallIceDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire,
                    candidate = signal.candidate ?: "", sdpMid = signal.sdpMid, sdpMLineIndex = signal.sdpMLineIndex,
                ),
            )
            app.pulse.protocol.SocketEvents.CALL_REJECT -> socket.emitCallReject(
                CallRejectDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, reason = signal.reason,
                ),
            )
            app.pulse.protocol.SocketEvents.CALL_CANCEL -> socket.emitCallCancel(
                CallCancelDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, reason = signal.reason ?: "cancel",
                ),
            )
            app.pulse.protocol.SocketEvents.CALL_HANGUP -> socket.emitCallHangup(
                CallHangupDto(
                    callId = signal.callId, conversationId = signal.conversationId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, durationSec = signal.durationSec,
                ),
            )
        }
    }

    // ── Wave 5 voice rooms / stage / space — best-effort socket emits ──
    // Same contract as emitCall: disconnected/offline = silent no-op (the
    // engines re-join on the next connect); these NEVER throw.

    override suspend fun emitVoiceJoin(conversationId: String, user: PulseVoiceUser) {
        socket.emitVoiceJoin(conversationId, user)
    }

    override suspend fun emitVoiceLeave(conversationId: String) {
        socket.emitVoiceLeave(conversationId)
    }

    override suspend fun emitVoicePtt(conversationId: String, userId: String, on: Boolean) {
        socket.emitVoicePtt(conversationId, userId, on)
    }

    override suspend fun emitVoiceChunk(conversationId: String, userId: String, seq: Long, data: String) {
        socket.emitVoiceChunk(conversationId, userId, seq, data)
    }

    override suspend fun emitVoiceTranscript(conversationId: String, userId: String, text: String) {
        socket.emitVoiceTranscript(conversationId, userId, text)
    }

    override suspend fun emitStageJoin(conversationId: String, user: PulseVoiceUser, asHost: Boolean) {
        socket.emitStageJoin(conversationId, user, asHost)
    }

    override suspend fun emitStageHand(conversationId: String, userId: String, raised: Boolean) {
        socket.emitStageHand(conversationId, userId, raised)
    }

    override suspend fun emitStageApprove(conversationId: String, byUserId: String, targetUserId: String) {
        socket.emitStageApprove(conversationId, byUserId, targetUserId)
    }

    override suspend fun emitStageMute(conversationId: String, byUserId: String, targetUserId: String) {
        socket.emitStageMute(conversationId, byUserId, targetUserId)
    }

    override suspend fun emitStageEnd(conversationId: String, byUserId: String) {
        socket.emitStageEnd(conversationId, byUserId)
    }

    override suspend fun emitStageLeave(conversationId: String) {
        socket.emitStageLeave(conversationId)
    }

    override suspend fun emitSpaceJoin(conversationId: String, user: PulseVoiceUser) {
        socket.emitSpaceJoin(conversationId, user)
    }

    override suspend fun emitSpaceMove(conversationId: String, x: Double, y: Double) {
        socket.emitSpaceMove(conversationId, x, y)
    }

    override suspend fun emitSpaceLeave(conversationId: String) {
        socket.emitSpaceLeave(conversationId)
    }

    override suspend fun transcribeVoice(
        conversationId: String,
        requesterId: String,
        audioBase64: String,
    ): Result<VoiceTranscriptResultDto> =
        when (val r = api.transcribeVoice(conversationId, requesterId, audioBase64)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── Wave 7 — collaboration & hub ───────────────────────────

    override suspend fun createRedPacket(conversationId: String, total: Long, count: Int, note: String?): Result<RedPacketCreateResultDto> =
        when (val r = api.createRedPacket(viewerId ?: "", conversationId, total, count, note)) {
            is PulseResult.Success -> {
                r.value.message?.let { upsertMessage(it.toDomain()) }
                scheduleConversationsRefresh()
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun redPacket(packetId: String): Result<RedPacketDetailDto> =
        when (val r = api.redPacket(packetId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun grabRedPacket(packetId: String): Result<RedPacketGrabResultDto> =
        when (val r = api.grabRedPacket(packetId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun whiteboard(conversationId: String, since: Long?): Result<WhiteboardPageDto> =
        when (val r = api.whiteboard(conversationId, viewerId ?: "", since)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun postWhiteboardStrokes(conversationId: String, strokes: List<WhiteboardStrokePostDto>): Result<WhiteboardPostResultDto> =
        when (val r = api.postWhiteboardStrokes(conversationId, viewerId ?: "", strokes)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun undoWhiteboardStroke(conversationId: String): Result<WhiteboardUndoResultDto> =
        when (val r = api.undoWhiteboardStroke(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun clearWhiteboard(conversationId: String): Result<WhiteboardClearResultDto> =
        when (val r = api.clearWhiteboard(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun kanbanBoard(conversationId: String): Result<KanbanPageDto> =
        when (val r = api.kanbanBoard(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createKanbanCard(conversationId: String, title: String?, column: String?, assigneeId: String?, messageId: String?): Result<KanbanCardDto> =
        when (val r = api.createKanbanCard(conversationId, viewerId ?: "", title, column, assigneeId, messageId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun updateKanbanCard(cardId: String, title: String?, column: String?, assigneeId: String?, clearAssignee: Boolean, position: Long?): Result<KanbanCardDto> =
        when (val r = api.updateKanbanCard(cardId, viewerId ?: "", title, column, assigneeId, clearAssignee, position)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteKanbanCard(cardId: String): Result<Unit> =
        when (val r = api.deleteKanbanCard(cardId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun events(conversationId: String): Result<EventsPageDto> =
        when (val r = api.events(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createEvent(conversationId: String, title: String, startsAtIso: String, description: String?, location: String?): Result<GroupEventDto> =
        when (val r = api.createEvent(conversationId, viewerId ?: "", title, startsAtIso, description, location)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteEvent(eventId: String): Result<Unit> =
        when (val r = api.deleteEvent(eventId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun rsvpEvent(eventId: String, status: String): Result<RsvpResultDto> =
        when (val r = api.rsvpEvent(eventId, viewerId ?: "", status)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun checkinEvent(eventId: String): Result<CheckinResultDto> =
        when (val r = api.checkinEvent(eventId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun reminders(dueOnly: Boolean): Result<RemindersPageDto> =
        when (val r = api.reminders(viewerId ?: "", dueOnly)) {
            is PulseResult.Success -> {
                if (!dueOnly) runCatching {
                    cachePut("reminders:${viewerId ?: "anon"}", PulseJson.encodeToString(RemindersPageDto.serializer(), r.value))
                }
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createReminder(conversationId: String, messageId: String?, note: String?, remindAtIso: String): Result<ReminderItemDto> =
        when (val r = api.createReminder(viewerId ?: "", conversationId, messageId, note, remindAtIso)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun resolveReminder(reminderId: String): Result<ReminderResolveDto> =
        when (val r = api.resolveReminder(reminderId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteReminder(reminderId: String): Result<Unit> =
        when (val r = api.deleteReminder(reminderId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── R1-W2A — quick phrases (F-MS-29) ──────────────────────────────

    override suspend fun phrases(): Result<List<QuickPhrase>> =
        when (val r = api.phrases(viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                r.value.phrases
                    .map { QuickPhrase(id = it.id, text = it.text, position = it.position) }
                    .sortedBy { it.position },
            )
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun addPhrase(text: String): Result<QuickPhrase> =
        when (val r = api.createPhrase(viewerId ?: "", text)) {
            is PulseResult.Success -> Result.success(
                QuickPhrase(
                    id = r.value.phrase?.id.orEmpty(),
                    text = r.value.phrase?.text.orEmpty().ifBlank { text },
                    position = r.value.phrase?.position ?: 0,
                ),
            )
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun deletePhrase(phraseId: String): Result<Unit> =
        when (val r = api.deletePhrase(viewerId ?: "", phraseId)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun createGame(conversationId: String, opponentId: String?): Result<GameMatchCreateResultDto> =
        when (val r = api.createGame(viewerId ?: "", conversationId, opponentId)) {
            is PulseResult.Success -> {
                r.value.message?.let { upsertMessage(it.toDomain()) }
                scheduleConversationsRefresh()
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun games(conversationId: String): Result<GamesPageDto> =
        when (val r = api.games(conversationId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun game(matchId: String): Result<GameDetailDto> =
        when (val r = api.game(matchId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun gameMove(matchId: String, cell: Int): Result<GameDetailDto> =
        when (val r = api.gameMove(matchId, viewerId ?: "", cell)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun joinGame(matchId: String): Result<GameDetailDto> =
        when (val r = api.joinGame(matchId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createTournament(conversationId: String, name: String): Result<TournamentCreateResultDto> =
        when (val r = api.createTournament(viewerId ?: "", conversationId, name)) {
            is PulseResult.Success -> {
                r.value.message?.let { upsertMessage(it.toDomain()) }
                scheduleConversationsRefresh()
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun tournaments(conversationId: String): Result<TournamentsPageDto> =
        when (val r = api.tournaments(conversationId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun tournament(tournamentId: String): Result<TournamentSummaryDto> =
        when (val r = api.tournament(tournamentId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun finishTournament(tournamentId: String): Result<TournamentSummaryDto> =
        when (val r = api.finishTournament(tournamentId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun joinTournament(tournamentId: String): Result<TournamentJoinResultDto> =
        when (val r = api.joinTournament(tournamentId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun leaderboard(conversationId: String?): Result<LeaderboardPageDto> =
        when (val r = api.leaderboard(conversationId, viewerId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun wallet(): Result<WalletPageDto> =
        when (val r = api.wallet(viewerId ?: "")) {
            is PulseResult.Success -> {
                runCatching {
                    cachePut(walletKey(), PulseJson.encodeToString(WalletPageDto.serializer(), r.value))
                }
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun checkinWallet(): Result<CheckinWalletResultDto> =
        when (val r = api.checkinWallet(viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun transferCoins(toUsername: String, amount: Long, note: String?): Result<TransferResultDto> =
        when (val r = api.transferCoins(viewerId ?: "", toUsername, amount, note)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun swapRates(): Result<SwapPageDto> =
        when (val r = api.swapRates()) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun swap(direction: String, amount: Long): Result<SwapResultDto> =
        when (val r = api.swap(viewerId ?: "", direction, amount)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun hubTasks(): Result<HubTasksPageDto> =
        when (val r = api.hubTasks(viewerId ?: "")) {
            is PulseResult.Success -> {
                runCatching {
                    cachePut("hub:tasks:${viewerId ?: "anon"}", PulseJson.encodeToString(HubTasksPageDto.serializer(), r.value))
                }
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createHubTask(title: String, status: String?): Result<HubTaskDto> =
        when (val r = api.createHubTask(viewerId ?: "", title, status)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun updateHubTask(taskId: String, title: String?, status: String?): Result<HubTaskDto> =
        when (val r = api.updateHubTask(taskId, viewerId ?: "", title, status)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteHubTask(taskId: String): Result<Unit> =
        when (val r = api.deleteHubTask(taskId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun market(): Result<MarketPageDto> =
        when (val r = api.market(viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createListing(title: String, description: String?, price: Long): Result<MarketListingDto> =
        when (val r = api.createListing(viewerId ?: "", title, description, price)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun buyListing(listingId: String): Result<MarketBuyResultDto> =
        when (val r = api.buyListing(listingId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun hubLogs(limit: Int, kind: String?): Result<HubLogsPageDto> =
        when (val r = api.hubLogs(limit, kind)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun appInstallState(appId: String): Result<AppInstallStateDto> =
        when (val r = api.appInstallState(appId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun installApp(appId: String): Result<AppInstallResultDto> =
        when (val r = api.installApp(appId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun uninstallApp(appId: String): Result<AppInstallResultDto> =
        when (val r = api.uninstallApp(appId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun appCommunity(appId: String): Result<AppCommunityDto> =
        when (val r = api.appCommunity(appId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun joinAppCommunity(appId: String): Result<AppCommunityDto> =
        when (val r = api.joinAppCommunity(appId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // Wave 7 read-through snapshot cache (story_cache precedent): network
    // success overwrites the blob, network failure serves the last good page.

    private suspend fun cachePut(key: String, json: String) {
        runCatching { wave7Dao.upsert(Wave7CacheEntity(key = key, json = json, updatedAt = System.currentTimeMillis())) }
    }

    private suspend fun cacheGet(key: String): String? =
        runCatching { wave7Dao.get(key)?.json }.getOrNull()

    /** Snapshot key for the hub wallet (per viewer). */
    private fun walletKey() = "hub:wallet:${viewerId ?: "anon"}"

    /** Read the cached wallet page without hitting the network. */
    override suspend fun cachedWallet(): WalletPageDto? =
        cacheGet(walletKey())?.let { blob ->
            runCatching { PulseJson.decodeFromString(WalletPageDto.serializer(), blob) }.getOrNull()
        }

    /** Read cached hub tasks without hitting the network. */
    override suspend fun cachedHubTasks(): HubTasksPageDto? =
        cacheGet("hub:tasks:${viewerId ?: "anon"}")?.let { blob ->
            runCatching { PulseJson.decodeFromString(HubTasksPageDto.serializer(), blob) }.getOrNull()
        }

    /** Read cached reminders (due badge survives cold start). */
    override suspend fun cachedReminders(): RemindersPageDto? =
        cacheGet("reminders:${viewerId ?: "anon"}")?.let { blob ->
            runCatching { PulseJson.decodeFromString(RemindersPageDto.serializer(), blob) }.getOrNull()
        }

    // ── Wave 6 — social graph & discovery ──────────────────────

    override suspend fun userProfile(userId: String): Result<UserProfile> =
        when (val r = api.fullUser(userId)) {
            is PulseResult.Success -> Result.success(r.value.toUserProfile())
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun patchProfile(patch: ProfilePatch): Result<UserProfile> {
        val body = buildJsonObject {
            patch.name?.let { put("name", it) }
            patch.about?.let { put("about", it) }
            patch.color?.let { put("color", it) }
            patch.avatar?.let { put("avatar", it) }
            patch.statusEmoji?.let { put("statusEmoji", it) }
            patch.statusText?.let { put("statusText", it) }
            // username is "explicit key" on the wire: null = untouched, "" = clear.
            patch.username?.let { put("username", it) }
        }
        return when (val r = api.patchUser(viewerId ?: "", body)) {
            is PulseResult.Success -> Result.success(r.value.toUserProfile())
            is PulseResult.Failure -> Result.failure(
                OnboardingError.of(r).takeIf { r.status == 400 || r.status == 409 }
                    ?: IllegalStateException("${r.kind}: ${r.message}"),
            )
        }
    }

    override suspend fun userStats(userId: String): Result<UserStats> =
        when (val r = api.userStats(userId)) {
            is PulseResult.Success -> Result.success(
                UserStats(
                    messages = r.value.messages,
                    reactions = r.value.reactions,
                    photos = r.value.photos,
                    voiceNotes = r.value.voiceNotes,
                    chats = r.value.chats,
                    groups = r.value.groups,
                    days = r.value.days,
                    joinedAtIso = r.value.joinedAt,
                    lastSeenIso = r.value.lastSeenAt,
                ),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun safetyState(peerId: String): Result<SafetyState> =
        when (val r = api.safetyState(peerId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                SafetyState(r.value.peerId, r.value.safetyNumber, r.value.verified, r.value.verifiedAt),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun verifyPeer(peerId: String): Result<SafetyState> =
        when (val r = api.safetyVerify(peerId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                // Settle-confirmed: the state is ONLY what the server answered.
                SafetyState(peerId = peerId, verified = r.value.verified, verifiedAtIso = r.value.verifiedAt),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun unverifyPeer(peerId: String): Result<SafetyState> =
        when (val r = api.safetyUnverify(peerId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                SafetyState(peerId = peerId, verified = r.value.verified, verifiedAtIso = null),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun blockState(userId: String): Result<Boolean> =
        when (val r = api.blockState(userId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.blocked)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun blockedAccounts(): Result<List<BlockedAccount>> =
        when (val r = api.blockedAccounts(viewerId ?: "")) {
            is PulseResult.Success -> Result.success(
                r.value.blocks.map {
                    BlockedAccount(
                        id = it.id,
                        name = it.name,
                        handle = it.username,
                        avatar = it.avatar,
                        color = it.color,
                        blockedAtIso = it.blockedAt,
                    )
                },
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun myReportReasons(userId: String): Result<List<String>> =
        when (val r = api.reportReasons(userId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.reasons.map { it.reason })
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun invitePreview(code: String): Result<InvitePreview> =
        when (val r = api.invitePreview(code, viewerId)) {
            is PulseResult.Success -> Result.success(
                InvitePreview(
                    code = r.value.code,
                    conversationId = r.value.conversationId,
                    name = r.value.name,
                    memberCount = r.value.memberCount,
                    alreadyMember = r.value.alreadyMember,
                ),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun joinInvite(code: String): Result<InviteJoinOutcome> =
        when (val r = api.inviteJoin(code, viewerId ?: "")) {
            is PulseResult.Success -> {
                // The relay bumps every participant's list — refresh ours now.
                runCatching { refreshConversations() }
                Result.success(InviteJoinOutcome(r.value.conversationId, r.value.alreadyMember))
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun channels(mineOnly: Boolean): Result<List<Channel>> =
        when (val r = api.channels(viewerId ?: "", mineOnly)) {
            is PulseResult.Success -> Result.success(r.value.channels.map { it.toChannel() })
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createChannel(name: String, description: String?, photo: String?): Result<Channel> =
        when (val r = api.createChannel(viewerId ?: "", name, description, photo)) {
            is PulseResult.Success -> {
                val channel = r.value.channel?.toChannel()
                if (channel == null) {
                    Result.failure(IllegalStateException("VALIDATION: channel missing in 201 body"))
                } else {
                    runCatching { refreshConversations() }
                    Result.success(channel)
                }
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun subscribeChannel(channelId: String): Result<Boolean> =
        when (val r = api.subscribeChannel(channelId, viewerId ?: "")) {
            is PulseResult.Success -> {
                runCatching { refreshConversations() }
                Result.success(r.value.already)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun unsubscribeChannel(channelId: String): Result<Unit> =
        when (val r = api.unsubscribeChannel(channelId, viewerId ?: "")) {
            is PulseResult.Success -> {
                runCatching { refreshConversations() }
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createFolder(name: String, emoji: String): Result<FolderSummary> =
        when (val r = api.createFolder(viewerId ?: "", name, emoji)) {
            is PulseResult.Success -> Result.success(r.value.toFolderSummary())
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun updateFolder(folderId: String, name: String?, emoji: String?, position: Int?): Result<Unit> =
        when (val r = api.patchFolder(folderId, name, emoji, position)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteFolder(folderId: String): Result<Unit> =
        when (val r = api.deleteFolder(folderId)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun setFolderConversations(folderId: String, conversationIds: List<String>): Result<Unit> =
        when (val r = api.setFolderConversations(folderId, conversationIds)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    /** POST one terminal call-log row; network-class failures enqueue the EXACT payload. */
    private suspend fun postCallLog(payload: kotlinx.serialization.json.JsonObject): Result<Unit> =
        when (val r = postCallLogRaw(payload)) {
            is PulseResult.Success -> {
                // Reconcile the authoritative row (server id + resolved peer).
                r.value.item?.let {
                    callLogDao.upsert(CallLogCacheEntity.from(it.toDomain()))
                }
                Result.success(Unit)
            }
            is PulseResult.Failure ->
                if (r.kind == PulseResult.Failure.Kind.NETWORK) {
                    callLogDao.enqueue(
                        CallLogQueueEntity(payloadJson = payload.toString(), createdAt = java.time.Instant.now().toString()),
                    )
                    Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
                } else {
                    Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
                }
        }

    private suspend fun postCallLogRaw(payload: kotlinx.serialization.json.JsonObject): PulseResult<CallLogCreatedDto> {
        val userId = payload["userId"]?.jsonPrimitive?.content ?: ""
        val conversationId = payload["conversationId"]?.jsonPrimitive?.content ?: ""
        val peerId = payload["peerId"]?.jsonPrimitive?.content ?: ""
        val kind = payload["kind"]?.jsonPrimitive?.content ?: "voice"
        val status = payload["status"]?.jsonPrimitive?.content ?: "missed"
        val durationSec = payload["durationSec"]?.jsonPrimitive?.longOrNull ?: 0L
        return api.createCallLog(userId, conversationId, peerId, kind, status, durationSec)
    }

    companion object {
        private const val TAG = "PulseRepo"
        private const val OFFLINE_BACKOFF_MS = 60_000L

        /** Web MAX_QUEUE parity for the call-log single-writer queue. */
        const val MAX_CALL_LOG_QUEUE = 50

        /** Web MAX_QUEUE parity — the outbox never holds more rows. */
        const val MAX_OUTBOX = 50

        /** Web MAX_DRAFT parity. */
        const val MAX_DRAFT = 2000

        /** Self-heal beat while a flush is pending (plan §6). */
        const val SELF_HEAL_MS = 20_000L

        /** Handles nobody may claim — offline safety net mirroring registry/handles.json. */
        private val BUILT_IN_RESERVED = setOf(
            "admin", "administrator", "root", "system", "support", "help", "team",
            "official", "moderator", "mod", "pulse", "staff", "security", "noreply",
            "notifications", "bot", "api", "gs",
        )
    }
}

// ── wire → domain mappers (kept beside the cache they feed) ──────

fun ConversationSummaryDto.toDomain(viewerId: String?): Conversation {
    val others = members.filter { it.id != viewerId }
    val title = name
        ?: others.firstOrNull()?.name
        ?: members.firstOrNull()?.name
        ?: "Conversation"
    val kind = when {
        isSelf == true -> Conversation.Kind.DM
        broadcastMode == true -> Conversation.Kind.CHANNEL
        isGroup -> Conversation.Kind.GROUP
        else -> Conversation.Kind.DM
    }
    val last = lastMessage
    val collapsed = last?.content?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    val isImage = last?.imagePath != null && collapsed.isEmpty()
    val isAudio = !isImage && last?.audioPath != null && collapsed.isEmpty()
    val isFile = !isImage && !isAudio && last?.kind == "file" && last?.filePath != null
    val mutedEpoch = PulseTime.epochMs(mutedUntil)
    return Conversation(
        id = id,
        kind = kind,
        title = title,
        avatar = photo ?: others.firstOrNull()?.avatar,
        lastMessagePreview = if (last == null) null else collapsed,
        lastMessageAuthorName = last?.sender?.name,
        lastMessageKind = last?.kind,
        // Web row stamp prefers the last message's time, falling back to updatedAt.
        lastActivityAt = last?.createdAt ?: updatedAt,
        unreadCount = unreadCount,
        isPinned = !pinnedAt.isNullOrBlank(),
        // Mute truth = the window is in the future, not merely non-null (spec §7.4).
        isMuted = mutedEpoch > System.currentTimeMillis(),
        isArchived = !archivedAt.isNullOrBlank(),
        memberIds = members.map { it.id },
        memberNames = members.map { it.name },
        accentColor = others.firstOrNull()?.color ?: members.firstOrNull()?.color,
        streakCount = streakCountOf(myStreak),
        myDraft = myDraft,
        isSelf = isSelf == true,
        myManualUnread = myManualUnread == true,
        streakAtRiskCount = streakCountOf(deadStreak),
        streakLost = streakCountOf(lostStreak) > 0,
        lastMessageMine = last != null && last.senderId == viewerId,
        lastMessageDeleted = last?.deletedAt != null,
        lastMessageIsReply = last?.replyTo != null || last?.parentId != null,
        lastMessageIsImage = isImage,
        lastMessageIsAudio = isAudio,
        lastMessageIsFile = isFile,
        lastMessageFileName = last?.fileName,
        mutedUntilEpoch = mutedEpoch,
        otherUserId = others.firstOrNull()?.id ?: members.firstOrNull()?.id,
        isChannel = broadcastMode == true,
        members = members.map { dto ->
            ConversationMember(
                id = dto.id,
                name = dto.name,
                color = dto.color,
                lastReadAt = PulseTime.epochMs(dto.lastReadAt).takeIf { it > 0 },
                role = dto.role,
            )
        },
    )
}

/** myStreak/deadStreak/lostStreak are {"count": n} on the wire — tolerate ints too. */
private fun streakCountOf(el: kotlinx.serialization.json.JsonElement?): Int = runCatching {
    (el?.jsonObject?.get("count") as? JsonPrimitive)?.intOrNull
        ?: el?.jsonPrimitive?.intOrNull
        ?: 0
}.getOrDefault(0)

fun UserDto.toDomain(): User = User(
    id = id,
    name = name,
    handle = username ?: "",
    avatar = avatar,
    bio = bio,
    lastSeen = lastSeen,
    verified = verified == true,
    color = color,
    statusEmoji = statusEmoji,
    statusText = statusText,
)

/** Full AppUser row (GET/PATCH /api/users/{id}) → the profile-page model. */
fun FullUserDto.toUserProfile(): UserProfile = UserProfile(
    id = id,
    name = name,
    handle = username,
    about = about,
    color = color,
    avatar = avatar,
    statusEmoji = statusEmoji,
    statusText = statusText,
    createdAtIso = createdAt,
    lastSeenIso = lastSeenAt,
)

/** One directory row (GET /api/channels) → the channels-page model. */
fun ChannelDto.toChannel(): Channel = Channel(
    id = id,
    name = name?.ifBlank { "Channel" } ?: "Channel",
    description = description,
    memberCount = memberCount,
    isSubscribed = isSubscribed,
    unread = unread,
    preview = preview,
    photo = photo,
)

/** Wire folder row → the rail model (POST/PATCH /api/folders bodies). */
fun FolderDto.toFolderSummary(): FolderSummary = FolderSummary(
    id = id,
    name = name,
    emoji = emoji,
    conversationIds = conversationIds,
)

fun ChatMessageDto.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    authorId = senderId,
    authorName = sender?.name ?: "Unknown",
    kind = kindOf(kind),
    body = content,
    createdAt = createdAt,
    editedAt = editedAt,
    deletedAt = deletedAt,
    // THREAD vs QUOTE (spec §1.1): parentId is the thread root, replyTo is
    // the inline quote — they are DIFFERENT axes and never merged.
    replyToId = replyTo?.id,
    threadRootId = parentId,
    pinnedAt = pinnedAt,
    viewedOnce = viewOnce == true,
    reactions = reactions.mapNotNull { r ->
        val e = r.emoji ?: return@mapNotNull null
        // Wire groups per emoji ({ emoji, userIds, count }); expand to per-user rows.
        val ids = r.userIds ?: listOfNotNull(r.userId)
        ids.map { Reaction(emoji = e, userId = it) }
    }.flatten(),
    replyToBody = replyTo?.content,
    replyToAuthor = replyTo?.sender?.name,
    senderColor = sender?.color,
    viaAutomation = viaAutomation == true,
    durationMs = durationMs,
    imagePath = imagePath,
    audioPath = audioPath,
    filePath = filePath,
    fileName = fileName,
    fileSize = fileSize,
    viewOnce = viewOnce == true,
    // ── Wave 2 depth (tolerant: absent/garbled keys degrade to null) ──
    viewedAt = PulseTime.epochMs(viewedAt).takeIf { it > 0L },
    transcript = transcript,
    transcribedAt = PulseTime.epochMs(transcribedAt).takeIf { it > 0L },
    // Poll pick derives from options[].votedBy via PollInfo.pickFor — the
    // wire myOptionId is actor-relative on relays and NEVER trusted (spec §1 row 2).
    poll = poll.decodePollDto()?.toInfo(),
    linkPreview = linkPreview.decodeLinkPreviewDto()?.toInfo(),
    topicId = topicId,
    // Wave 7 rich objects ride payload as a RAW JSON STRING (red packet / game /
    // tournament carriers) — cards parse tolerantly via PulseWave7Logic.
    payload = payload?.toString()?.takeIf { it != "null" && it != "{}" },
    // REM-A — incognito + disappearing (F-MS-17/19): wire carries the anon flag,
    // the deterministic alias, and the purge deadline (ISO → epoch ms).
    anon = anon == true,
    anonAlias = anonAlias,
    expiresAtEpochMs = PulseTime.epochMs(expiresAt).takeIf { it > 0L },
)

// ── Wave 2 wire → domain mappers (saved library + topics) ──────

fun SavedItemDto.toSavedItem(): SavedItem = SavedItem(
    savedAt = PulseTime.epochMs(savedAt),
    conversationId = conversation.id,
    conversationName = conversation.name,
    isGroup = conversation.isGroup,
    message = message.toDomain(),
)

fun SavedItemDto.toSavedEntity(): SavedMessageEntity = SavedMessageEntity(
    messageId = message.id,
    conversationId = conversation.id,
    savedAt = savedAt,
)

fun TopicDto.toDomain(): Topic = Topic(
    id = id,
    name = name,
    emoji = emoji,
    lastMessageAt = PulseTime.epochMs(lastMessageAt).takeIf { it > 0L },
    messageCount = messageCount,
)

/** Wire ScheduledItem row → the domain model (tolerant: blanks stay blank). */
fun ScheduledItemDto.toDomainScheduled(): ScheduledItem = ScheduledItem(
    id = id,
    conversationId = conversationId,
    content = content,
    scheduledAtIso = scheduledAt,
    cancelledAtIso = cancelledAt,
    cancelledReason = cancelledReason,
)

private fun kindOf(wire: String): Message.Kind = when (wire) {
    "text" -> Message.Kind.TEXT
    "image" -> Message.Kind.IMAGE
    "voice" -> Message.Kind.VOICE
    "video" -> Message.Kind.VIDEO
    "file" -> Message.Kind.FILE
    "poll" -> Message.Kind.POLL
    "red_packet" -> Message.Kind.RED_PACKET
    "game" -> Message.Kind.GAME
    "tournament" -> Message.Kind.TOURNAMENT
    // R1-W2A (F-MS-24): sticker rows carry payload {emoji,pack} and render
    // as large emoji — never the system-pill fallback.
    "sticker" -> Message.Kind.STICKER
    // R1-W2F (F-MD-07): pin rows carry payload {lat,lng,label}.
    "location" -> Message.Kind.LOCATION
    else -> Message.Kind.SYSTEM
}

/**
 * Identity-flow failure that keeps the wire's error/code/suggestion intact —
 * the onboarding screen branches on exactly these (web parity with the
 * createUserRequest 409 handling in onboarding-screen.tsx).
 */
class OnboardingError(
    val status: Int?,
    val code: String?,
    val suggestion: String?,
    message: String?,
) : Exception(message ?: "Request failed") {
    val isUsernameTaken: Boolean get() = code == "username_taken"
    val isNameClash: Boolean get() = status == 409 && !isUsernameTaken

    companion object {
        fun of(f: PulseResult.Failure): OnboardingError {
            // Transport-level failures carry raw engine strings ("CLEARTEXT
            // communication … not permitted") — humans get real copy instead.
            val human = if (f.status == null) "Can't reach the Pulse server — check your connection." else f.message
            return OnboardingError(f.status, f.code, f.suggestion, human)
        }
    }
}

/** GET /api/calls row → domain entry (peer resolved server-side relative to the viewer). */
private fun CallLogItemDto.toDomain(): CallLogEntry = CallLogEntry(
    id = id,
    conversationId = conversationId,
    callerId = callerId,
    calleeId = calleeId,
    kind = CallKind.of(kind),
    status = CallStatus.of(status),
    durationSec = durationSec,
    startedAt = startedAt,
    outgoing = outgoing,
    peer = peer?.let { CallPeer(id = it.id, name = it.name, color = it.color, avatar = it.avatar) },
)

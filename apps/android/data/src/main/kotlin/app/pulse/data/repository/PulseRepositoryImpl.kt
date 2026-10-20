package app.pulse.data.repository

import app.pulse.domain.sync.withConversationExpiry
import app.pulse.domain.sync.withSavedMessageExpiry
import app.pulse.domain.sync.withMessageExpiry

import app.pulse.protocol.objectPayloadText
import kotlinx.coroutines.ensureActive

import androidx.room.withTransaction
import app.pulse.data.local.PulseDatabase
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
import app.pulse.domain.model.Automation
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.CallStatus
import app.pulse.domain.model.Channel
// R1-W2F - per-conversation themes (F-FX-05).
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.GroupLeave
import app.pulse.domain.model.GroupCallMember
import app.pulse.domain.model.GroupCallProbe
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
import app.pulse.domain.model.SendReceipt
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.model.StoryUser
import app.pulse.domain.model.StoryViewer
import app.pulse.domain.model.StreakSnapshot
import app.pulse.domain.model.Topic
import app.pulse.domain.model.TranscribeOutcome
import app.pulse.domain.model.User
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.model.Webhook
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.FlushOutboxUseCase
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.CallAnswerDto
import app.pulse.protocol.AiRecapDto
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
import app.pulse.protocol.GroupCallAnswerDto
import app.pulse.protocol.GroupCallEvents
import app.pulse.protocol.GroupCallIceDto
import app.pulse.protocol.GroupCallMemberDto
import app.pulse.protocol.GroupCallOfferDto
import app.pulse.protocol.GroupCallRingPayload
import app.pulse.protocol.GroupCallStatePayload
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
import app.pulse.protocol.folderIconId
import app.pulse.protocol.topicIconId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import app.pulse.domain.sync.withSearchHitExpiry
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

/** Stories live exactly 24h - the same TTL the gateway stamps (route.ts STORY_TTL_MS). */
private const val STORY_TTL_MS: Long = 24L * 60 * 60 * 1000

/**
 * REAL remote-first, Room-cached repository (N3 UI-era surface).
 * Reads flow from Room; refreshes pull the gateway and upsert; writes POST
 * and cache the returned row. The socket pump turns relay signals into
 * Room upserts + [PulseEvent]s so every screen is live without polling.
 */
class PulseRepositoryImpl constructor(
    private val api: PulseApi,
    private val database: PulseDatabase,
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
    private val reminderEffects: app.pulse.domain.notify.ReminderEffects = app.pulse.domain.notify.ReminderEffects.None,
    private val reminderOwner: app.pulse.domain.notify.ReminderOwner? = null,
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

    /** Demo companion rotation counter - cycles the default reply bank. */
    @Volatile private var demoReplyTurn: Int = 0

    @Volatile override var viewerId: String? = null
        private set

    @Volatile private var attachmentSession = java.util.UUID.randomUUID().toString()
    @Volatile private var pumpStarted = false

    private val dispatchGate = app.pulse.domain.sync.MessageDispatchGate()
    private var selfHealJob: Job? = null

    override fun stop() {
        attachmentSession = java.util.UUID.randomUUID().toString()
        viewerId = null
        socket.disconnect()
        scope.coroutineContext.cancelChildren()
        pumpStarted = false
        selfHealJob = null
        onlineIds.value = emptySet()
        connectedFlow.value = false
    }

    override fun start(userId: String) {
        if (viewerId == userId && socket.isJoined) return
        if (viewerId != userId) stop()
        viewerId = userId
        scope.launch {
            // One ordering barrier, shared by UI and cold-process workers.
            sessionTokenStore.load()
            if (viewerId != userId || sessionTokenStore.invalidated.value) return@launch
            startPump()
            launch {
                if (reminderOwner != null) runCatching {
                    val references = database.attachmentDraftDao().references().toSet()
                    withContext(Dispatchers.IO) { attachmentFiles().prune(references) }
                }.onFailure { Log.w(TAG, "Attachment cleanup deferred", it) }
            }
            socket.connect(userId)
            launch { runCatching { ensureDemoSeeded(userId) } }
            launch { fetchSettings() }
            launch { refreshConversations() }
            launch { runCatching { if (outboxDao.count() > 0) flushOutbox() } }
            launch { runCatching { if (callLogDao.queueCount() > 0) flushCallLogQueue() } }
        }
    }

    /**
     * Wave 8 - server prefs fetch (GET /api/settings). The server blob is
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

    // R1-W2F - F-FX-05 per-conversation themes (`chat.convThemes`)

    override val convThemes: Flow<Map<String, ConvTheme>> = prefsLocalStore.convThemes

    override suspend fun setConvTheme(conversationId: String, theme: ConvTheme?) {
        prefsLocalStore.setConvTheme(conversationId, theme)
    }

    override suspend fun updatePulsePrefs(patch: WirePulsePrefs): Result<Unit> {
        // Optimistic FIRST - the toggle lands in the UI instantly.
        prefsLocalStore.applyLocal(patch)
        val id = viewerId ?: return Result.failure(IllegalStateException("No viewer identity"))
        return when (val r = api.updateSettings(id, patch)) {
            is PulseResult.Success -> {
                // the server response is the authoritative clamp - re-anchor
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
                // ROTATION: the raw token shows up exactly once - persist it now.
                r.value.token?.let { t -> runCatching { sessionTokenStore.save(t) } }
                val user = r.value.user
                    ?: return Result.failure(IllegalStateException("Malformed login response"))
                Result.success(user.toDomain())
            }
            is PulseResult.Failure ->
                // 404 copy "No identity with that name on this Pulse." rides verbatim
                Result.failure(OnboardingError.of(r))
        }

    /** Identity forget/switch - the credential must not outlive the identity. */
    override suspend fun clearSessionToken() {
        sessionTokenStore.clear()
    }

    /** Data & Storage - user-initiated drop of one held outbox row (no verdict event). */
    override suspend fun discardOutboxEntry(clientId: String) {
        val owner = viewerId ?: return
        database.withTransaction {
            val temp = messageDao.byId(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId) ?: return@withTransaction
            if (temp.authorId != owner) return@withTransaction
            outboxDao.deleteByClientId(clientId)
            messageDao.deleteById(temp.id)
        }
    }

    override suspend fun retryOutboxEntry(clientId: String): Result<Unit> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("Sign in to the original account"))
        return try {
            if (outboxDao.retryFailed(clientId, owner) == 0) {
                Result.failure(IllegalStateException("This saved message is no longer available for retry"))
            } else {
                scope.launch {
                    try { flushOutbox() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Log.w(TAG, "Retried message remains queued", error); scheduleSelfHeal() }
                }
                Result.success(Unit)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    }

    override suspend fun clearOutbox() {
        val owner = viewerId ?: return
        database.withTransaction {
            outboxDao.allOwned(owner).forEach { row ->
                outboxDao.deleteByClientId(row.clientId)
                messageDao.deleteById(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + row.clientId)
            }
        }
    }

    /** Data & Storage - drop every composer draft at once. */
    override suspend fun clearAllDrafts() {
        draftMutex.withLock {
            // Cancellation after SQLite commit must not skip the in-memory fence:
            // old journal tasks intentionally outlive the composing UI.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val rooms = draftDao.all().map { it.conversationId }.toSet() + draftVersions.keys
                val revisions = rooms.associateWith { room ->
                    draftVersions.getOrPut(room) { java.util.concurrent.atomic.AtomicLong() }.get()
                }
                database.withTransaction {
                    rooms.forEach { draftDao.upsert(DraftEntity(it, "", java.time.OffsetDateTime.now().toString())) }
                }
                revisions.forEach { (room, revision) ->
                    if (draftVersions[room]?.compareAndSet(revision, revision + 1) == true) {
                        scope.launch { mirrorDraft(room, "", revision + 1) }
                    }
                }
            }
        }
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
                            // Flush trigger: the relay came back - drain the outbox first
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
                        // Wave 8 - the relay verified our PRESENTED token and
                        // refused it (rotated/invalid). Clear the credential;
                        // the app layer routes to the honest re-login flow.
                        scope.launch { runCatching { sessionTokenStore.markInvalid(signal.error) } }
                    }
                    is PulseSocketClient.Signal.Joined -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.PresenceSnapshot -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.MessageEnvelope -> onMessageEnvelope(signal)
                    is PulseSocketClient.Signal.Read -> {
                        eventsBus.tryEmit(PulseEvent.MessageRead(signal.conversationId, signal.userId, signal.lastReadAt))
                        scheduleConversationsRefresh()
                    }
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
                    // R8 Task 3-c - group call (mesh) signals → domain events
                    is PulseSocketClient.Signal.GroupCallState -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(signal.payload.toDomainSignal(app.pulse.protocol.GroupCallEvents.STATE)),
                    )
                    is PulseSocketClient.Signal.GroupCallOffer -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(signal.dto.toDomainSignal(app.pulse.protocol.GroupCallEvents.OFFER)),
                    )
                    is PulseSocketClient.Signal.GroupCallAnswer -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(signal.dto.toDomainSignal(app.pulse.protocol.GroupCallEvents.ANSWER)),
                    )
                    is PulseSocketClient.Signal.GroupCallIce -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(signal.dto.toDomainSignal(app.pulse.protocol.GroupCallEvents.ICE)),
                    )
                    is PulseSocketClient.Signal.GroupCallRing -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(signal.payload.toDomainRingSignal()),
                    )
                    is PulseSocketClient.Signal.GroupCallEnded -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(
                            app.pulse.domain.model.GroupCallSignalData(
                                event = app.pulse.protocol.GroupCallEvents.ENDED,
                                conversationId = signal.payload.conversationId,
                                callId = signal.payload.callId,
                                reason = signal.payload.reason,
                            ),
                        ),
                    )
                    is PulseSocketClient.Signal.GroupCallFull -> eventsBus.tryEmit(
                        PulseEvent.GroupCallSignal(
                            app.pulse.domain.model.GroupCallSignalData(
                                event = app.pulse.protocol.GroupCallEvents.FULL,
                                conversationId = signal.payload.conversationId,
                                callId = signal.payload.callId,
                                max = signal.payload.max,
                            ),
                        ),
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

    // R8 Task 3-c - gcall:* socket union → the domain envelope

    private fun GroupCallStatePayload.toDomainSignal(event: String) = app.pulse.domain.model.GroupCallSignalData(
        event = event,
        callId = callId, conversationId = conversationId,
        kind = app.pulse.domain.model.CallKind.of(kind),
        hostId = hostId.takeIf { it.isNotBlank() },
        startedAt = startedAt,
        members = members.map { app.pulse.domain.model.GroupCallMember(it.id, it.name, it.color, it.avatar) },
    )

    private fun GroupCallOfferDto.toDomainSignal(event: String) = app.pulse.domain.model.GroupCallSignalData(
        event = event,
        callId = callId, conversationId = conversationId,
        from = from, to = to, kind = app.pulse.domain.model.CallKind.of(kind), sdp = sdp,
    )

    private fun GroupCallAnswerDto.toDomainSignal(event: String) = app.pulse.domain.model.GroupCallSignalData(
        event = event,
        callId = callId, conversationId = conversationId,
        from = from, to = to, sdp = sdp,
    )

    private fun GroupCallIceDto.toDomainSignal(event: String) = app.pulse.domain.model.GroupCallSignalData(
        event = event,
        callId = callId, conversationId = conversationId,
        from = from, to = to,
        candidate = candidate, sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex,
    )

    private fun GroupCallRingPayload.toDomainRingSignal() = app.pulse.domain.model.GroupCallSignalData(
        event = app.pulse.protocol.GroupCallEvents.RING,
        conversationId = conversationId,
        kind = app.pulse.domain.model.CallKind.of(kind),
        title = title,
        from = caller?.id ?: "",
        callerName = caller?.name ?: "Someone",
        callerColor = caller?.color ?: "emerald",
        callerAvatar = caller?.avatar,
    )

    /**
     * Every message:* envelope carries the authoritative row - upsert it and
     * fan the event out. message:deleted keeps its tombstone semantics.
     */
    private val messageCacheMutex = Mutex()
    private val messageEventClock = java.util.concurrent.atomic.AtomicLong()
    private val messageCacheChanges = MutableStateFlow(0L)
    private val messageEventRevisions = mutableMapOf<String, Long>()

    private suspend fun onMessageEnvelope(signal: PulseSocketClient.Signal.MessageEnvelope) {
        val dto = signal.dto ?: return
        val message = dto.toDomain()
        messageCacheMutex.withLock {
            messageEventRevisions[message.id] = messageEventClock.incrementAndGet()
            // Persist tombstones too; an event-only deletion resurrects after restart.
            messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
            messageCacheChanges.value = messageEventClock.get()
        }
        if (signal.event == app.pulse.protocol.SocketEvents.MESSAGE_DELETED) {
            eventsBus.tryEmit(
                PulseEvent.MessageDeleted(signal.conversationId, message.id),
            )
        } else {
            eventsBus.tryEmit(PulseEvent.MessageReceived(signal.conversationId, message))
        }
        scheduleConversationsRefresh()
    }

    /** A late GET must not undo a socket edit/delete, including in its return value. */
    private suspend fun cacheFetchedMessages(rows: List<Message>, watermark: Long): List<Message> =
        messageCacheMutex.withLock { cacheFetchedMessagesLocked(rows, watermark) }

    /** Caller holds messageCacheMutex; saved snapshot replacement also wraps this in a transaction. */
    private suspend fun cacheFetchedMessagesLocked(rows: List<Message>, watermark: Long): List<Message> {
        val fresh = rows.filter { (messageEventRevisions[it.id] ?: 0L) <= watermark }
        messageDao.upsertAll(fresh.map { MessageEntity.from(it, reactionsJsonOf(it)) })
        return rows.mapNotNull { row ->
            if ((messageEventRevisions[row.id] ?: 0L) > watermark) messageDao.byId(row.id)?.toDomain()
            else row
        }
    }

    private var refreshScheduled = false

    /** Debounced inbox refetch - keeps previews/unreads honest after live hits. */
    private fun scheduleConversationsRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        scope.launch {
            delay(500)
            refreshScheduled = false
            if (isActive) refreshConversations()
        }
    }

    // reads
    override fun observeConversations(query: String): Flow<List<Conversation>> =
        kotlinx.coroutines.flow.combine(conversationDao.observeAll(), draftDao.observeAll()) { rows, drafts ->
            val local = drafts.associate { it.conversationId to it.text }
            rows.map { row ->
                val conversation = row.toDomain()
                local[conversation.id]?.let { conversation.copy(myDraft = it) } ?: conversation
            }.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        }.withConversationExpiry()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeFor(conversationId).map { rows -> rows.map { it.toDomain() } }.withMessageExpiry()

    override fun observeThreadMessages(rootId: String): Flow<List<Message>> =
        messageDao.observeThread(rootId).map { rows -> rows.map { it.toDomain() } }.withMessageExpiry()

    override fun observeDrafts(): Flow<Map<String, String>> = draftDao.observeAll().map { rows ->
        rows.associate { it.conversationId to it.text }
    }

    override fun observePresence(): Flow<Set<String>> = onlineIds.asStateFlow()

    override fun observeConnected(): Flow<Boolean> = connectedFlow.asStateFlow()

    override fun events(): Flow<PulseEvent> = eventsBus.asSharedFlow()

    private val inboxRefreshVersion = java.util.concurrent.atomic.AtomicLong()

    private suspend fun cacheConversation(dto: ConversationSummaryDto): Conversation = database.withTransaction {
        inboxRefreshVersion.incrementAndGet() // pre-mutation list responses cannot erase a created/updated room
        val previous = conversationDao.byId(dto.id)?.toDomain()
        dto.toDomain(viewerId, previous).also { conversationDao.upsertAll(listOf(ConversationEntity.from(it))) }
    }

    override suspend fun refreshConversations(): Result<Unit> {
        val version = inboxRefreshVersion.incrementAndGet()
        val owner = viewerId ?: return Result.failure(IllegalStateException("No active account"))
        return when (val r = api.conversations(owner)) {
            is PulseResult.Success -> {
                database.withTransaction {
                    if (version == inboxRefreshVersion.get() && owner == viewerId) {
                        val rows = r.value.conversations.map { ConversationEntity.from(it.toDomain(owner)) }
                        conversationDao.clearServerSnapshot(app.pulse.domain.model.PulseDemo.CONVERSATION_ID)
                        conversationDao.upsertAll(rows)
                    }
                }
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }
    }

    override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> =
        refreshMessagesWindow(conversationId, limit = limit)

    /** Wave 2 topic-filtered refresh - only rows filed under `topicId`. */
    override suspend fun refreshMessages(conversationId: String, topicId: String?): Result<Unit> =
        refreshMessagesWindow(conversationId, topicId = topicId)

    private val messageRefreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private suspend fun refreshMessagesWindow(
        conversationId: String,
        limit: Int = 200,
        topicId: String? = null,
    ): Result<Unit> = messageRefreshLocks.getOrPut(conversationId) { Mutex() }.withLock {
        // Revisit the cached range, not just its newest timestamp: missed edits
        // and tombstones matter as much as new messages. Never replace pending rows.
        val from = messageDao.earliestServerCreatedAt(conversationId)
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val watermark = messageEventClock.get()
            val result = api.messages(conversationId, limit = limit,
                topicId = if (from == null) topicId else null,
                syncFrom = if (cursor == null) from else null, syncCursor = cursor)
            val page = when (result) {
                is PulseResult.Success -> result.value
                is PulseResult.Failure -> return@withLock Result.failure(apiExceptionOf(result))
            }
            if (from != null && page.syncVersion != 1) {
                return@withLock Result.failure(IllegalStateException("Gateway needs the paged message-sync update"))
            }
            messageCacheMutex.withLock {
                messageDao.upsertAll(page.messages.filter { (messageEventRevisions[it.id] ?: 0L) <= watermark }.map { dto ->
                    val message = dto.toDomain()
                    MessageEntity.from(message, reactionsJsonOf(message))
                })
            }
            if (from == null || !page.hasMore) break
            cursor = page.nextSyncCursor
            if (cursor == null || !seenCursors.add(cursor)) {
                return@withLock Result.failure(IllegalStateException("Message sync returned a missing/repeated cursor"))
            }
        } while (true)
        Result.success(Unit)
    }

    // users / identity
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
                // Wave 8 - the create response carries the raw session token
                // (shown once server-side); persist it before returning.
                r.value.token?.let { t -> runCatching { sessionTokenStore.save(t) } }
                val user = r.value.user
                    ?: return Result.failure(IllegalStateException("Malformed create response"))
                Result.success(user.toDomain())
            }
            is PulseResult.Failure ->
                when {
                    // Definitive live-server verdicts surface untouched -
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

    /** Offline identity - stable random id, kept in prefs like a server row. */
    private fun localIdentity(name: String, color: String?, username: String?): User = User(
        id = "local_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12),
        name = name,
        handle = username ?: "",
        color = color ?: "emerald",
    )

    // ------------------------------------------------------------------
    // Demo companion (Nova) - the on-device first-run room. A fresh offline
    // install can open a REAL chat immediately (Room rows, same pipeline as
    // every other conversation) instead of staring at a dead inbox.

    private fun demoNowIso(): String =
        java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Idempotent seed: one Nova DM per install, unread badge so it gets noticed. */
    private suspend fun ensureDemoSeeded(viewerId: String) {
        val demo = app.pulse.domain.model.PulseDemo
        if (viewerId.isBlank()) return
        if (conversationDao.byId(demo.CONVERSATION_ID) != null) return
        val conversation = Conversation(
            id = demo.CONVERSATION_ID,
            kind = Conversation.Kind.DM,
            title = demo.USER_NAME,
            lastMessageAuthorName = demo.USER_NAME,
            lastMessageKind = "text",
            lastActivityAt = demoNowIso(),
            unreadCount = 1,
            memberIds = listOf(viewerId, demo.USER_ID),
            memberNames = listOf(demo.USER_NAME),
            accentColor = demo.COLOR,
            otherUserId = demo.USER_ID,
        )
        conversationDao.upsertAll(listOf(ConversationEntity.from(conversation)))
        val messages = demo.openingLines("").mapIndexed { index, pair ->
            val isSystem = pair.first == "SYSTEM"
            val body = pair.second
            Message(
                id = "demo_seed_$index",
                conversationId = demo.CONVERSATION_ID,
                authorId = if (isSystem) "system" else demo.USER_ID,
                authorName = if (isSystem) "Pulse" else demo.USER_NAME,
                kind = if (isSystem) Message.Kind.SYSTEM else Message.Kind.TEXT,
                body = body,
                createdAt = demoNowIso(),
                senderColor = if (isSystem) null else demo.COLOR,
            )
        }
        messageDao.upsertAll(messages.map { MessageEntity.from(it) })
    }

    /** On-device send: settle the bubble instantly, then let Nova answer. */
    private suspend fun demoSend(
        conversationId: String,
        body: String,
        replyToId: String?,
        parentId: String?,
        topicId: String?,
        draftToken: app.pulse.domain.model.ComposerDraftToken?,
    ): Result<SendReceipt> {
        val demo = app.pulse.domain.model.PulseDemo
        if (viewerId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No identity - complete onboarding first."))
        }
        if (draftToken != null && (draftToken.ownerId != viewerId || draftToken.conversationId != conversationId)) {
            return Result.failure(IllegalStateException("The composer account changed; nothing was saved"))
        }
        val mine = Message(
            id = "demo_out_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            conversationId = conversationId,
            authorId = viewerId ?: "",
            authorName = viewerId ?: "",
            kind = Message.Kind.TEXT,
            body = body,
            createdAt = demoNowIso(),
            replyToId = replyToId,
            threadRootId = parentId,
            topicId = topicId,
        )
        persistComposerSend(draftToken) { messageDao.upsertAll(listOf(MessageEntity.from(mine))) }
        mutateConversation(conversationId) {
            it.copy(
                lastMessagePreview = body,
                lastMessageKind = "text",
                lastActivityAt = mine.createdAt,
                lastMessageMine = true,
                lastMessageDeleted = false,
                lastMessageIsReply = false,
            )
        }
        scheduleDemoReply(body)
        return Result.success(SendReceipt(message = mine, streak = null))
    }

    /** Nova answers after a human-feeling beat; Room flows carry it live. */
    private fun scheduleDemoReply(userBody: String) {
        val demo = app.pulse.domain.model.PulseDemo
        scope.launch {
            kotlinx.coroutines.delay(800L + (0L..1000L).random())
            runCatching {
                val reply = Message(
                    id = "demo_in_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
                    conversationId = demo.CONVERSATION_ID,
                    authorId = demo.USER_ID,
                    authorName = demo.USER_NAME,
                    kind = Message.Kind.TEXT,
                    body = demo.reply(userBody, demoReplyTurn++),
                    createdAt = demoNowIso(),
                    senderColor = demo.COLOR,
                )
                messageDao.upsertAll(listOf(MessageEntity.from(reply)))
                mutateConversation(demo.CONVERSATION_ID) {
                    it.copy(
                        lastMessagePreview = reply.body,
                        lastMessageAuthorName = demo.USER_NAME,
                        lastMessageKind = "text",
                        lastActivityAt = reply.createdAt,
                        lastMessageMine = false,
                        unreadCount = it.unreadCount + 1,
                    )
                }
            }.onFailure { Log.w(TAG, "demo reply failed", it) }
        }
    }

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
                // 400 invalid / 409 taken are definitive live-server verdicts -
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
                // fully offline - stop paying the timeout on every keystroke
                offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
                Result.success(localVerdict(handle))
            }
        }
    }

    /** Local rules - the last line of defense so onboarding always completes. */
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
                // 404 = the name is free - the caller decides what that means
                if (r.kind == PulseResult.Failure.Kind.NOT_FOUND) Result.success(null)
                else Result.failure(OnboardingError.of(r))
        }

    override suspend fun createDm(otherUserId: String): Result<Conversation> = createConversation(listOf(otherUserId), isGroup = false, name = null)
    override suspend fun createGroup(name: String, memberIds: List<String>): Result<Conversation> = createConversation(memberIds, isGroup = true, name = name)

    private suspend fun createConversation(memberIds: List<String>, isGroup: Boolean, name: String?): Result<Conversation> =
        when (val r = api.createConversation(viewerId ?: "", memberIds, isGroup, name)) {
            is PulseResult.Success -> {
                cacheConversation(r.value)
                Result.success(r.value.toDomain(viewerId))
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun me(): User? = null // no /me route on the wire yet - viewer lives in prefs

    // writes
    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        replyToId: String?,
        parentId: String?,
        topicId: String?,
        draftToken: app.pulse.domain.model.ComposerDraftToken?,
    ): Result<SendReceipt> {
        // Demo companion room: fully on-device. No gateway round-trip, no
        // outbox - the bubble settles instantly and Nova replies locally.
        if (app.pulse.domain.model.PulseDemo.isDemoConversation(conversationId)) {
            return demoSend(conversationId, body, replyToId, parentId, topicId, draftToken)
        }
        // Optimistic echo FIRST (spec §2 row 2): the bubble appears on the very
        // keystroke-to-send beat, not after the round-trip. The same clientId
        // then either reconciles with the real row, rides the outbox, or is
        // retracted on a definitive failure.
        val owner = viewerId ?: return Result.failure(IllegalStateException("Sign in before sending"))
        if (draftToken != null && (draftToken.ownerId != owner || draftToken.conversationId != conversationId)) {
            return Result.failure(IllegalStateException("The composer account changed; nothing was queued"))
        }
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "")
        val nowIso = java.time.OffsetDateTime.now()
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val temp = Message(
            id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId,
            conversationId = conversationId,
            authorId = owner,
            authorName = viewerId ?: "",
            kind = Message.Kind.TEXT,
            body = body,
            createdAt = nowIso,
            replyToId = replyToId,
            threadRootId = parentId,
            topicId = if (parentId == null) topicId else null,
        )
        persistComposerSend(draftToken) {
            messageDao.upsertAll(listOf(MessageEntity.from(temp)))
            outboxDao.insert(OutboxEntity(
                conversationId = conversationId, clientId = clientId, content = body, createdAt = nowIso,
            ))
        }
        return sendPersistedNowOrQueue(temp, clientId) {
            api.sendMessage(
                conversationId = conversationId,
                senderId = owner,
                content = body,
                clientId = clientId,
                replyToId = replyToId,
                // THREAD REPLY rides `parentId` - NEVER conflated with replyToId (spec §1.1).
                parentId = parentId,
                // Wave 2 topic filing (spec §1 row 10): never on thread replies.
                topicId = if (parentId == null) topicId else null,
            )
        }
    }

    /**
     * REM-A - rich TEXT-kind send (F-MS-17/24/23): carries the incognito flag
     * (group-only server-side) and the sticker/effects payload blob. Optimistic
     * echo + offline-outbox semantics mirror [sendMessage] exactly.
     */
    override suspend fun sendRichMessage(
        conversationId: String,
        body: String,
        kind: String,
        payload: String?,
        anon: Boolean,
        anonAliasPreview: String?,
        replyToId: String?,
        parentId: String?,
        topicId: String?,
        draftToken: app.pulse.domain.model.ComposerDraftToken?,
    ): Result<SendReceipt> {
        // Demo room rides the same on-device path (sticker/effect wrappers
        // degrade to their text body here - the bursts still fire client-side).
        if (app.pulse.domain.model.PulseDemo.isDemoConversation(conversationId)) {
            return demoSend(conversationId, body, replyToId, parentId, topicId, draftToken)
        }
        if (kind !in setOf("text", "image", "audio", "file", "sticker", "location")) {
            return Result.failure(IllegalArgumentException("Unsupported message kind"))
        }
        val richPayload = payload?.let {
            runCatching { PulseJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Message payload must be a JSON object"))
        }
        val owner = viewerId ?: return Result.failure(IllegalStateException("Sign in before sending"))
        if (draftToken != null && (draftToken.ownerId != owner || draftToken.conversationId != conversationId)) {
            return Result.failure(IllegalStateException("The composer account changed; nothing was queued"))
        }
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "")
        val nowIso = java.time.OffsetDateTime.now()
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val temp = Message(
            id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId,
            conversationId = conversationId,
            authorId = owner,
            authorName = viewerId ?: "",
            kind = Message.Kind.TEXT,
            body = body,
            createdAt = nowIso,
            replyToId = replyToId,
            threadRootId = parentId,
            topicId = if (parentId == null) topicId else null,
            anon = anon,
            // R3-B item 4 - the optimistic row wears the deterministic alias
            // the server will store (web optimistic-mask parity).
            anonAlias = anonAliasPreview.takeIf { anon },
        )
        persistComposerSend(draftToken) {
            messageDao.upsertAll(listOf(MessageEntity.from(temp.copy(payload = payload)).copy(kind = if (kind == "audio") "VOICE" else kind.uppercase(java.util.Locale.ROOT))))
            outboxDao.insert(OutboxEntity(
                conversationId = conversationId, clientId = clientId, content = body, createdAt = nowIso,
                kind = kind, wirePayload = payload,
            ))
        }
        return sendPersistedNowOrQueue(temp.copy(payload = payload, kind = kindOf(kind)), clientId) {
            api.sendMessage(
                conversationId = conversationId,
                senderId = owner,
                content = body,
                clientId = clientId,
                replyToId = replyToId,
                parentId = parentId,
                kind = kind,
                topicId = if (parentId == null) topicId else null,
                anon = anon.takeIf { it },
                payload = richPayload,
            )
        }
    }

    /** Queue + explicit draft clear commit together; late journal writes share this lock. */
    private suspend fun persistComposerSend(token: app.pulse.domain.model.ComposerDraftToken?, write: suspend () -> Unit) {
        draftMutex.withLock {
            // Cancellation after SQLite commit must not skip the in-memory fence:
            // old journal tasks intentionally outlive the composing UI.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val counter = token?.let { draftVersions[it.conversationId] }
                val consume = token != null && counter?.get() == token.revision
                database.withTransaction {
                    write()
                    if (consume) draftDao.upsert(DraftEntity(checkNotNull(token).conversationId, "", java.time.OffsetDateTime.now().toString()))
                }
                if (consume) {
                    val consumed = checkNotNull(token)
                    if (counter?.compareAndSet(consumed.revision, consumed.revision + 1) == true) {
                        scope.launch { mirrorDraft(consumed.conversationId, "", consumed.revision + 1) }
                    }
                }
            }
        }
    }

    override fun captureDraftToken(conversationId: String): app.pulse.domain.model.ComposerDraftToken? {
        val owner = viewerId ?: return null
        val counter = draftVersions.getOrPut(conversationId) { java.util.concurrent.atomic.AtomicLong() }
        return app.pulse.domain.model.ComposerDraftToken(conversationId, owner, counter.get())
    }

    private suspend fun sendPersistedNowOrQueue(
        temp: Message,
        clientId: String,
        send: suspend () -> PulseResult<app.pulse.protocol.MessageSendEnvelopeDto>,
    ): Result<SendReceipt> {
        val immediate = try {
            dispatchGate.tryImmediate(
                isHead = {
                    viewerId == temp.authorId &&
                        outboxDao.allForOwner(temp.authorId, dispatchGate.blockedConversations().toList()).firstOrNull()?.clientId == clientId
                },
                conversationId = temp.conversationId,
            ) {
                when (val response = send()) {
                    is PulseResult.Success -> {
                        val receipt = response.value.toReceipt()
                        database.withTransaction {
                            messageDao.upsertAll(listOf(MessageEntity.from(receipt.message, reactionsJsonOf(receipt.message))))
                            messageDao.deleteById(temp.id)
                            outboxDao.deleteByClientId(clientId)
                        }
                        scheduleConversationsRefresh()
                        Result.success(receipt)
                    }
                    is PulseResult.Failure -> {
                        holdMessageRetries(response, temp.conversationId)
                        if (response.outboxFailureClass() == OutboxFailureClass.DROP) {
                            dropOutboxEntry(clientId, response.message ?: "Message rejected")
                            Result.failure(apiExceptionOf(response))
                        } else {
                            scheduleSelfHeal()
                            if (response.outboxFailureClass(messageEndpoint = true) == OutboxFailureClass.ROOM_RETRY) {
                                scope.launch { runCatching { flushOutbox() }.onFailure { scheduleSelfHeal() } }
                            }
                            eventsBus.tryEmit(PulseEvent.OutboxQueued(clientId, temp.conversationId))
                            Result.success(SendReceipt(message = temp, streak = null))
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // An unknown response or failed local ACK write is not proof of
            // rejection. The committed operation must retry under its SAME ID.
            Log.w(TAG, "Message acknowledgement is unconfirmed; retained for retry", error)
            null
        } finally {
            if (viewerId == temp.authorId) scheduleSelfHeal()
        }
        if (immediate != null) return immediate
        eventsBus.tryEmit(PulseEvent.OutboxQueued(clientId, temp.conversationId))
        // Bytes/metadata are already durable. Never wait for a large upload to
        // finish before acknowledging that the next text is safely queued.
        scope.launch {
            try { flushOutbox() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) { Log.w(TAG, "Message remains queued", error); scheduleSelfHeal() }
        }
        return Result.success(SendReceipt(message = temp, streak = null))
    }

    private fun holdMessageRetries(failure: PulseResult.Failure, conversationId: String? = null) {
        if (failure.status == 429 || failure.kind == PulseResult.Failure.Kind.RATE_LIMITED) {
            val room = conversationId.takeIf { failure.status == 429 && failure.code == "slow_mode" }
            dispatchGate.holdRetries(failure.retryAfter, room)
        }
    }

    /** Legacy error text (`"KIND: message"`) + the typed retryAfter carrier. */
    private fun apiExceptionOf(f: PulseResult.Failure): PulseApiException = PulseApiException(
        kind = f.kind.name,
        message = f.message?.let { "${f.kind.name}: $it" } ?: f.kind.name,
        status = f.status,
        retryAfter = f.retryAfter,
    )

    // R5-B - send-envelope mapping (server { message, streak?, xpAwarded })

    /** Envelope → domain receipt: the real row + the streak bump when present. */
    private fun app.pulse.protocol.MessageSendEnvelopeDto.toReceipt(): SendReceipt = SendReceipt(
        message = messageOrThrow(),
        streak = streak?.let { StreakSnapshot(count = it.count, best = it.best, continued = it.continued) },
    )

    /**
     * The envelope's message row - null must fail loudly (VALIDATION), never
     * decode as an empty success (iOS R5 lesson: envelope-nil ≠ bare row).
     */
    private fun app.pulse.protocol.MessageSendEnvelopeDto.messageOrThrow(): Message =
        message?.toDomain() ?: throw IllegalStateException("VALIDATION: send response carried no message")

    // Wave 1 messaging surface (spec §1.1 - every route exists today)

    /**
     * Media is saved before upload. Drafts restore without sending; explicit
     * Send transfers their immutable file reference into the existing outbox.
     */
    private fun attachmentFiles(): app.pulse.data.local.AttachmentFiles {
        val owner = checkNotNull(reminderOwner) { "A verified account is required for attachment storage" }
        val namespace = app.pulse.data.local.AccountStorageKey.namespace(owner.origin, owner.userId)
        return app.pulse.data.local.AttachmentFiles(File(context.filesDir, "attachments/$namespace"))
    }

    override fun attachmentSessionKey(): String? = attachmentSession.takeIf { viewerId != null && reminderOwner?.userId == viewerId }

    private fun requireAttachmentSession(key: String): String {
        val owner = viewerId
        if (owner == null || reminderOwner?.userId != owner || key != attachmentSession) throw kotlinx.coroutines.CancellationException("Attachment session ended")
        return owner
    }

    override suspend fun saveAttachmentDraft(draft: app.pulse.domain.model.AttachmentDraft, sessionKey: String): Result<Unit> = try {
        requireAttachmentSession(sessionKey)
        val validated = withContext(Dispatchers.IO) { app.pulse.data.local.AttachmentValidation.validated(draft) }
        val key = withContext(Dispatchers.IO) { attachmentFiles().put(draft.selectionId, validated.dataUrl) }
        database.withTransaction {
            requireAttachmentSession(sessionKey)
            database.attachmentDraftDao().upsert(app.pulse.data.local.AttachmentDraftEntity.from(validated, key))
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requireAttachmentSession(sessionKey)
        }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

    override suspend fun loadAttachmentDraft(conversationId: String, sessionKey: String): Result<app.pulse.domain.model.AttachmentDraft?> = try {
        requireAttachmentSession(sessionKey)
        val saved = database.attachmentDraftDao().get(conversationId)
        val draft = saved?.let { withContext(Dispatchers.IO) { it.toDomain(attachmentFiles().read(it.fileKey)) } }
        requireAttachmentSession(sessionKey)
        Result.success(draft)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

    override suspend fun editAttachmentDraft(conversationId: String, selectionId: String, caption: String, viewOnce: Boolean, revision: Long, sessionKey: String): Result<Unit> = try {
        requireAttachmentSession(sessionKey)
        require(caption.length <= 2000) { "Caption is too long" }
        database.attachmentDraftDao().edit(conversationId, selectionId, caption, viewOnce, revision)
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

    override suspend fun discardAttachmentDraft(conversationId: String, selectionId: String, sessionKey: String): Result<Unit> = try {
        requireAttachmentSession(sessionKey)
        database.attachmentDraftDao().delete(conversationId, selectionId)
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

    override suspend fun queueAttachmentDraft(conversationId: String, selectionId: String, caption: String, viewOnce: Boolean, topicId: String?, sessionKey: String): Result<Message> = try {
        val owner = requireAttachmentSession(sessionKey)
        val snapshot = database.attachmentDraftDao().get(conversationId)
        val prepared = snapshot?.takeIf { it.selectionId == selectionId }?.let { record ->
            withContext(Dispatchers.IO) {
                app.pulse.data.local.AttachmentValidation.validated(
                    record.toDomain(attachmentFiles().read(record.fileKey)).copy(caption = caption, viewOnce = viewOnce))
            }
        }
        val message = database.withTransaction {
            requireAttachmentSession(sessionKey)
            val saved = database.attachmentDraftDao().get(conversationId)
            if (saved?.selectionId != selectionId) {
                val pending = outboxDao.pendingByClientId(selectionId)
                val echo = messageDao.byId(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + selectionId)
                check(pending?.conversationId == conversationId && echo?.authorId == owner) { "Attachment changed or was already queued; check the chat" }
                return@withTransaction checkNotNull(echo).toDomain()
            }
            val record = checkNotNull(saved)
            // Hashing/decoding multi-MB files must not hold Room's writer lock.
            // The immutable reference and metadata must still match that read.
            check(record.fileKey == snapshot?.fileKey && record.metadataJson == snapshot?.metadataJson) { "Attachment changed while preparing Send" }
            val draft = checkNotNull(prepared) { "Attachment is no longer ready" }
            val temp = Message(id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + selectionId,
                conversationId = conversationId, authorId = owner, authorName = owner, kind = kindOf(draft.kind),
                body = caption, createdAt = java.time.Instant.now().toString(), durationMs = draft.durationMs,
                fileName = draft.fileName.takeIf { draft.kind == "file" }, fileSize = draft.fileSize.takeIf { draft.kind == "file" },
                viewOnce = viewOnce, topicId = topicId)
            messageDao.upsertAll(listOf(MessageEntity.from(temp)))
            outboxDao.insert(OutboxEntity(conversationId = conversationId, clientId = selectionId,
                content = caption, kind = draft.kind, createdAt = temp.createdAt, uploadKey = record.fileKey))
            database.attachmentDraftDao().delete(conversationId, selectionId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requireAttachmentSession(sessionKey)
            temp
        }
        scope.launch {
            try { flushOutbox() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Log.w(TAG, "Attachment remains queued", error); scheduleSelfHeal() }
        }
        Result.success(message)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

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
        clientId: String?,
    ): Result<Message> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("Sign in before sending"))
        val operationId = clientId ?: java.util.UUID.randomUUID().toString()
        val wireKind = kind ?: when {
            imagePath != null -> "image"
            audioPath != null -> "audio"
            filePath != null -> "file"
            else -> return Result.failure(IllegalArgumentException("Attachment path is missing"))
        }
        if (wireKind !in setOf("image", "audio", "file") ||
            (wireKind == "image" && imagePath.isNullOrBlank()) ||
            (wireKind == "audio" && (audioPath.isNullOrBlank() || (durationMs != null && durationMs !in 1L..600_000L))) ||
            (wireKind == "file" && (filePath.isNullOrBlank() || fileName.isNullOrBlank() || fileName.length > 120)) ||
            (viewOnce == true && wireKind != "image")) {
            return Result.failure(IllegalArgumentException("Attachment metadata is incomplete or invalid"))
        }
        val temp = Message(
            id = app.pulse.domain.model.TEMP_MESSAGE_PREFIX + operationId,
            conversationId = conversationId, authorId = owner, authorName = owner,
            kind = kindOf(wireKind), body = body, createdAt = java.time.Instant.now().toString(),
            imagePath = imagePath, audioPath = audioPath, durationMs = durationMs,
            filePath = filePath, fileName = fileName, fileSize = fileSize,
            viewOnce = viewOnce == true, topicId = topicId,
        )
        try {
            database.withTransaction {
                messageDao.upsertAll(listOf(MessageEntity.from(temp)))
                outboxDao.insert(OutboxEntity(conversationId = conversationId, clientId = operationId,
                    content = body, kind = wireKind, createdAt = temp.createdAt))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { return Result.failure(error) }
        // One account-owned drainer, not an independent screen POST. Its existing
        // retry envelope reads the exact media metadata from the optimistic row.
        eventsBus.tryEmit(PulseEvent.OutboxQueued(operationId, conversationId))
        scope.launch {
            try { flushOutbox() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Log.w(TAG, "Attachment remains queued", error); scheduleSelfHeal() }
        }
        return Result.success(temp)
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

    /** Batched "N replies ↳" counts for the river - one grouped Room query. */
    override suspend fun threadReplyCounts(rootIds: List<String>): Map<String, Int> {
        if (rootIds.isEmpty()) return emptyMap()
        return runCatching {
            messageDao.countsByThread(rootIds).associate { it.rootId to it.cnt }
        }.getOrDefault(emptyMap())
    }

    override suspend fun editMessage(messageId: String, content: String): Result<Message> =
        when (val r = api.editMessage(messageId, viewerId ?: "", content)) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                upsertMessage(message)
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun toggleMessagePin(messageId: String): Result<Message> =
        when (val r = api.toggleMessagePin(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                upsertMessage(message)
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    private val savedOperations = Mutex()

    override suspend fun toggleMessageSave(messageId: String): Result<Boolean> = savedOperations.withLock {
        when (val r = api.toggleMessageSave(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                if (!r.value.saved) savedDao.deleteById(messageId)
                else messageDao.byId(messageId)?.let { row ->
                    savedDao.upsertAll(listOf(SavedMessageEntity(messageId, row.conversationId, java.time.OffsetDateTime.now().toString())))
                }
                Result.success(r.value.saved)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }
    }

    private val pinRefreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    override suspend fun pinnedMessages(conversationId: String): List<Message> =
        pinRefreshLocks.getOrPut(conversationId) { Mutex() }.withLock {
            val watermark = messageEventClock.get()
            val page = when (val r = api.pinnedMessages(conversationId, viewerId ?: "")) {
                is PulseResult.Success -> r.value
                is PulseResult.Failure -> throw apiExceptionOf(r)
            }
            messageCacheMutex.withLock {
                database.withTransaction {
                    val ids = page.messages.map { it.id }.toSet()
                    // A full empty list must retire stale cached pin flags too.
                    val missing = messageDao.pinned(conversationId).filter {
                        it.id !in ids && (messageEventRevisions[it.id] ?: 0L) <= watermark
                    }
                    messageDao.upsertAll(missing.map { it.copy(pinnedAt = null) })
                    cacheFetchedMessagesLocked(page.messages.map { it.toDomain() }, watermark)
                    messageDao.pinned(conversationId).map { it.toDomain() }.filter { savedMessageVisible(it) }
                }
            }
        }

    override suspend fun unpinMessage(messageId: String): Result<Unit> {
        val watermark = messageEventClock.get()
        return when (val response = api.unpinMessage(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                messageCacheMutex.withLock {
                    // A later relayed re-pin wins over this operation's late ACK.
                    if ((messageEventRevisions[messageId] ?: 0L) <= watermark) {
                        messageEventRevisions[messageId] = messageEventClock.incrementAndGet()
                        messageDao.byId(messageId)?.let { messageDao.upsertAll(listOf(it.copy(pinnedAt = null))) }
                        messageCacheChanges.value = messageEventClock.get()
                    }
                }
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(response))
        }
    }

    override suspend fun loadThread(rootId: String): Pair<Message, List<Message>> {
        val watermark = messageEventClock.get()
        val page = when (val r = api.thread(rootId, viewerId ?: "")) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw apiExceptionOf(r)
        }
        val parent = page.parent?.toDomain() ?: throw IllegalStateException("thread parent missing")
        val replies = page.replies.map { it.toDomain() } // wire order: createdAt asc
        // Upsert both so observeThread(rootId) rehydrates the sheet offline
        // and realtime reply appends merge with this window.
        val merged = cacheFetchedMessages(listOf(parent) + replies, watermark)
        val currentParent = merged.firstOrNull { it.id == parent.id }
            ?: throw IllegalStateException("Thread parent is no longer available")
        if (currentParent.id != rootId || currentParent.threadRootId != null || currentParent.isDeleted ||
            (currentParent.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true)) {
            throw PulseApiException("NOT_FOUND", "This thread is no longer available", status = 410)
        }
        return currentParent to merged.filter { it.threadRootId == rootId && it.conversationId == currentParent.conversationId }
    }

    override suspend fun messagesPage(conversationId: String, before: String?, limit: Int): Pair<List<Message>, Boolean> {
        val anchor = before?.let { messageDao.byId(it)?.takeIf { row -> row.conversationId == conversationId }
            ?: messageDao.firstAtTimestamp(conversationId, it) }
        return fetchMessagePage(conversationId, anchor?.createdAt ?: before, anchor?.id, limit)
    }

    override suspend fun messagesBefore(conversationId: String, beforeId: String, beforeCreatedAt: String, limit: Int): Pair<List<Message>, Boolean> =
        fetchMessagePage(conversationId, beforeCreatedAt, beforeId, limit)

    private suspend fun fetchMessagePage(conversationId: String, before: String?, beforeId: String?, limit: Int): Pair<List<Message>, Boolean> {
        val watermark = messageEventClock.get()
        val page = when (val r = api.messages(conversationId, limit, before, beforeId = beforeId)) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw apiExceptionOf(r)
        }
        val rows = page.messages.map { it.toDomain() }
        return cacheFetchedMessages(rows, watermark) to page.hasMore
    }

    override suspend fun searchInConversation(conversationId: String, query: String): List<Message> {
        val watermark = messageEventClock.get()
        if (query.isBlank()) return emptyList()
        val page = when (val r = api.searchInConversation(conversationId, query)) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> throw IllegalStateException("${r.kind}: ${r.message}")
        }
        val rows = page.messages.map { it.toDomain() }
        return cacheFetchedMessages(rows, watermark)
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
     * endpoint - spec §1.1). Media is forwarded by reusing the stored paths;
     * the wire kind maps back through the whitelist (text|image|audio|sticker|
     * location|file). REM-A F-MS-10: a NETWORK-class failure on a PLAIN TEXT
     * forward rides the existing outbox (kind "text") instead of dying -
     * media stays online-only per spec §1.2.
     */
    override suspend fun forwardMessage(targetConversationId: String, source: Message): Result<Message> {
        val clientId = java.util.UUID.randomUUID().toString()
        val textOnly = source.kind == Message.Kind.TEXT &&
            source.imagePath == null && source.audioPath == null && source.filePath == null
        return when (
            val r = api.sendMessage(
                conversationId = targetConversationId,
                senderId = viewerId ?: "",
                content = source.body,
                clientId = clientId,
                imagePath = source.imagePath,
                audioPath = source.audioPath,
                durationMs = source.durationMs,
                filePath = source.filePath,
                fileName = source.fileName,
                fileSize = source.fileSize,
                kind = wireKindOf(source.kind),
                // R1-W2F (F-MD-07) - location pins keep their {lat,lng,label}
                // payload when forwarded, so the target room renders a REAL pin.
                payload = source.payload?.let { raw ->
                    runCatching { PulseJson.parseToJsonElement(raw) }.getOrNull()
                        as? kotlinx.serialization.json.JsonObject
                },
            )
        ) {
            is PulseResult.Success -> {
                val message = r.value.messageOrThrow()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure -> {
                if (r.kind == PulseResult.Failure.Kind.NETWORK && textOnly && !viewerId.isNullOrBlank()) {
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
                    messageDao.upsertAll(listOf(MessageEntity.from(temp).copy(payloadJson = source.payload)))
                    outboxDao.insert(
                        OutboxEntity(
                            conversationId = targetConversationId,
                            clientId = clientId,
                            content = source.body,
                            kind = "text",
                            createdAt = nowIso,
                        ),
                    )
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
        // R1-W2F (F-MD-07) - pins re-POST with their wire kind + payload.
        Message.Kind.LOCATION -> "location"
        // VIDEO/POLL/RED_PACKET/SYSTEM are outside the send whitelist - omit
        // and let the server default to text (media fields still ride along).
        else -> null
    }

    override suspend fun setServerDraft(conversationId: String, draft: String) {
        val id = viewerId ?: return
        runCatching {
            api.setDraft(conversationId, id, draft.take(MAX_DRAFT))
        }
        // Fire-and-forget: the local draft wins; the server only seeds
        // cross-device restore - failures are silently ignored.
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
                val conversation = cacheConversation(r.value)
                Result.success(conversation)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    // REM-A group governance + scheduling (web group-info-sheet parity)

    /** Live group meta - ALSO upserts the Room conversation cache (rename TTL etc. flow into the list). */
    override suspend fun groupMeta(conversationId: String): Result<GroupMeta> =
        when (val r = api.conversationDetail(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val dto = r.value
                cacheConversation(dto)
                Result.success(
                    GroupMeta(
                        myRole = dto.members.firstOrNull { it.id == viewerId }?.role,
                        isGroup = dto.isGroup,
                        ttlSeconds = dto.ttlSeconds ?: 0,
                        broadcastMode = dto.broadcastMode == true,
                        slowModeSeconds = dto.slowModeSeconds ?: 0,
                        screenPrivacy = dto.screenPrivacy == true,
                        myScreenPrivacy = dto.myScreenPrivacy == true,
                        inviteCode = dto.inviteCode,
                        // R6 - M5: the server computes dmBlocked for DM details
                        // only (src/lib/serializers.ts buildConversationDetail).
                        dmBlocked = dto.dmBlocked == true,
                        // R70 - the web room-info page's live detail fields.
                        description = dto.description,
                        photo = dto.photo,
                        myMutedUntilIso = dto.mutedUntil,
                        myStreakCount = streakCountOf(dto.myStreak),
                        myStreakBest = streakBestOf(dto.myStreak),
                        deadStreakCount = streakCountOf(dto.deadStreak),
                        lostStreakCount = streakCountOf(dto.lostStreak),
                        lostStreakBest = streakBestOf(dto.lostStreak),
                    ),
                )
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    /** PATCH /api/conversations/{id} - every success carries the updated detail; refresh the cache. */
    private suspend fun patchGroupMeta(
        conversationId: String,
        name: String? = null,
        broadcast: Boolean? = null,
        screenPrivacy: Boolean? = null,
    ): Result<Unit> =
        when (val r = api.patchConversation(conversationId, viewerId ?: "", name = name, broadcast = broadcast, screenPrivacy = screenPrivacy)) {
            is PulseResult.Success -> {
                cacheConversation(r.value)
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

    // R2-A - round-2 parity (automations · webhooks · recap · privacy · photo)

    override suspend fun setMyScreenPrivacy(conversationId: String, on: Boolean): Result<Unit> =
        when (val r = api.setMyScreenPrivacy(conversationId, viewerId ?: "", on)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setGroupPhoto(conversationId: String, photoPath: String): Result<Unit> =
        when (val r = api.patchConversation(conversationId, viewerId ?: "", photo = normalizeUploadPath(photoPath))) {
            is PulseResult.Success -> {
                cacheConversation(r.value)
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun automations(conversationId: String): Result<List<Automation>> =
        when (val r = api.automations(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.automations.map { it.toDomain() })
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun createAutomation(conversationId: String, trigger: String, reply: String): Result<Automation> =
        when (val r = api.createAutomation(conversationId, viewerId ?: "", trigger, reply)) {
            is PulseResult.Success -> Result.success(r.value.automation.toDomain())
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setAutomationEnabled(automationId: String, enabled: Boolean): Result<Automation> =
        when (val r = api.patchAutomation(automationId, viewerId ?: "", enabled = enabled, trigger = null)) {
            is PulseResult.Success -> Result.success(r.value.automation.toDomain())
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setAutomationTrigger(automationId: String, trigger: String): Result<Automation> =
        when (val r = api.patchAutomation(automationId, viewerId ?: "", enabled = null, trigger = trigger)) {
            is PulseResult.Success -> Result.success(r.value.automation.toDomain())
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun deleteAutomation(automationId: String): Result<Unit> =
        when (val r = api.deleteAutomation(automationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun webhooks(conversationId: String): Result<List<Webhook>> =
        when (val r = api.webhooks(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.webhooks.map { it.toDomain() })
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun createWebhook(conversationId: String, name: String): Result<Webhook> =
        when (val r = api.createWebhook(conversationId, name, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(r.value.toDomain())
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun deleteWebhook(token: String): Result<Unit> =
        when (val r = api.deleteWebhook(token, viewerId ?: "")) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun aiRecap(conversationId: String): Result<AiRecapDto> =
        when (val r = api.aiRecap(viewerId ?: "", conversationId)) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    /** Wire automation row → domain (AutomationDto/Room2Dtos.kt). */
    private fun app.pulse.protocol.AutomationDto.toDomain(): Automation = Automation(
        id = id,
        conversationId = conversationId,
        trigger = trigger,
        reply = reply,
        enabled = enabled,
        hits = hits,
        lastFiredAtIso = lastFiredAt,
        createdAtIso = createdAt,
        createdByName = createdBy?.name,
    )

    /** Wire webhook row → domain (WebhookDto/Room2Dtos.kt). */
    private fun app.pulse.protocol.WebhookDto.toDomain(): Webhook = Webhook(
        id = id,
        name = name,
        token = token,
        avatarColor = avatarColor,
        url = url,
        createdAtIso = createdAt,
        createdBy = createdBy,
    )

    override suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Result<List<String>> =
        when (val r = api.addMembers(conversationId, viewerId ?: "", userIds)) {
            is PulseResult.Success -> Result.success(r.value.added)
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun setMemberRole(conversationId: String, userId: String, promote: Boolean): Result<Unit> =
        when (val r = api.promoteDemote(conversationId, viewerId ?: "", userId, promote)) {
            is PulseResult.Success -> {
                cacheConversation(r.value)
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
                cacheConversation(r.value)
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

    // offline outbox engine (Wave 0 - web pulse-outbox parity)

    override suspend fun enqueueOutbox(entry: OutboxEntry): Result<Unit> = try {
        outboxDao.insert(OutboxEntity.from(entry))
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun observeOutbox(): Flow<List<OutboxEntry>> {
        val owner = viewerId ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return outboxDao.observeOwned(owner).map { rows -> rows.map { it.toEntry() } }
    }

    override suspend fun outboxPending(): List<OutboxEntry> {
        val owner = viewerId ?: return emptyList()
        return outboxDao.allForOwner(owner).map { it.toEntry() }
    }

    override suspend fun outboxReady(excludingRooms: Set<String>): List<OutboxEntry> {
        val owner = viewerId ?: return emptyList()
        return outboxDao.allForOwner(owner, (excludingRooms + dispatchGate.blockedConversations()).toList()).map { it.toEntry() }
    }

    override suspend fun pendingOutboxCount(): Int = viewerId?.let { outboxDao.pendingCountForOwner(it) } ?: 0

    /** ONE POST attempt - classification rides on OutboxDeliveryException. */
    override suspend fun attemptOutboxSend(entry: OutboxEntry): Result<Message> {
        val current = outboxDao.pendingByClientId(entry.clientId)
            ?: return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Operation is no longer pending"))
        val owner = viewerId
        val stored = messageDao.byId(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + entry.clientId)
        if (owner.isNullOrBlank() || sessionTokenStore.cached.isNullOrBlank() ||
            stored == null || stored.authorId != owner || stored.conversationId != entry.conversationId
        ) return Result.failure(OutboxDeliveryException(OutboxFailureClass.RETRY, "Sign in to the message's original account to send it"))
        var temp = checkNotNull(stored)
        if (current.uploadKey != null) {
            if (current.kind !in setOf("image", "audio", "file")) return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Saved upload kind is invalid"))
            val bytes = try { withContext(Dispatchers.IO) { attachmentFiles().read(current.uploadKey) } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, error.message ?: "Saved attachment is unavailable")) }
            val path = when (val upload = api.uploadMedia(bytes)) {
                is PulseResult.Success -> upload.value.filePath?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Upload receipt was not confirmed; original attachment is kept"))
                is PulseResult.Failure -> {
                    holdMessageRetries(upload)
                    val classification = upload.outboxFailureClass()
                    return Result.failure(OutboxDeliveryException(classification, upload.message ?: "Upload was not confirmed"))
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (viewerId != owner) throw kotlinx.coroutines.CancellationException("Attachment account ended")
            val uploaded = database.withTransaction {
                if (outboxDao.pendingByClientId(entry.clientId) == null) return@withTransaction null
                val original = messageDao.byId(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + entry.clientId)
                    ?: return@withTransaction null
                if (original.authorId != owner || original.conversationId != entry.conversationId) return@withTransaction null
                val updated = original.copy(imagePath = path.takeIf { current.kind == "image" },
                    audioPath = path.takeIf { current.kind == "audio" }, filePath = path.takeIf { current.kind == "file" })
                messageDao.upsertAll(listOf(updated))
                outboxDao.finishUpload(entry.clientId)
                updated
            } ?: return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Attachment was discarded during upload"))
            temp = uploaded
            // The durable path has replaced the upload bytes. A cleanup failure
            // must not turn that successful receipt into a failed send.
            withContext(Dispatchers.IO) { runCatching { attachmentFiles().remove(current.uploadKey) } }
        }
        val queued = temp.toDomain()
        val payload = try {
            (entry.wirePayload ?: queued.payload)?.let {
                PulseJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject
                    ?: return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Saved message payload is invalid"))
            }
        } catch (_: Exception) {
            return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Saved message payload is damaged"))
        }
        // Receipt cleanup suspends; a user may discard the row during it.
        // Do not start a POST for an intent observed to have been removed.
        if (outboxDao.pendingByClientId(entry.clientId) == null) {
            return Result.failure(OutboxDeliveryException(OutboxFailureClass.DROP, "Message was discarded before sending"))
        }
        return when (val r = api.sendMessage(
            entry.conversationId, owner, entry.content, clientId = entry.clientId,
            replyToId = temp.replyToId, parentId = temp.threadRootId,
            topicId = temp.topicId, kind = entry.kind,
            anon = queued.anon.takeIf { it }, payload = payload,
            imagePath = queued.imagePath, audioPath = queued.audioPath, durationMs = queued.durationMs,
            filePath = queued.filePath, fileName = queued.fileName, fileSize = queued.fileSize,
            viewOnce = queued.viewOnce.takeIf { it },
        )) {
            is PulseResult.Success -> Result.success(r.value.messageOrThrow())
            is PulseResult.Failure -> {
                holdMessageRetries(r, entry.conversationId)
                val classification = r.outboxFailureClass(messageEndpoint = true)
                Result.failure(OutboxDeliveryException(classification, r.message ?: r.kind.name))
            }
        }
    }

    override suspend fun resolveOutboxDelivery(entry: OutboxEntry, real: Message) {
        database.withTransaction {
            messageDao.upsertAll(listOf(MessageEntity.from(real, reactionsJsonOf(real))))
            messageDao.deleteById(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + entry.clientId)
            outboxDao.deleteByClientId(entry.clientId)
        }
        scheduleConversationsRefresh()
        eventsBus.tryEmit(PulseEvent.OutboxFlushed(entry.clientId, real))
    }

    override suspend fun dropOutboxEntry(clientId: String, reason: String) {
        // Retain the echo as the full retry/recovery envelope, hidden by the DAO
        // timeline query. A later canonical delivery can still resolve this row.
        val message = messageDao.byId(app.pulse.domain.model.TEMP_MESSAGE_PREFIX + clientId)
        if (outboxDao.markFailed(clientId, reason) > 0) {
            eventsBus.tryEmit(PulseEvent.OutboxDropped(clientId, "Saved in Drafts & outbox: $reason", message?.conversationId, message?.threadRootId))
        }
    }

    override suspend fun bumpOutboxAttempts(clientId: String) {
        outboxDao.incrementAttempts(clientId)
    }

    /**
     * Every trigger funnels here (start / reconnect / foreground / worker /
     * self-heal). The drain policy itself is FlushOutboxUseCase - single
     * source of truth, unit-tested at the domain layer.
     */
    override suspend fun flushOutbox(): FlushReport = dispatchGate.serialized {
        if (viewerId == null || sessionTokenStore.cached.isNullOrBlank() || sessionTokenStore.invalidated.value) {
            return@serialized FlushReport(sent = 0, dropped = 0, pending = pendingOutboxCount())
        }
        if (dispatchGate.remainingDelayMillis() > 0L) {
            scheduleSelfHeal()
            return@serialized FlushReport(pending = pendingOutboxCount())
        }
        val report = FlushOutboxUseCase(this).invoke()
        if (report.pending > 0) scheduleSelfHeal()
        report
    }

    /** 20s self-heal - a pending queue never waits longer than one beat. */
    private fun scheduleSelfHeal() {
        if (selfHealJob?.isActive == true) return
        selfHealJob = scope.launch {
            delay(maxOf(SELF_HEAL_MS, dispatchGate.remainingDelayMillis()))
            // Release the scheduling slot before flush requests its next retry.
            selfHealJob = null
            runCatching { flushOutbox() }
                .onFailure { Log.w(TAG, "self-heal flush failed", it) }
        }
    }

    // drafts (Wave 0 - web pulse-drafts parity)

    private val draftWrites = DraftWriteCoordinator.forDatabase(database)
    private val draftVersions = draftWrites.versions
    private val draftMutex = draftWrites.localMutex
    private val draftServerMutex = draftWrites.serverMutex
    // Finite local journal writes outlive UI/transport teardown; no network work
    // runs in this scope. Every write remains bound to this account's database.
    private val draftWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun nextDraftVersion(id: String): Long = draftVersions
        .getOrPut(id) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()

    private suspend fun writeLocalDraft(id: String, text: String, version: Long) = draftMutex.withLock {
        if (draftVersions[id]?.get() != version) return@withLock false
        val value = text.take(MAX_DRAFT)
        // Empty is an explicit local clear, not permission to restore stale server text.
        draftDao.upsert(DraftEntity(id, if (value.isBlank()) "" else value, java.time.OffsetDateTime.now().toString()))
        true
    }

    override fun stageDraft(conversationId: String, text: String) {
        val version = nextDraftVersion(conversationId)
        draftWriteScope.launch {
            try { writeLocalDraft(conversationId, text, version) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { Log.w(TAG, "Local draft could not be saved", error) }
        }
    }

    override suspend fun saveDraft(conversationId: String, text: String) {
        val revision = nextDraftVersion(conversationId)
        if (writeLocalDraft(conversationId, text, revision)) mirrorDraft(conversationId, text, revision)
    }

    override suspend fun saveStagedDraft(conversationId: String, text: String, token: app.pulse.domain.model.ComposerDraftToken) {
        if (token.ownerId != viewerId || token.conversationId != conversationId) return
        if (writeLocalDraft(conversationId, text, token.revision)) {
            mirrorDraft(conversationId, text, token.revision)
        }
    }

    private suspend fun mirrorDraft(conversationId: String, text: String, revision: Long) = draftServerMutex.withLock {
        // Serialize attempted mirrors; skip revisions replaced before the request.
        // This is best effort, not server-side cross-device conflict resolution.
        if (!app.pulse.domain.model.PulseDemo.isDemoConversation(conversationId) && draftVersions[conversationId]?.get() == revision) {
            setServerDraft(conversationId, text.take(MAX_DRAFT))
        }
    }

    override suspend fun clearDraft(conversationId: String) = saveDraft(conversationId, "")

    override fun observeDraft(conversationId: String): Flow<String?> =
        draftDao.observe(conversationId).map { it?.text }

    override suspend fun markRead(conversationId: String): Result<Unit> = acknowledgeRead(conversationId, null)

    override suspend fun markReadThrough(conversationId: String, messageId: String): Result<Unit> =
        acknowledgeRead(conversationId, messageId)

    private suspend fun acknowledgeRead(conversationId: String, messageId: String?): Result<Unit> {
        if (app.pulse.domain.model.PulseDemo.isDemoConversation(conversationId)) {
            mutateConversation(conversationId) { it.copy(unreadCount = 0, myManualUnread = false) }
            return Result.success(Unit)
        }
        val owner = viewerId ?: return Result.failure(IllegalStateException("The account is no longer active"))
        val result = api.postAction("/api/conversations/$conversationId/read",
            PulseApi.jsonOf("userId" to owner, "messageId" to messageId)).toResult()
        // Do not zero newer arrivals or roll an entire stale conversation back
        // over messages/pins received while the read request was in flight.
        if (result.isSuccess && viewerId == owner) scheduleConversationsRefresh()
        return result
    }

    override suspend fun setTyping(conversationId: String, userName: String, typing: Boolean) {
        // R2-A - the relay fans typing out to `recipients` user rooms and DROPS
        // empty recipient lists (pulse-socket/index.ts: recipients.length===0 →
        // no relay). The web room computes members-minus-me (chat-room.tsx:1238)
        // for this same payload; the cached conversation row supplies the ids.
        val recipients = conversationDao.byId(conversationId)?.toDomain()?.memberIds
            .orEmpty()
            .filter { it != viewerId }
        socket.emitTyping(recipients = recipients, conversationId, viewerId ?: "", userName = userName, isTyping = typing)
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
     * Optimistic local mutation of one cached conversation row - the flag flips
     * instantly and the server call + refresh reconcile the truth afterwards.
     */
    private suspend fun mutateConversation(id: String, transform: (Conversation) -> Conversation) {
        database.withTransaction {
            val row = conversationDao.byId(id) ?: return@withTransaction
            inboxRefreshVersion.incrementAndGet()
            conversationDao.upsertAll(listOf(ConversationEntity.from(transform(row.toDomain()))))
        }
    }

    override suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit> {
        mutateConversation(conversationId) { it.copy(isPinned = pinned) }
        val result = api.patchAction(
            "/api/conversations/$conversationId/pin",
            PulseApi.jsonOf("userId" to (viewerId ?: "")),
        ).toResult()
        refreshConversations() // server toggles - reconcile from truth either way
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
            // Explicit JsonPrimitive/JsonNull - JsonObjectBuilder.put has no
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
    // unblock is DELETE /block?userId= - POST /unblock does NOT exist on the
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

    // N10 home-page era

    override suspend fun createSelfChat(): Result<Conversation> =
        when (val r = api.createSelfChat(viewerId ?: "")) {
            is PulseResult.Success -> {
                val conversation = cacheConversation(r.value)
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
                // so a cached replay walks the exact same mapper - including
                // the D2 expiry filter - as a live fetch.
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

    /** Wire DTO → domain story (NO expiry filtering here - filter lives in [toDomainGroups]/the machine). */
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
     * with expiresAt <= now are dropped HERE, offline, before any grouping -
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
                // R18 icon-id contract - normalize on read (stale emoji to the
                // default) so the domain layer only ever carries registry ids.
                FolderSummary(id = f.id, name = f.name, emoji = folderIconId(f.emoji), conversationIds = f.conversationIds)
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
                        senderName = if (hit.anon) hit.anonAlias ?: "Anonymous" else hit.sender?.name ?: "",
                        senderColor = if (hit.anon) null else hit.sender?.color,
                        content = hit.content,
                        createdAt = hit.createdAt,
                        deleted = hit.deletedAt != null,
                        imageOnly = hit.imagePath != null && hit.content.isBlank(),
                        isFile = hit.filePath != null,
                        fileName = hit.fileName,
                        expiresAtEpochMs = hit.expiresAt?.let { PulseTime.epochMs(it) },
                        viewOnce = hit.viewOnce,
                    )
                },
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }
    }

    override fun observeMessageSearch(query: String): Flow<Result<List<MessageHit>>> = kotlinx.coroutines.flow.flow {
        val watermark = messageEventClock.get()
        val result = searchMessages(query)
        val hits = result.getOrElse { emit(Result.failure(it)); return@flow }
        emitAll(messageCacheChanges.map {
            messageCacheMutex.withLock {
                // A selected result is a snapshot, not authorization to retain
                // a body after a known edit/delete. Re-query to discover new matches.
                hits.filter { (messageEventRevisions[it.id] ?: 0L) <= watermark }
            }
        }.withSearchHitExpiry().map { Result.success(it) })
    }

    override suspend fun fullHistory(conversationId: String): Result<List<Message>> =
        when (val r = api.messages(conversationId, 500)) {
            is PulseResult.Success -> Result.success(r.value.messages.map { it.toDomain() })
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        when (val r = api.deleteMessage(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                // R2-C item 2 - the tombstone echoes back with its ORIGINAL
                // createdAt, so a since= delta refetch can never re-fetch it:
                // upsert the deleted row here (server truth) so "Message
                // deleted" renders immediately (the VM's refetch then rides
                // the delta path for anything genuinely newer).
                upsertMessage(r.value.toDomain())
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override suspend fun exportChat(conversationId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val history = fullHistory(conversationId).getOrThrow()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val fileName = "pulse-${conversationId.take(12)}-${java.time.LocalDate.now()}.txt"
            File(dir, fileName).writeText(
                buildString {
                    appendLine("Pulse - exported chat")
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
            when (val response = api.deleteMessage(message.id, viewerId ?: "")) {
                is PulseResult.Success -> { upsertMessage(response.value.toDomain()); cleared += 1 }
                is PulseResult.Failure -> Unit // Failed rows remain intact and retryable.
            }
        }
        if (mine.isNotEmpty() && cleared == 0) return Result.failure(IllegalStateException("No messages could be deleted. Try again."))
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
    private suspend fun upsertMessage(m: Message) = messageCacheMutex.withLock {
        messageEventRevisions[m.id] = messageEventClock.incrementAndGet()
        messageDao.upsertAll(listOf(MessageEntity.from(m, reactionsJsonOf(m))))
        messageCacheChanges.value = messageEventClock.get()
    }

    // Wave 2 messaging depth (spec §0/§1 - routes verified live)

    override suspend fun transcribeMessage(messageId: String): Result<TranscribeOutcome> =
        when (val r = api.transcribe(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val outcome = TranscribeOutcome(
                    transcript = r.value.transcript,
                    transcribedAt = PulseTime.epochMs(r.value.transcribedAt).takeIf { it > 0L },
                    cached = r.value.cached,
                )
                // Targeted patch - never rewrites the row's other columns;
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

    // R1-W2F - F-MD-06 translation

    /**
     * POST /api/messages/{id}/translate - server-side LLM, persisted per
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
        // silently ignored - the link:preview relay covers the rest.
        when (val r = api.unfurl(messageId, viewerId ?: "")) {
            is PulseResult.Success -> r.value?.let { upsertMessage(it.toDomain()) }
            is PulseResult.Failure -> Log.w(TAG, "unfurl failed: ${r.kind}: ${r.message}")
        }
    }

    override suspend fun refreshSavedLibrary(): Result<List<SavedItem>> = savedOperations.withLock {
        val watermark = messageEventClock.get()
        when (val r = api.savedList(viewerId ?: "")) {
            is PulseResult.Success -> messageCacheMutex.withLock {
                database.withTransaction {
                    // One commit: message versions + exact index, including an empty list.
                    // An older GET cannot overwrite a socket/local edit or deletion.
                    cacheFetchedMessagesLocked(r.value.items.map { it.message.toDomain() }, watermark)
                    savedDao.clear()
                    savedDao.upsertAll(r.value.items.map { it.toSavedEntity() })
                    Result.success(savedDao.library().map { it.toDomain() }.filter { savedMessageVisible(it.message) })
                }
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }
    }

    private fun savedMessageVisible(message: Message): Boolean = !message.isDeleted &&
        (message.expiresAtEpochMs?.let { it > System.currentTimeMillis() } != false)

    override fun observeSavedLibrary(): Flow<List<SavedItem>> = savedDao.observeLibrary()
        .map { rows -> rows.map { it.toDomain() } }.withSavedMessageExpiry()

    override suspend fun unsaveMessage(messageId: String): Result<Boolean> = savedOperations.withLock {
        when (val r = api.unsaveMessage(messageId, viewerId ?: "")) {
            is PulseResult.Success -> {
                if (r.value.saved) Result.failure(IllegalStateException("Message was not removed from saved"))
                else {
                    savedDao.deleteById(messageId)
                    Result.success(false)
                }
            }
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }
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

    // Wave 3: native 1:1 calls - history cache + single-writer queue

    override fun observeCallLog(): Flow<List<CallLogEntry>> =
        callLogDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshCallLog(): Result<List<CallLogEntry>> =
        when (val r = api.callLogs(viewerId ?: "")) {
            is PulseResult.Success -> {
                val items = r.value.items.map { it.toDomain() }
                callLogDao.upsertAll(items.map { CallLogCacheEntity.from(it) })
                // Server is truth - prune rows it no longer lists (cap 50).
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
        // Instant local visibility - the refresh reconciles the server id later.
        callLogDao.upsert(CallLogCacheEntity.from(entry))
        return postCallLog(payload)
    }

    override suspend fun quickPhrases(): List<Pair<String, String>> {
        val id = viewerId ?: return emptyList()
        return when (val r = api.quickPhrases(id)) {
            is PulseResult.Success -> r.value
            is PulseResult.Failure -> emptyList()
        }
    }

    override suspend fun flushCallLogQueue(): Result<Int> {
        val rows = callLogDao.queued(MAX_CALL_LOG_QUEUE)
        if (rows.isEmpty()) return Result.success(0)
        var flushed = 0
        for (row in rows) {
            // `continue` inside an inline lambda is experimental Kotlin -
            // the null-check form is semantically identical and stable.
            val payload = runCatching {
                PulseJson.parseToJsonElement(row.payloadJson).jsonObject
            }.getOrNull()
            if (payload == null) {
                Log.w(TAG, "call-log queue row ${row.id} unparseable - dropped")
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
                        // Definitive 4xx verdict - the row is dead weight.
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

    override suspend fun emitGroupCall(signal: app.pulse.domain.model.GroupCallSignalOut) {
        when (signal.event) {
            app.pulse.protocol.GroupCallEvents.JOIN -> socket.emitGroupCallJoin(
                signal.conversationId,
                signal.kind.wire,
                GroupCallMemberDto(
                    id = signal.user?.id ?: "",
                    name = signal.user?.name ?: "",
                    color = signal.user?.color ?: "emerald",
                    avatar = signal.user?.avatar,
                ),
            )
            app.pulse.protocol.GroupCallEvents.OFFER -> socket.emitGroupCallOffer(
                GroupCallOfferDto(
                    conversationId = signal.conversationId, callId = signal.callId,
                    from = signal.from, to = signal.to, kind = signal.kind.wire, sdp = signal.sdp ?: "",
                ),
            )
            app.pulse.protocol.GroupCallEvents.ANSWER -> socket.emitGroupCallAnswer(
                GroupCallAnswerDto(
                    conversationId = signal.conversationId, callId = signal.callId,
                    from = signal.from, to = signal.to, sdp = signal.sdp ?: "",
                ),
            )
            app.pulse.protocol.GroupCallEvents.ICE -> socket.emitGroupCallIce(
                GroupCallIceDto(
                    conversationId = signal.conversationId, callId = signal.callId,
                    from = signal.from, to = signal.to,
                    candidate = signal.candidate ?: "", sdpMid = signal.sdpMid, sdpMLineIndex = signal.sdpMLineIndex,
                ),
            )
            app.pulse.protocol.GroupCallEvents.LEAVE -> socket.emitGroupCallLeave(signal.conversationId, signal.from)
        }
    }

    override suspend fun postGroupCallRing(conversationId: String, kind: CallKind): Result<Unit> {
        val id = viewerId ?: return Result.failure(IllegalStateException("No viewer identity"))
        return when (val r = api.postGroupCallRing(conversationId, id, kind.wire)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException(r.message ?: "Could not ring other members"))
        }
    }

    override suspend fun probeGroupCallState(conversationId: String): Result<GroupCallProbe?> {
        return when (val r = api.groupCallState(conversationId)) {
            is PulseResult.Success -> Result.success(
                GroupCallProbe(
                    callId = r.value.callId.takeIf { it.isNotBlank() },
                    kind = CallKind.of(r.value.kind),
                    startedAt = r.value.startedAt,
                    members = r.value.members.map { GroupCallMember(it.id, it.name, it.color, it.avatar) },
                ),
            )
            is PulseResult.Failure -> Result.failure(IllegalStateException(r.message ?: "probe failed"))
        }
    }

    override suspend fun registerPushToken(userId: String, token: String): Result<Unit> {
        return when (val r = api.registerPushToken(userId, token)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException(r.message ?: "push register failed"))
        }
    }

    override suspend fun unregisterPushToken(token: String): Result<Unit> {
        return when (val r = api.unregisterPushToken(token)) {
            is PulseResult.Success -> Result.success(Unit)
            is PulseResult.Failure -> Result.failure(IllegalStateException(r.message ?: "push unregister failed"))
        }
    }

    // Wave 5 voice rooms / stage / space - best-effort socket emits
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

    // Wave 7 - collaboration & hub

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

    override suspend fun loadWhiteboardDraft(conversationId: String, ownerId: String): List<WhiteboardStrokePostDto> =
        prefsLocalStore.whiteboardDraft("account:$ownerId:room:$conversationId")

    override suspend fun updateWhiteboardDraft(conversationId: String, ownerId: String, transform: (List<WhiteboardStrokePostDto>) -> List<WhiteboardStrokePostDto>) =
        prefsLocalStore.updateWhiteboardDraft("account:$ownerId:room:$conversationId", transform)

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
                reminderOwner?.let { owner ->
                    for (item in r.value.items) reminderEffects.schedule(owner, item)
                }
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun createReminder(conversationId: String, messageId: String?, note: String?, remindAtIso: String): Result<ReminderItemDto> =
        when (val r = api.createReminder(viewerId ?: "", conversationId, messageId, note, remindAtIso)) {
            is PulseResult.Success -> {
                reminderOwner?.let { reminderEffects.schedule(it, r.value) }
                Result.success(r.value)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun resolveReminder(reminderId: String): Result<ReminderResolveDto> =
        when (val r = api.resolveReminder(reminderId, viewerId ?: "")) {
            is PulseResult.Success -> {
                if (!r.value.ok) Result.failure(IllegalStateException("Invalid reminder acknowledgement"))
                else {
                    reminderOwner?.let { reminderEffects.finish(it, reminderId) }
                    Result.success(r.value)
                }
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun deleteReminder(reminderId: String): Result<Unit> =
        when (val r = api.deleteReminder(reminderId, viewerId ?: "")) {
            is PulseResult.Success -> {
                reminderOwner?.let { reminderEffects.finish(it, reminderId) }
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun acknowledgeReminderDelivery(reminderId: String): Result<ReminderResolveDto> =
        when (val r = api.resolveReminder(reminderId, viewerId ?: "")) {
            is PulseResult.Success -> if (r.value.ok) Result.success(r.value)
                else Result.failure(IllegalStateException("Invalid reminder acknowledgement"))
            is PulseResult.Failure -> Result.failure(apiExceptionOf(r))
        }

    override fun reminderDeliveryOwner(): app.pulse.domain.notify.ReminderOwner? = reminderOwner

    // R1-W2A - quick phrases (F-MS-29)

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

    override suspend fun createGame(conversationId: String, opponentId: String?): Result<GameMatchCreateResultDto> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("No room owner"))
        val opponent = opponentId?.trim()?.takeIf { it.isNotEmpty() }
        val intent = kotlinx.serialization.json.JsonArray(listOf(owner, "game", conversationId, opponent.orEmpty()).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        return roomCreation(intent, send = { id -> api.createGame(owner, conversationId, opponent, id) }, carrier = { it.message })
    }

    /** Persist the retry identity before POST; cache its confirmed carrier before
     * retiring it. Cancellation/storage failure leaves the same operation retryable. */
    private suspend fun <T> roomCreation(intent: String, send: suspend (String) -> PulseResult<T>, carrier: (T) -> app.pulse.protocol.ChatMessageDto?): Result<T> {
        return try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val id = prefsLocalStore.beginRoomCreation(intent)
            when (val result = send(id)) {
                is PulseResult.Success -> {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val message = requireNotNull(carrier(result.value)) { "Creation message is missing" }
                    upsertMessage(message.toDomain())
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    prefsLocalStore.finishRoomCreation(intent, id)
                    scheduleConversationsRefresh()
                    Result.success(result.value)
                }
                is PulseResult.Failure -> {
                    if (result.status == 410 && result.code == "creation_gone") {
                        if (result.receiptClientId != id) return Result.failure(IllegalStateException("Unconfirmed removal receipt; the original creation is still pending."))
                        // Proven delivered but removed: allow a later deliberate new creation.
                        // Unknown 410s or mismatched receipts must keep the retry identity.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        prefsLocalStore.finishRoomCreation(intent, id)
                    }
                    Result.failure(IllegalStateException("${result.kind}: ${result.message}"))
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
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

    override suspend fun createTournament(conversationId: String, name: String): Result<TournamentCreateResultDto> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("No room owner"))
        val normalizedName = name.trim()
        val intent = kotlinx.serialization.json.JsonArray(listOf(owner, "tournament", conversationId, normalizedName).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        return roomCreation(intent, send = { id -> api.createTournament(owner, conversationId, normalizedName, id) }, carrier = { it.message })
    }

    override suspend fun tournaments(conversationId: String): Result<TournamentsPageDto> =
        when (val r = api.tournaments(conversationId, viewerId)) {
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

    override suspend fun transferCoins(toUsername: String, amount: Long, note: String?): Result<TransferResultDto> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("No wallet owner"))
        val handle = toUsername.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
        val normalizedNote = note.orEmpty().trim().take(140)
        val intent = kotlinx.serialization.json.JsonArray(listOf(owner, "transfer", handle, amount.toString(), normalizedNote).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        return walletMutation(intent) { id -> api.transferCoins(owner, handle, amount, normalizedNote, id) }
    }

    private suspend fun <T> walletMutation(intent: String, send: suspend (String) -> PulseResult<T>): Result<T> {
        return try {
            val id = prefsLocalStore.beginWalletOperation(intent)
            when (val result = send(id)) {
                is PulseResult.Success -> {
                    prefsLocalStore.finishWalletOperation(intent, id)
                    Result.success(result.value)
                }
                is PulseResult.Failure -> Result.failure(IllegalStateException("${result.kind}: ${result.message}"))
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.failure(error)
        }
    }

    override suspend fun swapRates(): Result<SwapPageDto> =
        when (val r = api.swapRates()) {
            is PulseResult.Success -> Result.success(r.value)
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun swap(direction: String, amount: Long): Result<SwapResultDto> {
        val owner = viewerId ?: return Result.failure(IllegalStateException("No wallet owner"))
        val intent = "swap:$owner:$direction:$amount"
        return walletMutation(intent) { id -> api.swap(owner, direction, amount, id) }
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

    // Wave 6 - social graph & discovery

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
            // The uploads route returns a bare filename; the users PATCH route
            // only accepts a server-managed "/api/uploads/<file>" path (the
            // web prefixes before PATCHing). "" = clear stays untouched.
            patch.avatar?.let { put("avatar", normalizeUploadPath(it)) }
            patch.coverImage?.let { put("coverImage", normalizeUploadPath(it)) }
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
                // The relay bumps every participant's list - refresh ours now.
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

        /** Web MAX_QUEUE parity - the outbox never holds more rows. */
        const val MAX_OUTBOX = 50

        /** Web MAX_DRAFT parity. */
        const val MAX_DRAFT = 2000

        /** Self-heal beat while a flush is pending (plan §6). */
        const val SELF_HEAL_MS = 20_000L

        /** Handles nobody may claim - offline safety net mirroring registry/handles.json. */
        private val BUILT_IN_RESERVED = setOf(
            "admin", "administrator", "root", "system", "support", "help", "team",
            "official", "moderator", "mod", "pulse", "staff", "security", "noreply",
            "notifications", "bot", "api", "gs",
        )
    }
}

// wire → domain mappers (kept beside the cache they feed)

fun ConversationSummaryDto.toDomain(viewerId: String?, previous: Conversation? = null): Conversation {
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
    val mapped = Conversation(
        id = id,
        kind = kind,
        title = title,
        avatar = photo ?: others.firstOrNull()?.avatar,
        lastMessagePreview = if (last == null) null else collapsed,
        lastMessageAuthorName = last?.sender?.name,
        lastMessageKind = last?.kind,
        // Web row stamp prefers the last message's time, falling back to updatedAt.
        lastActivityAt = last?.createdAt ?: updatedAt,
        unreadCount = unreadCount ?: 0,
        lastMessageExpiresAtEpochMs = PulseTime.epochMs(last?.expiresAt).takeIf { it > 0 },
        unreadExpirations = unreadExpirations.mapNotNull {
            val deadline = PulseTime.epochMs(it.expiresAt)
            if (deadline > 0 && it.count > 0) app.pulse.domain.model.UnreadExpiration(deadline, it.count) else null
        },
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
    // Detail endpoints omit lastMessage/unread and viewer pin/archive fields.
    // Opening room info must not wipe the inbox preview, badge or expiry schedule.
    if (unreadCount != null || previous == null) return mapped
    return mapped.copy(
        lastMessagePreview = previous.lastMessagePreview,
        lastMessageAuthorName = previous.lastMessageAuthorName,
        lastMessageKind = previous.lastMessageKind,
        lastActivityAt = previous.lastActivityAt,
        unreadCount = previous.unreadCount,
        unreadExpirations = previous.unreadExpirations,
        lastMessageExpiresAtEpochMs = previous.lastMessageExpiresAtEpochMs,
        lastMessageMine = previous.lastMessageMine,
        lastMessageDeleted = previous.lastMessageDeleted,
        lastMessageIsReply = previous.lastMessageIsReply,
        lastMessageIsImage = previous.lastMessageIsImage,
        lastMessageIsAudio = previous.lastMessageIsAudio,
        lastMessageIsFile = previous.lastMessageIsFile,
        lastMessageFileName = previous.lastMessageFileName,
        isPinned = previous.isPinned, isArchived = previous.isArchived,
        isMuted = previous.isMuted, mutedUntilEpoch = previous.mutedUntilEpoch,
    )
}

/** myStreak/deadStreak/lostStreak are {"count": n} on the wire - tolerate ints too. */
private fun streakCountOf(el: kotlinx.serialization.json.JsonElement?): Int = runCatching {
    (el?.jsonObject?.get("count") as? JsonPrimitive)?.intOrNull
        ?: el?.jsonPrimitive?.intOrNull
        ?: 0
}.getOrDefault(0)

/** R70 - the streak "best" field the room-info streak row renders (0 = absent). */
private fun streakBestOf(el: kotlinx.serialization.json.JsonElement?): Int = runCatching {
    (el?.jsonObject?.get("best") as? JsonPrimitive)?.intOrNull ?: 0
}.getOrDefault(0)

fun UserDto.toDomain(): User = User(
    id = id,
    name = name,
    handle = username ?: "",
    avatar = avatar,
    coverImage = coverImage,
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
    coverImage = coverImage,
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
    // R18 icon-id contract - normalize on read (stale emoji to the default).
    emoji = folderIconId(emoji),
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
    // the inline quote - they are DIFFERENT axes and never merged.
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
    // Wave 2 depth (tolerant: absent/garbled keys degrade to null)
    viewedAt = PulseTime.epochMs(viewedAt).takeIf { it > 0L },
    transcript = transcript,
    transcribedAt = PulseTime.epochMs(transcribedAt).takeIf { it > 0L },
    // Poll pick derives from options[].votedBy via PollInfo.pickFor - the
    // wire myOptionId is actor-relative on relays and NEVER trusted (spec §1 row 2).
    poll = poll.decodePollDto()?.toInfo(),
    linkPreview = linkPreview.decodeLinkPreviewDto()?.toInfo(),
    topicId = topicId,
    // Wave 7 rich objects ride payload as a RAW JSON STRING (red packet / game /
    // tournament carriers) - cards parse tolerantly via PulseWave7Logic.
    payload = payload.objectPayloadText(),
    // REM-A - incognito + disappearing (F-MS-17/19): wire carries the anon flag,
    // the deterministic alias, and the purge deadline (ISO → epoch ms).
    anon = anon == true,
    anonAlias = anonAlias,
    expiresAtEpochMs = PulseTime.epochMs(expiresAt).takeIf { it > 0L },
)

// Wave 2 wire → domain mappers (saved library + topics)

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
    // R18 icon-id contract - normalize on read so stale emoji literals or
    // unknown values never reach the UI (they resolve to the default id).
    emoji = topicIconId(emoji),
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

/**
 * Upload paths the wire accepts are "/api/uploads/<file>" (validated by the
 * users + conversations PATCH routes); the uploads route itself returns a
 * bare "<uuid>.<ext>" filename. Blank ("clear") passes through untouched.
 */
internal fun normalizeUploadPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.startsWith("/api/uploads/")) trimmed else "/api/uploads/$trimmed"
}

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
    // as large emoji - never the system-pill fallback.
    "sticker" -> Message.Kind.STICKER
    // R1-W2F (F-MD-07): pin rows carry payload {lat,lng,label}.
    "location" -> Message.Kind.LOCATION
    else -> Message.Kind.SYSTEM
}

/**
 * Identity-flow failure that keeps the wire's error/code/suggestion intact -
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
            // communication … not permitted") - humans get real copy instead.
            val human = if (f.status == null) "Can't reach the Pulse server - check your connection." else f.message
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

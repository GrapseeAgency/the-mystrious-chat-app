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
import app.pulse.data.local.SavedDao
import app.pulse.data.local.SavedMessageEntity
import app.pulse.data.local.TopicDao
import app.pulse.data.local.TopicEntity
import app.pulse.data.local.toInfo
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.CallStatus
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.MentionItem
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
import app.pulse.domain.model.Reaction
import app.pulse.domain.model.SavedItem
import app.pulse.domain.model.StoryCell
import app.pulse.domain.model.Topic
import app.pulse.domain.model.TranscribeOutcome
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.FlushOutboxUseCase
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.CallAnswerDto
import app.pulse.protocol.CallCancelDto
import app.pulse.protocol.CallHangupDto
import app.pulse.protocol.CallIceDto
import app.pulse.protocol.CallLogCreatedDto
import app.pulse.protocol.CallLogItemDto
import app.pulse.protocol.CallOfferDto
import app.pulse.protocol.CallRejectDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.SavedItemDto
import app.pulse.protocol.TopicDto
import app.pulse.protocol.UserDto
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
    private val socket: PulseSocketClient,
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
                    is PulseSocketClient.Signal.VoiceRoster -> Unit
                    is PulseSocketClient.Signal.VoicePtt -> Unit
                    is PulseSocketClient.Signal.VoiceChunk -> Unit
                    is PulseSocketClient.Signal.VoiceTranscript -> Unit
                    is PulseSocketClient.Signal.StageState -> Unit
                    is PulseSocketClient.Signal.StageEnded -> Unit
                    is PulseSocketClient.Signal.SpaceState -> Unit
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
            is PulseResult.Success -> Result.success(r.value.toDomain())
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
                    Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
                }
        }
    }

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
     * location|file). Never queued — media sends are online-only (spec §1.2).
     */
    override suspend fun forwardMessage(targetConversationId: String, source: Message): Result<Message> =
        when (
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

    /** Domain kind → wire kind (whitelist text|image|audio|sticker|location|file). */
    private fun wireKindOf(kind: Message.Kind): String? = when (kind) {
        Message.Kind.TEXT -> "text"
        Message.Kind.IMAGE -> "image"
        Message.Kind.VOICE -> "audio"
        Message.Kind.FILE -> "file"
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

    override suspend fun conversationDetail(conversationId: String): Result<Conversation> =
        when (val r = api.conversationDetail(conversationId, viewerId ?: "")) {
            is PulseResult.Success -> {
                val conversation = r.value.toDomain(viewerId)
                conversationDao.upsertAll(listOf(ConversationEntity.from(conversation)))
                Result.success(conversation)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
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

    override suspend fun block(userId: String): Result<Unit> = api.postAction("/api/users/$userId/block").toResult()
    override suspend fun unblock(userId: String): Result<Unit> = api.postAction("/api/users/$userId/unblock").toResult()
    override suspend fun report(userId: String, reason: String, details: String?): Result<Unit> =
        api.postAction("/api/users/$userId/report", PulseApi.jsonOf("reason" to reason, "details" to details)).toResult()

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

    override suspend fun stories(): Result<List<StoryCell>> = when (val r = api.stories(viewerId ?: "")) {
        is PulseResult.Success -> Result.success(
            r.value.groups.mapNotNull { group ->
                val user = group.user ?: return@mapNotNull null
                if (group.stories.isEmpty()) return@mapNotNull null
                StoryCell(
                    userId = user.id,
                    name = user.name,
                    color = user.color,
                    mine = group.mine,
                    unseen = !group.allSeen,
                )
            },
        )
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
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
            val payload = runCatching {
                PulseJson.parseToJsonElement(row.payloadJson).jsonObject
            }.getOrElse {
                Log.w(TAG, "call-log queue row ${row.id} unparseable — dropped", it)
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

private fun kindOf(wire: String): Message.Kind = when (wire) {
    "text" -> Message.Kind.TEXT
    "image" -> Message.Kind.IMAGE
    "voice" -> Message.Kind.VOICE
    "video" -> Message.Kind.VIDEO
    "file" -> Message.Kind.FILE
    "poll" -> Message.Kind.POLL
    "red_packet" -> Message.Kind.RED_PACKET
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

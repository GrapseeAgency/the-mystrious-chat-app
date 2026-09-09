package app.pulse.data.repository

import android.util.Log
import app.pulse.core.result.PulseResult
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.ConversationEntity
import app.pulse.data.local.MessageDao
import app.pulse.data.local.MessageEntity
import app.pulse.data.local.PulseDatabase
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.Message
import app.pulse.domain.model.Reaction
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.UserDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

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
    private val socket: PulseSocketClient,
) : PulseRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsBus = MutableSharedFlow<PulseEvent>(
        replay = 0, extraBufferCapacity = 128,
    )
    private val onlineIds = MutableStateFlow<Set<String>>(emptySet())

    @Volatile override var viewerId: String? = null
        private set

    @Volatile private var pumpStarted = false

    override fun start(userId: String) {
        if (viewerId == userId && socket.isJoined) return
        viewerId = userId
        startPump()
        socket.connect(userId)
        scope.launch { refreshConversations() }
    }

    /** One collector, started once per process: signals → Room + events. */
    private fun startPump() {
        if (pumpStarted) return
        pumpStarted = true
        scope.launch {
            socket.signals.collect { signal ->
                when (signal) {
                    is PulseSocketClient.Signal.Joined -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.PresenceSnapshot -> onlineIds.value = signal.onlineUserIds.toSet()
                    is PulseSocketClient.Signal.MessageNew -> onMessageNew(signal)
                    is PulseSocketClient.Signal.MessageDeleted -> {
                        // Tombstone in cache + event (message row content untouched in Room;
                        // screens treat the event as authoritative for live removal).
                        eventsBus.tryEmit(
                            PulseEvent.MessageDeleted(signal.conversationId, signal.messageId),
                        )
                        scheduleConversationsRefresh()
                    }
                    is PulseSocketClient.Signal.MessageRead -> eventsBus.tryEmit(
                        PulseEvent.MessageRead(signal.conversationId, signal.userId, signal.at),
                    )
                    is PulseSocketClient.Signal.Typing -> eventsBus.tryEmit(
                        PulseEvent.Typing(signal.conversationId, signal.userId, signal.userName, signal.isTyping),
                    )
                    is PulseSocketClient.Signal.VoiceTranscript -> Unit
                    is PulseSocketClient.Signal.CallSignal -> Unit
                }
            }
        }
    }

    private suspend fun onMessageNew(signal: PulseSocketClient.Signal.MessageNew) {
        val dto = runCatching {
            PulseJson.decodeFromString(ChatMessageDto.serializer(), signal.raw.toString())
        }.getOrNull() ?: return
        val message = dto.toDomain()
        messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
        eventsBus.tryEmit(PulseEvent.MessageReceived(signal.conversationId, message))
        scheduleConversationsRefresh()
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

    override fun observePresence(): Flow<Set<String>> = onlineIds.asStateFlow()

    override fun events(): Flow<PulseEvent> = eventsBus.asSharedFlow()

    override suspend fun refreshConversations(): Result<Unit> = when (val r = api.conversations(viewerId ?: "")) {
        is PulseResult.Success -> {
            conversationDao.upsertAll(r.value.conversations.map { ConversationEntity.from(it.toDomain(viewerId)) })
            Result.success(Unit)
        }
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> =
        when (val r = api.messages(conversationId, limit)) {
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
            is PulseResult.Failure -> Result.failure(OnboardingError.of(r))
        }

    override suspend fun checkHandle(handle: String): Result<HandleCheck> =
        when (val r = api.checkUsername(handle)) {
            is PulseResult.Success -> Result.success(HandleCheck(available = r.value.available, suggestion = r.value.suggestion))
            is PulseResult.Failure -> Result.failure(OnboardingError.of(r))
        }

    override suspend fun lookupUserByName(name: String): Result<User?> =
        when (val r = api.lookupUserByName(name)) {
            is PulseResult.Success -> Result.success(r.value.toDomain())
            // 404 = the name is free — the caller decides what that means
            is PulseResult.Failure if r.kind == PulseResult.Failure.Kind.NOT_FOUND -> Result.success(null)
            is PulseResult.Failure -> Result.failure(OnboardingError.of(r))
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
    override suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message> =
        when (val r = api.sendMessage(conversationId, viewerId ?: "", body)) {
            is PulseResult.Success -> {
                val message = r.value.toDomain()
                messageDao.upsertAll(listOf(MessageEntity.from(message, reactionsJsonOf(message))))
                scheduleConversationsRefresh()
                Result.success(message)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun markRead(conversationId: String): Result<Unit> = api.postAction("/api/conversations/$conversationId/read").toResult()

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

    override suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit> =
        api.postAction("/api/conversations/$conversationId/pin", PulseApi.jsonOf("pinned" to pinned)).toResult()

    override suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit> =
        api.postAction("/api/conversations/$conversationId/mute", PulseApi.jsonOf("muted" to muted)).toResult()

    override suspend fun archive(conversationId: String, archived: Boolean): Result<Unit> =
        api.postAction("/api/conversations/$conversationId/archive", PulseApi.jsonOf("archived" to archived)).toResult()

    override suspend fun block(userId: String): Result<Unit> = api.postAction("/api/users/$userId/block").toResult()
    override suspend fun unblock(userId: String): Result<Unit> = api.postAction("/api/users/$userId/unblock").toResult()
    override suspend fun report(userId: String, reason: String, details: String?): Result<Unit> =
        api.postAction("/api/users/$userId/report", PulseApi.jsonOf("reason" to reason, "details" to details)).toResult()

    private fun <T> PulseResult<T>.toResult(): Result<T> = when (this) {
        is PulseResult.Success -> Result.success(value)
        is PulseResult.Failure -> Result.failure(IllegalStateException("${kind.name}: $message"))
    }

    private fun reactionsJsonOf(m: Message): String? = if (m.reactions.isEmpty()) null else PulseJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(app.pulse.protocol.ReactionDto.serializer()),
        m.reactions.map { app.pulse.protocol.ReactionDto(emoji = it.emoji, userId = it.userId) },
    )

    companion object {
        private const val TAG = "PulseRepo"
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
    return Conversation(
        id = id,
        kind = kind,
        title = title,
        avatar = photo ?: others.firstOrNull()?.avatar,
        lastMessagePreview = lastMessage?.let { previewOf(it) },
        lastMessageAuthorName = lastMessage?.sender?.name,
        lastMessageKind = lastMessage?.kind,
        lastActivityAt = updatedAt ?: lastMessage?.createdAt,
        unreadCount = unreadCount,
        isPinned = !pinnedAt.isNullOrBlank(),
        isMuted = !mutedUntil.isNullOrBlank(),
        isArchived = !archivedAt.isNullOrBlank(),
        memberIds = members.map { it.id },
        memberNames = members.map { it.name },
        accentColor = others.firstOrNull()?.color ?: members.firstOrNull()?.color,
        streakCount = streakCountOf(myStreak),
        myDraft = myDraft,
        isSelf = isSelf == true,
    )
}

/** myStreak is {"count": n} on the wire (N2 parity fix) — tolerate ints too. */
private fun streakCountOf(el: kotlinx.serialization.json.JsonElement?): Int = runCatching {
    el?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull
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
    replyToId = parentId ?: replyTo?.id,
    threadRootId = null,
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

private fun previewOf(m: ChatMessageDto): String = when (m.kind) {
    "image" -> "Photo"
    "voice" -> "Voice message"
    "video" -> "Video"
    "file" -> m.fileName ?: "File"
    "poll" -> "Poll"
    else -> m.content
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
        fun of(f: PulseResult.Failure): OnboardingError =
            OnboardingError(f.status, f.code, f.suggestion, f.message)
    }
}

package app.pulse.data.repository

import app.pulse.core.result.PulseResult
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.ConversationEntity
import app.pulse.data.local.MessageDao
import app.pulse.data.local.MessageEntity
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * REAL remote-first, Room-cached repository (N2 wiring).
 * Reads flow from Room; refreshes pull the gateway and upsert; writes POST
 * and cache the returned row. Socket signals arrive separately via
 * [PulseSocketClient.signals].
 */
@Singleton
class PulseRepositoryImpl @Inject constructor(
    private val api: PulseApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    @Suppress("unused") private val socket: PulseSocketClient,
) : PulseRepository {

    private var viewerId: String = ""

    override fun start(userId: String) {
        viewerId = userId
        socket.connect(userId)
    }

    // ── reads ───────────────────────────────────────────────────
    override fun observeConversations(query: String): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows ->
            rows.map { it.toDomain() }
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeFor(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun refreshConversations(): Result<Unit> = when (val r = api.conversations(viewerId)) {
        is PulseResult.Success -> {
            conversationDao.upsertAll(r.value.conversations.map { ConversationEntity.from(it.toDomain()) })
            Result.success(Unit)
        }
        is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
    }

    override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> =
        when (val r = api.messages(conversationId, limit)) {
            is PulseResult.Success -> {
                messageDao.upsertAll(r.value.messages.map { MessageEntity.from(it.toDomain()) })
                Result.success(Unit)
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    // ── writes ──────────────────────────────────────────────────
    override suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message> =
        when (val r = api.sendMessage(conversationId, viewerId, body)) {
            is PulseResult.Success -> {
                messageDao.upsertAll(listOf(MessageEntity.from(r.value.toDomain())))
                Result.success(r.value.toDomain())
            }
            is PulseResult.Failure -> Result.failure(IllegalStateException("${r.kind}: ${r.message}"))
        }

    override suspend fun me(): User = TODO("N3: /api/auth session surface — web has no /me route yet")

    override suspend fun markRead(conversationId: String): Result<Unit> = api.postAction("/api/conversations/$conversationId/read").toResult()

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        socket.emitTyping(recipients = emptyList(), conversationId, viewerId, userName = "", isTyping = typing)
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
}

// ── wire → domain mappers (kept beside the cache they feed) ──────

fun ConversationSummaryDto.toDomain(): Conversation {
    val others = members.filter { it.id != lastMessage?.senderId }
    val title = name
        ?: members.filter { m -> m.id != others.firstOrNull()?.id }.firstOrNull()?.name
        ?: members.firstOrNull()?.name
        ?: "Conversation"
    val kind = when {
        broadcastMode == true -> Conversation.Kind.CHANNEL
        isGroup -> Conversation.Kind.GROUP
        else -> Conversation.Kind.DM
    }
    return Conversation(
        id = id,
        kind = kind,
        title = title,
        avatar = photo ?: members.firstOrNull()?.avatar,
        lastMessagePreview = lastMessage?.let { previewOf(it) },
        lastActivityAt = updatedAt ?: lastMessage?.createdAt,
        unreadCount = unreadCount,
        isPinned = !pinnedAt.isNullOrBlank(),
        isMuted = !mutedUntil.isNullOrBlank(),
        isArchived = !archivedAt.isNullOrBlank(),
    )
}

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
)

private fun kindOf(wire: String): Message.Kind = when (wire) {
    "text" -> Message.Kind.TEXT
    "image" -> Message.Kind.IMAGE
    "voice" -> Message.Kind.VOICE
    "video" -> Message.Kind.VIDEO
    "file" -> Message.Kind.FILE
    "poll" -> Message.Kind.POLL
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

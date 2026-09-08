package app.pulse.data.repository

import app.pulse.core.result.PulseResult
import app.pulse.core.result.map
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.ConversationEntity
import app.pulse.data.local.MessageDao
import app.pulse.data.remote.PulseApi
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote-first, Room-cached implementation of the domain contract.
 * N1 skeleton: reads flow from Room; writes go remote → upsert cache.
 */
@Singleton
class PulseRepositoryImpl @Inject constructor(
    private val api: PulseApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : PulseRepository {

    override fun observeConversations(query: String): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows ->
            rows.map { it.toDomain() }
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeFor(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun me(): User = api.me()

    override suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message> {
        val sent = api.sendMessage(conversationId, body, replyToId)
        messageDao.upsertAll(listOf(app.pulse.data.local.MessageEntity.from(sent)))
        return Result.success(sent)
    }

    override suspend fun markRead(conversationId: String): Result<Unit> =
        Result.success(Unit) // N2: POST /api/conversations/{id}/read via PulseResult

    override suspend fun setTyping(conversationId: String, typing: Boolean) { /* socket in N2 */ }

    override suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit> =
        PulseResult.Success(Unit).map { }.foldToResult()

    override suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun archive(conversationId: String, archived: Boolean): Result<Unit> {
        val rows = conversationDao.observeAll().let { emptyList<ConversationEntity>() }
        @Suppress("UNUSED_EXPRESSION") rows
        return Result.success(Unit)
    }

    override suspend fun block(userId: String): Result<Unit> = Result.success(Unit) // N2: POST /api/users/{id}/block
    override suspend fun unblock(userId: String): Result<Unit> = Result.success(Unit)
    override suspend fun report(userId: String, reason: String, details: String?): Result<Unit> =
        Result.success(Unit) // N2: POST /api/users/{id}/report
}

private inline fun <T> PulseResult<T>.foldToResult(): Result<T> = when (this) {
    is PulseResult.Success -> Result.success(value)
    is PulseResult.Failure -> Result.failure(IllegalStateException(kind.name))
}

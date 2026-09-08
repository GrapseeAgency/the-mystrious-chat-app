package app.pulse.domain.repository

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.User
import kotlinx.coroutines.flow.Flow

/** Contract every Pulse data source (remote-first, Room cache) must honor. */
interface PulseRepository {
    /** Bind the client to a viewer identity (REST userId + socket join). */
    fun start(userId: String)

    fun observeConversations(query: String = ""): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>

    /** Pull the latest lists from the gateway into the local cache. */
    suspend fun refreshConversations(): Result<Unit>
    suspend fun refreshMessages(conversationId: String, limit: Int = 200): Result<Unit>

    suspend fun me(): User
    suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message>
    suspend fun markRead(conversationId: String): Result<Unit>
    suspend fun setTyping(conversationId: String, typing: Boolean)
    suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit>
    suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit>
    suspend fun archive(conversationId: String, archived: Boolean): Result<Unit>
    suspend fun block(userId: String): Result<Unit>
    suspend fun unblock(userId: String): Result<Unit>
    suspend fun report(userId: String, reason: String, details: String?): Result<Unit>
}

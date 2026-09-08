package app.pulse.domain.repository

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.User
import kotlinx.coroutines.flow.Flow

/** Live events pushed by the relay — the UDF event side of the repository. */
sealed interface PulseEvent {
    data class MessageReceived(val conversationId: String, val message: Message) : PulseEvent
    data class MessageDeleted(val conversationId: String, val messageId: String) : PulseEvent
    data class MessageRead(val conversationId: String, val userId: String, val at: String?) : PulseEvent
    data class Typing(
        val conversationId: String,
        val userId: String,
        val userName: String,
        val isTyping: Boolean,
    ) : PulseEvent
}

/** Device-side preferences (DataStore on Android, UserDefaults on iOS). */
interface PulsePrefsStore {
    val viewerId: Flow<String?>
    val viewerName: Flow<String?>
    val fxMode: Flow<String>
    val darkOverride: Flow<String>
    val reducedMotion: Flow<Boolean>

    suspend fun setViewer(id: String?, name: String?)
    suspend fun setFxMode(mode: String)
    suspend fun setDarkOverride(value: String)
    suspend fun setReducedMotion(value: Boolean)
}

/** Contract every Pulse data source (remote-first, Room cache) must honor. */
interface PulseRepository {
    /** Bind the client to a viewer identity (REST userId + socket join). Idempotent per id. */
    fun start(userId: String)

    val viewerId: String?

    fun observeConversations(query: String = ""): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    fun observePresence(): Flow<Set<String>>
    fun events(): Flow<PulseEvent>

    /** Pull the latest lists from the gateway into the local cache. */
    suspend fun refreshConversations(): Result<Unit>
    suspend fun refreshMessages(conversationId: String, limit: Int = 200): Result<Unit>

    suspend fun users(query: String = ""): Result<List<User>>
    suspend fun createIdentity(name: String, color: String?): Result<User>
    suspend fun createDm(otherUserId: String): Result<Conversation>
    suspend fun createGroup(name: String, memberIds: List<String>): Result<Conversation>

    suspend fun me(): User?
    suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message>
    suspend fun markRead(conversationId: String): Result<Unit>
    suspend fun setTyping(conversationId: String, userName: String, typing: Boolean)
    suspend fun react(messageId: String, emoji: String): Result<Unit>
    suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit>
    suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit>
    suspend fun archive(conversationId: String, archived: Boolean): Result<Unit>
    suspend fun block(userId: String): Result<Unit>
    suspend fun unblock(userId: String): Result<Unit>
    suspend fun report(userId: String, reason: String, details: String?): Result<Unit>
}

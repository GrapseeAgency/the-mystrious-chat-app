package app.pulse.domain.repository

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.MentionItem
import app.pulse.domain.model.StoryCell
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
    /** The avatar palette key chosen at onboarding ("emerald"…"cyan") — header avatar. */
    val viewerColor: Flow<String?>
    val fxMode: Flow<String>
    val darkOverride: Flow<String>
    val reducedMotion: Flow<Boolean>
    /** Persisted chats-list filter — "all" | "unread" | "groups" (web ChatsListFilter). */
    val chatsListFilter: Flow<String>
    /** Optional user-set gateway origin ("https://host") — null/empty = offline-first. */
    val serverBase: Flow<String?>

    suspend fun setViewer(id: String?, name: String?, color: String? = null)
    suspend fun setFxMode(mode: String)
    suspend fun setDarkOverride(value: String)
    suspend fun setReducedMotion(value: Boolean)
    suspend fun setChatsListFilter(value: String)
    suspend fun setServerBase(value: String?)
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
    suspend fun createIdentity(name: String, color: String?, username: String? = null): Result<User>
    /** Live @handle availability for the onboarding picker (web check-username). */
    suspend fun checkHandle(handle: String): Result<HandleCheck>
    /** Case-insensitive name lookup (onboarding "that's me — log in"); null when free. */
    suspend fun lookupUserByName(name: String): Result<User?>
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

    // ── N10 home-page era (HOMEPAGE-SPEC §8/§14) ────────────────────

    /** PATCH /mark-unread {userId, on} — Telegram mark-as-unread dot. */
    suspend fun markUnread(conversationId: String, on: Boolean): Result<Unit>

    /** PATCH /mute {userId, until} — until: "8h" | "1w" | "always" | null. */
    suspend fun setMutedUntil(conversationId: String, until: String?): Result<Unit>

    /** POST /api/conversations/self {userId} — the viewer's Note to Self chat. */
    suspend fun createSelfChat(): Result<Conversation>

    /** GET /api/stories?requesterId= — null-safe: failure degrades to the honest empty rail. */
    suspend fun stories(): Result<List<StoryCell>>

    /** GET /api/folders?userId= — failure degrades to All-only rail. */
    suspend fun folders(): Result<List<FolderSummary>>

    /** GET /api/mentions?userId= — failure yields an honest 0 count. */
    suspend fun mentions(): Result<List<MentionItem>>

    /** GET /api/search?userId=&q= — server message search (≥2 chars, caller debounces). */
    suspend fun searchMessages(query: String): Result<List<MessageHit>>

    /** Full paginated-ish history for export/clear (single bounded fetch). */
    suspend fun fullHistory(conversationId: String): Result<List<Message>>

    /** DELETE /api/messages/{id} {requesterId} — sender-gated soft delete. */
    suspend fun deleteMessage(messageId: String): Result<Unit>

    /** Export the room transcript to a .txt file in app cache — returns the file name. */
    suspend fun exportChat(conversationId: String): Result<String>

    /** Soft-delete MY OWN non-deleted messages sequentially — returns the cleared count. */
    suspend fun clearMyMessages(conversationId: String): Result<Int>
}

package app.pulse.domain.model

import kotlinx.serialization.Serializable

/** A Pulse account — mirrors web `AppUser` (src/lib/types.ts). */
@Serializable
data class User(
    val id: String,
    val name: String,
    val handle: String,
    val avatar: String? = null,
    val bio: String? = null,
    val lastSeen: String? = null,
    val verified: Boolean = false,
)

/** A conversation in the chats list — mirrors web `ConversationSummary`. */
@Serializable
data class Conversation(
    val id: String,
    val kind: Kind,
    val title: String,
    val avatar: String? = null,
    val lastMessagePreview: String? = null,
    val lastActivityAt: String? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
) {
    enum class Kind { DM, GROUP, CHANNEL, SPACE, VOICE, STAGE }
}

/** A message — mirrors web `MessageDTO`. */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val authorId: String,
    val authorName: String,
    val kind: Kind,
    val body: String,
    val createdAt: String,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val replyToId: String? = null,
    val threadRootId: String? = null,
    val pinnedAt: String? = null,
    val viewedOnce: Boolean = false,
) {
    enum class Kind { TEXT, IMAGE, VOICE, VIDEO, FILE, POLL, RED_PACKET, SYSTEM }
}

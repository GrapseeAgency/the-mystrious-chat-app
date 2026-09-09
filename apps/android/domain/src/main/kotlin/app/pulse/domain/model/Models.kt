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
    val color: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
)

/** GET /api/users/check-username outcome — onboarding @handle picker (web parity). */
data class HandleCheck(
    val available: Boolean,
    val suggestion: String? = null,
)

/** One emoji reaction on a message (toggled per user, wire parity with web). */
@Serializable
data class Reaction(
    val emoji: String,
    val userId: String,
)

/** A conversation in the chats list — mirrors web `ConversationSummary`. */
@Serializable
data class Conversation(
    val id: String,
    val kind: Kind,
    val title: String,
    val avatar: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAuthorName: String? = null,
    val lastMessageKind: String? = null,
    val lastActivityAt: String? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val memberIds: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val accentColor: String? = null,
    val streakCount: Int = 0,
    val myDraft: String? = null,
    val isSelf: Boolean = false,
) {
    enum class Kind { DM, GROUP, CHANNEL, SPACE, VOICE, STAGE }

    val isGroupish: Boolean get() = kind != Kind.DM
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
    val reactions: List<Reaction> = emptyList(),
    /** Denormalized for rendering without a join. */
    val replyToBody: String? = null,
    val replyToAuthor: String? = null,
    val senderColor: String? = null,
    val viaAutomation: Boolean = false,
    val durationMs: Long? = null,
) {
    enum class Kind { TEXT, IMAGE, VOICE, VIDEO, FILE, POLL, RED_PACKET, SYSTEM }

    val isDeleted: Boolean get() = deletedAt != null
}

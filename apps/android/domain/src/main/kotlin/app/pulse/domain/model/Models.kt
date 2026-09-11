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

/**
 * One conversation participant with the read watermark (wire members[]).
 * Powers the ✓✓ read ticks (member.lastReadAt ≥ message createdAt) and the
 * message-info "seen by" sheet. Serialized into the Room `membersJson` cache.
 */
@Serializable
data class ConversationMember(
    val id: String,
    val name: String,
    val color: String? = null,
    /** Epoch ms of the member's read watermark — null = never read / unknown. */
    val lastReadAt: Long? = null,
    /** Wire role — "admin" | "member". */
    val role: String? = null,
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
    /** Full member rows with read watermarks (summaries + detail) — ticks/info sheet. */
    val members: List<ConversationMember> = emptyList(),
    val accentColor: String? = null,
    val streakCount: Int = 0,
    val myDraft: String? = null,
    val isSelf: Boolean = false,
    // N10 home-page era — web ConversationSummary parity (spec HOMEPAGE-SPEC §7/§14).
    /** Viewer flagged this row mark-as-unread (12dp dot even at 0 unread). */
    val myManualUnread: Boolean = false,
    /** deadStreak.count — a live chain that dies tonight (amber "ends tonight"). */
    val streakAtRiskCount: Int = 0,
    /** lostStreak.count > 0 — honestly lost chain (rose "streak lost"). */
    val streakLost: Boolean = false,
    val lastMessageMine: Boolean = false,
    val lastMessageDeleted: Boolean = false,
    val lastMessageIsReply: Boolean = false,
    val lastMessageIsImage: Boolean = false,
    val lastMessageIsAudio: Boolean = false,
    val lastMessageIsFile: Boolean = false,
    val lastMessageFileName: String? = null,
    /** Epoch ms when the viewer's mute window ends — 0 = not muted (future-check truth). */
    val mutedUntilEpoch: Long = 0,
    /** First non-viewer member id — DM presence lookup. Null when unknown. */
    val otherUserId: String? = null,
    /** Broadcast-only channel (web broadcastMode). */
    val isChannel: Boolean = false,
) {
    enum class Kind { DM, GROUP, CHANNEL, SPACE, VOICE, STAGE }

    val isGroupish: Boolean get() = kind != Kind.DM
}

/** One message hit from the server-side global search (GET /api/search). */
data class MessageHit(
    val id: String,
    val conversationId: String,
    val conversationName: String,
    val isGroup: Boolean,
    val senderName: String,
    val senderColor: String? = null,
    val content: String,
    val createdAt: String,
    val deleted: Boolean = false,
    val imageOnly: Boolean = false,
    val isFile: Boolean = false,
    val fileName: String? = null,
)

// ── Wave 2 messaging depth — polls / link previews / saved / topics / ASR ──

/** One option of a live poll with its per-user tally (wire votedBy mirror). */
@Serializable
data class PollOptionInfo(
    val id: String,
    val text: String,
    val position: Int = 0,
    val voteCount: Int = 0,
    val votedBy: List<String> = emptyList(),
)

/**
 * Parsed poll tally of a message (from the wire `message.poll` object).
 *
 * `myOptionId` is carried for completeness but is ACTOR-RELATIVE on relayed
 * rows (poll:voted maps the row with the VOTER as viewer → every recipient
 * would see the voter's pick as "mine") and null on history GETs — NEVER use
 * it. The viewer's own pick derives ONLY from [pickFor], which scans
 * options[].votedBy for the viewer id.
 */
@Serializable
data class PollInfo(
    val id: String,
    val question: String,
    val closed: Boolean = false,
    val options: List<PollOptionInfo> = emptyList(),
    val totalVotes: Int = 0,
    val myOptionId: String? = null,
) {
    /**
     * The viewer's own pick, derived from options[].votedBy ONLY — never from
     * [myOptionId] (actor-relative on relays, null on history). Null when the
     * viewer has not voted (or viewerId is unknown).
     */
    fun pickFor(viewerId: String?): String? =
        options.firstOrNull { viewerId != null && viewerId in it.votedBy }?.id
}

/** Open-Graph link preview attached to a message (wire linkPreview mirror). */
@Serializable
data class LinkPreviewInfo(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

/** One row of the saved library (GET /api/users/{id}/saved). */
@Serializable
data class SavedItem(
    val savedAt: Long,
    val conversationId: String,
    val conversationName: String?,
    val isGroup: Boolean,
    val message: Message,
)

/** One Zulip-style topic chip (General is NOT a row — implicit whole room). */
@Serializable
data class Topic(
    val id: String,
    val name: String,
    val emoji: String = "💬",
    val lastMessageAt: Long? = null,
    val messageCount: Int = 0,
)

/** Outcome of POST /api/messages/{id}/transcribe (cached: server ASR cache hit). */
@Serializable
data class TranscribeOutcome(
    val transcript: String,
    val transcribedAt: Long? = null,
    val cached: Boolean = false,
)

/** One cell in the chats stories rail (GET /api/stories — degrades to My status only). */
data class StoryCell(
    val userId: String,
    val name: String,
    val color: String? = null,
    /** this is the viewer's own cell ("My status") */
    val mine: Boolean,
    /** true → animated conic gradient ring; false → static ring */
    val unseen: Boolean,
)

/** Signal/Beeper-style chat folder (GET /api/folders — fails offline → empty rail). */
data class FolderSummary(
    val id: String,
    val name: String,
    val emoji: String = "",
    val conversationIds: List<String> = emptyList(),
)

/** One @mention row (GET /api/mentions) — the list pill only consumes the count. */
data class MentionItem(
    val messageId: String,
    val conversationId: String,
    val conversationName: String? = null,
    val authorName: String = "",
    val snippet: String = "",
    val createdAt: String = "",
)

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
    // Wave 1 media fields — image/file/voice rows render without extra fetches.
    /** Wire imagePath — served as {gateway}/api/uploads/{imagePath}. */
    val imagePath: String? = null,
    val audioPath: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** View-once render gate (consume itself is a Wave 2 non-goal). */
    val viewOnce: Boolean = false,
    // ── Wave 2 depth fields ─────────────────────────────────────
    /** Epoch ms when the view-once photo was consumed (wire viewedAt ISO → parsed). Null = unopened. */
    val viewedAt: Long? = null,
    /** Voice-note transcript (server ASR cache — visible to every member). */
    val transcript: String? = null,
    /** Epoch ms the transcript was produced (wire transcribedAt ISO → parsed). */
    val transcribedAt: Long? = null,
    /** Parsed poll tally (null on non-poll rows). Pick derives from PollInfo.pickFor, NEVER myOptionId. */
    val poll: PollInfo? = null,
    /** Parsed Open-Graph preview (null until the unfurl round-trip lands). */
    val linkPreview: LinkPreviewInfo? = null,
    /** Zulip-style topic the message is filed under — null = General (whole room). */
    val topicId: String? = null,
) {
    enum class Kind { TEXT, IMAGE, VOICE, VIDEO, FILE, POLL, RED_PACKET, SYSTEM }

    val isDeleted: Boolean get() = deletedAt != null
}

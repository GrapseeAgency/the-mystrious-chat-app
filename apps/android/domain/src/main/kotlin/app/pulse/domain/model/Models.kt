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

/** One story author group header (GET /api/stories groups[].user). */
data class StoryUser(
    val id: String,
    val name: String,
    val username: String? = null,
    val color: String? = null,
)

/**
 * One 24h status story (GET /api/stories stories[]).
 * Wire ISO-8601 UTC stamps are parsed to epoch-ms AT MAPPING TIME so every
 * consumer (expiry filter D2, relative labels) works on plain longs.
 */
data class StoryItem(
    val id: String,
    /** "image" | "text" — imagePath != null ⇒ image (wire kind is advisory). */
    val kind: String,
    val imagePath: String? = null,
    val caption: String = "",
    /** Palette key (emerald/rose/amber/violet/teal/orange/pink/cyan). */
    val background: String = "emerald",
    val createdAtEpochMs: Long = 0L,
    /** createdAt + 24h — stories with expiresAt <= now are dropped client-side (web-defect D2). */
    val expiresAtEpochMs: Long = 0L,
    val viewCount: Int = 0,
    val viewedByMe: Boolean = false,
    /** Raw wire stamps kept for the owner viewers sheet / future surfaces. */
    val createdAtIso: String? = null,
    val expiresAtIso: String? = null,
) {
    val isImage: Boolean get() = imagePath != null
}

/** One author's story stack (mine first per server order, stories oldest-first). */
data class StoryGroup(
    val user: StoryUser?,
    /** this group belongs to the requesting viewer */
    val mine: Boolean,
    /** every surviving story already viewed by the requester (recomputed after D2 expiry filtering) */
    val allSeen: Boolean,
    val stories: List<StoryItem>,
)

/** One row of the owner-only viewers list (GET /api/stories/{id}/view — oldest first). */
data class StoryViewer(
    val userId: String,
    val name: String,
    val username: String? = null,
    val color: String? = null,
    val viewedAtIso: String = "",
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

// ── Wave 6 — social graph & discovery domain models ─────────────────

/**
 * The FULL profile row behind a user page / edit form (GET+PATCH
 * /api/users/{id}). `lastSeenIso` is null when the owner scrubs it —
 * surfaces render "Last seen hidden", never a guess.
 */
data class UserProfile(
    val id: String,
    val name: String,
    val handle: String? = null,
    val about: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val createdAtIso: String? = null,
    val lastSeenIso: String? = null,
) {
    val firstName: String get() = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { name }
}

/** Live profile statistics (GET /api/users/{id}/stats). */
data class UserStats(
    val messages: Long = 0,
    val reactions: Long = 0,
    val photos: Long = 0,
    val voiceNotes: Long = 0,
    val chats: Long = 0,
    val groups: Long = 0,
    val days: Long = 0,
    val joinedAtIso: String? = null,
    val lastSeenIso: String? = null,
)

/**
 * R5-B — the conversation-streak state AFTER a send (server `streak` field,
 * messages/route.ts:678-706). `continued` mirrors the server's `lastDay ==
 * yesterdayUTC` growth check; a restart lands continued=false, count=1.
 */
data class StreakSnapshot(
    val count: Int = 0,
    val best: Int = 0,
    val continued: Boolean = false,
)

/**
 * R5-B — receipt of a DELIVERED send: the real row PLUS the streak bump the
 * server attached (null when the send didn't change today's streak —
 * same-day re-sends). The room fires the web-verbatim nudge from it.
 */
data class SendReceipt(
    val message: Message,
    val streak: StreakSnapshot? = null,
)

/** Safety-number pair state (GET /api/users/{id}/safety?userId=). */
data class SafetyState(
    val peerId: String,
    /** 60 digits, server-formatted as 12×5 space-joined. */
    val safetyNumber: String = "",
    val verified: Boolean = false,
    val verifiedAtIso: String? = null,
)

/** One row of the viewer's blocked-accounts list. */
data class BlockedAccount(
    val id: String,
    val name: String,
    val handle: String? = null,
    val avatar: String? = null,
    val color: String? = null,
    val blockedAtIso: String? = null,
)

/** Public invite preview (GET /api/invite/{code}?userId=). */
data class InvitePreview(
    val code: String,
    val conversationId: String,
    val name: String?,
    val memberCount: Int,
    val alreadyMember: Boolean,
)

/** POST /api/invite/{code}/join outcome — idempotent (alreadyMember=true is a no-op join). */
data class InviteJoinOutcome(
    val conversationId: String,
    val alreadyMember: Boolean,
)

/** One broadcast channel row (GET /api/channels). */
data class Channel(
    val id: String,
    val name: String,
    val description: String? = null,
    val memberCount: Int = 0,
    val isSubscribed: Boolean = false,
    val unread: Boolean = false,
    /** ≤60-char server snippet of the last post (null when none). */
    val preview: String? = null,
    val photo: String? = null,
)

/**
 * A PATCH /api/users/{id} payload — only fields the user touched ride the
 * body; `username` null = leave alone, "" = clear (wire contract).
 */
data class ProfilePatch(
    val name: String? = null,
    val about: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val username: String? = null,
)

// ── REM-A group governance + scheduling models ─────────────────────────────

/** Outcome of leaving a group (DELETE members) — succession bookkeeping included. */
data class GroupLeave(
    /** How many members remain in the group after the departure. */
    val remainingMembers: Int,
    /** Set when the leaver was the group's LAST admin — the server auto-promotes the longest-standing member. */
    val promotedUserId: String? = null,
)

/**
 * One pending delayed-send row (GET/POST /api/conversations/{id}/scheduled,
 * DELETE /api/scheduled/{id}) — web ScheduledItem parity.
 */
data class ScheduledItem(
    val id: String,
    val conversationId: String,
    val content: String,
    /** ISO-8601 wire stamp — the moment the server will dispatch it. */
    val scheduledAtIso: String,
    val cancelledAtIso: String? = null,
    val cancelledReason: String? = null,
)

/**
 * Server detail meta for the group-info surface (web ConversationDetail
 * subset). Fetched live on room/info open — NOT persisted in Room (schema v9
 * is frozen); every read is honest server truth.
 */
data class GroupMeta(
    /** THIS viewer's wire role — "admin" | "member" (null on DMs). */
    val myRole: String?,
    val isGroup: Boolean,
    /** Disappearing-message TTL seconds (0 = off). */
    val ttlSeconds: Int,
    /** Announcement mode — admins only post while on. */
    val broadcastMode: Boolean,
    /** Slow mode — members wait Ns between sends (0 = off). */
    val slowModeSeconds: Int,
    /** Signal screen security (room-wide flag). */
    val screenPrivacy: Boolean,
    /** R42 per-VIEWER screen security (the personal veil flag). */
    val myScreenPrivacy: Boolean = false,
    /** Shareable join code — null = no active link. */
    val inviteCode: String?,
) {
    val isAdmin: Boolean get() = myRole == "admin"

    /** R42 — the room veils when EITHER flag is on (web screenPrivacyOn parity). */
    val screenPrivacyEffective: Boolean get() = screenPrivacy || myScreenPrivacy
}

/**
 * Send/write failure that keeps the wire's typed metadata intact. The message
 * text matches the legacy `"KIND: message"` shape so existing surfaces render
 * it unchanged; the composer reads [retryAfter] to run the slow-mode
 * countdown lockout (web ApiError.retryAfter parity, pulse-utils.ts).
 */
class PulseApiException(
    val kind: String,
    message: String?,
    val status: Int? = null,
    val retryAfter: Int? = null,
) : IllegalStateException(message ?: "Request failed")

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
    // ── Wave 7 rich-object payload (raw JSON string from the wire; null on text rows) ──
    val payload: String? = null,
    // ── REM-A — incognito + disappearing (F-MS-17/19) ──
    /** Wire anon flag — group-only server-side; the sender's own rows keep it true. */
    val anon: Boolean = false,
    /** Deterministic per (sender, conversation) mask ("Ember the Falcon"). */
    val anonAlias: String? = null,
    /** Epoch ms when this row evaporates (wire expiresAt ISO → parsed). Null = forever. */
    val expiresAtEpochMs: Long? = null,
) {
    // R1-W2F (F-MD-07): wire kind "location" — pin rows carry payload {lat,lng,label}.
    enum class Kind { TEXT, IMAGE, VOICE, VIDEO, FILE, POLL, RED_PACKET, GAME, TOURNAMENT, STICKER, LOCATION, SYSTEM }

    val isDeleted: Boolean get() = deletedAt != null
}

/**
 * R1-W2F — `kind=="location"` payload blob: {lat,lng,label} (web
 * LocationPayload / parseLocationPayload in location-share.tsx). lat/lng have
 * NO defaults on purpose: a garbled or partial blob fails the decode and the
 * bubble falls back to plain text — the same honesty as the web parser.
 */
@Serializable
data class LocationPayload(
    val lat: Double,
    val lng: Double,
    /** Blank on the wire → renderers fall back to "Current location". */
    val label: String = "",
)

/**
 * R1-W2F — one conversation's theme override (F-FX-05, web ConvTheme in
 * conv-theme.ts): wallpaper mirrors the global Appearance tokens and tint is
 * an optional accent layered over the room wallpaper. Stored under the prefs
 * key `chat.convThemes` as { conversationId: { wallpaper, tint? } } — the web
 * JSON shape, byte-identical.
 */
data class ConvTheme(
    val wallpaper: String,
    val tint: String? = null,
) {
    companion object {
        /** Web conv-theme.ts CONV_WALLPAPERS — the global Appearance token set. */
        val WALLPAPERS: List<String> = listOf("none", "aurora", "dusk", "forest", "mono")

        /** Web conv-theme.ts CONV_TINTS. */
        val TINTS: List<String> = listOf("emerald", "rose", "amber", "violet", "teal")

        /** Web MAX_CONV_THEMES — the /api/settings 4KB prefs budget keeps 48 safe. */
        const val MAX_MAP_ENTRIES = 48
    }
}

/**
 * One row of the viewer's quick-phrase rail (F-MS-29 — GET/POST/DELETE
 * /api/users/{id}/phrases). position is the server-side ordering key.
 */
data class QuickPhrase(
    val id: String,
    val text: String,
    val position: Int = 0,
)

/**
 * R39 — one keyword auto-reply rule (web `AutomationSummary`, types.ts:330).
 * The send path lands the reply as a real machine-sent message server-side;
 * native surfaces manage rows through /api/automations.
 */
data class Automation(
    val id: String,
    val conversationId: String,
    /** 2-40 chars, matched as a standalone phrase. */
    val trigger: String,
    /** 1-500 chars. */
    val reply: String,
    val enabled: Boolean,
    /** Lifetime fire count. */
    val hits: Long = 0,
    val lastFiredAtIso: String? = null,
    val createdAtIso: String? = null,
    /** Admin who authored the rule (also the author of fired replies). */
    val createdByName: String? = null,
)

/**
 * One Discord-style incoming webhook (web `WebhookItem`). `url` is the
 * RELATIVE ingest path from the wire (`/api/webhooks/{token}`) — surfaces
 * pair it with the configured server origin for copy/share.
 */
data class Webhook(
    val id: String,
    val name: String,
    val token: String,
    val avatarColor: String,
    val url: String,
    val createdAtIso: String? = null,
    val createdBy: String = "",
)

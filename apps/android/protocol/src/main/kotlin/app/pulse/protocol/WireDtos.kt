package app.pulse.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * REAL wire DTOs — reverse-engineered from the live gateway (:81), the same
 * JSON the Next.js API emits today. Source of truth mirrored in
 * packages/protocol/src/contracts.ts. Kotlin, Swift and TypeScript parse
 * these exact shapes.
 */
@Serializable
data class SenderDto(
    val id: String,
    val name: String,
    val username: String? = null,
    val color: String? = null,
    val avatar: String? = null,
)

@Serializable
data class ReactionDto(
    val id: String? = null,
    val userId: String? = null,
    /** Live wire groups reactions: { emoji, userIds, count } (serializers.groupReactions). */
    val userIds: List<String>? = null,
    val count: Int? = null,
    val emoji: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    /** wire kinds are lowercase: text|image|voice|video|file|poll|system|sticker|location|… */
    val kind: String = "text",
    val payload: JsonElement? = null,
    val createdAt: String,
    val updatedAt: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val sender: SenderDto? = null,
    val reactions: List<ReactionDto> = emptyList(),
    val replyTo: ChatMessageDto? = null,
    val parentId: String? = null,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val durationMs: Long? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val linkUrl: String? = null,
    val linkPreview: JsonElement? = null,
    val pinnedAt: String? = null,
    val pinnedBy: String? = null,
    val poll: JsonElement? = null,
    val topicId: String? = null,
    val transcript: String? = null,
    val transcribedAt: String? = null,
    val viaAutomation: Boolean? = null,
    val viewOnce: Boolean? = null,
    val viewedAt: String? = null,
    val viewedBy: JsonElement? = null,
    val anon: Boolean? = null,
    val anonAlias: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class MessagesPageDto(
    val messages: List<ChatMessageDto>,
    val hasMore: Boolean = false,
    val total: Int = 0,
)

/** GET /api/messages/{id}/thread?userId= → { parent, replies } (replies ascending). */
@Serializable
data class ThreadPageDto(
    val parent: ChatMessageDto? = null,
    val replies: List<ChatMessageDto> = emptyList(),
)

/** GET /api/conversations/{id}/pinned?userId= → { messages } (pinnedAt ascending). */
@Serializable
data class PinnedPageDto(
    val messages: List<ChatMessageDto> = emptyList(),
)

/** POST /api/messages/{id}/save { userId } → { saved } (per-user toggle). */
@Serializable
data class SavedToggleDto(
    val saved: Boolean = false,
)

/** POST /api/uploads { dataUrl } → 201 { filePath, imagePath } — NOT multipart. */
@Serializable
data class UploadResultDto(
    val filePath: String? = null,
    /** Legacy alias the image flow uses — same value as filePath for images. */
    val imagePath: String? = null,
)

@Serializable
data class ConversationMemberDto(
    val id: String,
    val name: String,
    val username: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val lastReadAt: String? = null,
    val role: String? = null,
)

@Serializable
data class ConversationSummaryDto(
    val id: String,
    val isGroup: Boolean = false,
    val name: String? = null,
    val photo: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val members: List<ConversationMemberDto> = emptyList(),
    val lastMessage: ChatMessageDto? = null,
    val unreadCount: Int = 0,
    val pinnedAt: String? = null,
    val mutedUntil: String? = null,
    val archivedAt: String? = null,
    val ttlSeconds: Int? = null,
    val broadcastMode: Boolean? = null,
    val isSelf: Boolean? = null,
    val myDraft: String? = null,
    val myManualUnread: Boolean? = null,
    val myStreak: JsonElement? = null,
    val deadStreak: JsonElement? = null,
    val lostStreak: JsonElement? = null,
)

@Serializable
data class ConversationsPageDto(
    val conversations: List<ConversationSummaryDto>,
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val username: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val bio: String? = null,
    val lastSeen: String? = null,
    val verified: Boolean? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val xp: Int? = null,
    val level: Int? = null,
)

@Serializable
data class UsersPageDto(
    val users: List<UserDto> = emptyList(),
)

/** GET /api/users/check-username — live @handle availability (onboarding picker). */
@Serializable
data class UsernameCheckDto(
    val available: Boolean = false,
    val suggestion: String? = null,
)

/** GET registry/handles.json — static-CDN availability source (offline-first onboarding). */
@Serializable
data class HandleRegistryDto(
    val reserved: List<String> = emptyList(),
    val taken: List<String> = emptyList(),
)

// ── N10 home-page era — search / stories / folders / mentions ────

/** GET /api/search — one message hit with the resolved conversation title. */
@Serializable
data class SearchHitDto(
    val id: String = "",
    val conversationId: String = "",
    val conversationName: String? = null,
    val isGroup: Boolean = false,
    val senderId: String = "",
    val content: String = "",
    val kind: String = "text",
    val createdAt: String = "",
    val deletedAt: String? = null,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val sender: SenderDto? = null,
)

@Serializable
data class SearchPageDto(
    val messages: List<SearchHitDto> = emptyList(),
    val total: Int = 0,
)

/** GET /api/folders — Signal-style folder rail (tolerant subset). */
@Serializable
data class FolderDto(
    val id: String = "",
    val name: String = "",
    val emoji: String = "",
    val position: Int? = null,
    val conversationIds: List<String> = emptyList(),
)

@Serializable
data class FoldersPageDto(
    val folders: List<FolderDto> = emptyList(),
)

/** GET /api/mentions — one @mention row (tolerant subset; the list pill counts only). */
@Serializable
data class MentionDto(
    val messageId: String = "",
    val conversationId: String = "",
    val conversationName: String? = null,
    val isGroup: Boolean = false,
    val author: SenderDto? = null,
    val snippet: String = "",
    val createdAt: String = "",
)

@Serializable
data class MentionsPageDto(
    val items: List<MentionDto> = emptyList(),
)

/**
 * GET /api/stories — 24h status rail. Tolerant: every field defaulted so a
 * shape drift can never crash the whole feed (house rule).
 *
 * StoryItem wire shape (web src/app/api/stories/route.ts mapStory):
 *   { id, kind: "image"|"text", imagePath, caption, background,
 *     createdAt: ISO, expiresAt: ISO (createdAt+24h), viewCount, viewedByMe }
 * `imagePath != null` ⇒ kind is "image" — the wire kind is advisory.
 */
@Serializable
data class StoryItemDto(
    val id: String = "",
    val kind: String? = null,
    val imagePath: String? = null,
    val caption: String = "",
    /** Palette key (emerald/rose/amber/violet/teal/orange/pink/cyan) — image stories are forced "emerald". */
    val background: String = "emerald",
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val viewCount: Int = 0,
    val viewedByMe: Boolean = false,
)

@Serializable
data class StoryGroupDto(
    val user: SenderDto? = null,
    val mine: Boolean = false,
    val allSeen: Boolean = false,
    val stories: List<StoryItemDto> = emptyList(),
)

@Serializable
data class StoriesPageDto(
    val groups: List<StoryGroupDto> = emptyList(),
)

/** POST /api/stories → 201 { story } (viewCount 0, viewedByMe false). */
@Serializable
data class StoryCreatedDto(
    val story: StoryItemDto? = null,
)

/** POST /api/stories/{id}/view { requesterId } → { viewCount, owner? } (owner short-circuits). */
@Serializable
data class StoryViewAckDto(
    val viewCount: Int = 0,
    val owner: Boolean? = null,
)

/** GET /api/stories/{id}/view?requesterId= (owner-only 403) → { viewers } oldest-first. */
@Serializable
data class StoryViewerDto(
    val userId: String = "",
    val name: String = "",
    val username: String? = null,
    val color: String? = null,
    val viewedAt: String = "",
)

@Serializable
data class StoryViewersDto(
    val viewers: List<StoryViewerDto> = emptyList(),
)

// ── Wave 2 messaging depth — polls / link previews / saved / topics / ASR ──

/**
 * One option of a live poll (wire shape inside message.poll.options).
 * `votedBy` carries the voter ids — the ONLY trusted source for the viewer's
 * own pick (server myOptionId is actor-relative on relayed rows and null on
 * history GETs — spec §1 row 2).
 */
@Serializable
data class PollOptionDto(
    val id: String,
    val text: String,
    val position: Int = 0,
    val voteCount: Int = 0,
    val votedBy: List<String> = emptyList(),
)

/**
 * Wire poll tally (message.poll): { id, question, closed, options[],
 * totalVotes, myOptionId } — NO `multiple`, NO `closesAt` (server is
 * single-choice + manual close only).
 *
 * `myOptionId` is parsed but NEVER trusted by clients: it is computed
 * relative to the actor the server mapped the row for, so on poll:voted
 * relays every recipient would see the voter's pick as "mine", and it is
 * null on history GETs. Derive the viewer's pick from options[].votedBy.
 */
@Serializable
data class PollDto(
    val id: String,
    val question: String,
    val closed: Boolean = false,
    val options: List<PollOptionDto> = emptyList(),
    val totalVotes: Int = 0,
    val myOptionId: String? = null,
)

/** Wire Open-Graph preview (message.linkPreview) — all metadata nullable. */
@Serializable
data class LinkPreviewDto(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

/** Resolved conversation display info of one saved item (DM name server-side). */
@Serializable
data class SavedConversationDto(
    val id: String,
    val isGroup: Boolean = false,
    val name: String? = null,
)

/** GET /api/users/{id}/saved → items[] rows: { savedAt, conversation, message }. */
@Serializable
data class SavedItemDto(
    val savedAt: String,
    val conversation: SavedConversationDto = SavedConversationDto(id = ""),
    val message: ChatMessageDto,
)

@Serializable
data class SavedPageDto(
    val items: List<SavedItemDto> = emptyList(),
)

/** One Zulip-style topic chip (General is NOT a row — implicit whole room). */
@Serializable
data class TopicDto(
    val id: String,
    val name: String,
    val emoji: String = "💬",
    val lastMessageAt: String? = null,
    val messageCount: Int = 0,
)

/** GET /api/conversations/{id}/topics?userId= → { topics: [...] }. */
@Serializable
data class TopicsPageDto(
    val topics: List<TopicDto> = emptyList(),
)

/** POST /api/conversations/{id}/topics → 200 existing / 201 new — { topic }. */
@Serializable
data class TopicEnvelopeDto(
    val topic: TopicDto? = null,
)

// ── Wave 3 native calls — REST /api/calls (src/lib/call-types.ts parity) ──

/** Peer info resolved server-side for history rows (wire CallPeerInfo). */
@Serializable
data class CallPeerInfoDto(
    val id: String = "",
    val name: String = "Unknown",
    val username: String? = null,
    val color: String = "emerald",
    val avatar: String? = null,
)

/**
 * One call-history row as returned by GET /api/calls (wire CallLogItem).
 * `outgoing` is relative to the requesting viewer; `peer` is the OTHER party.
 */
@Serializable
data class CallLogItemDto(
    val id: String = "",
    val conversationId: String = "",
    val callerId: String = "",
    val calleeId: String = "",
    /** "voice" | "video" — tolerant decode. */
    val kind: String = "voice",
    /** "completed" | "missed" | "declined" — tolerant decode. */
    val status: String = "missed",
    val durationSec: Long = 0,
    /** ISO-8601 startedAt — verbatim. */
    val startedAt: String = "",
    val outgoing: Boolean = false,
    val peer: CallPeerInfoDto? = null,
)

/** GET /api/calls?userId= → { items: [...] } (newest first, cap 50). */
@Serializable
data class CallLogsPageDto(
    val items: List<CallLogItemDto> = emptyList(),
)

/** POST /api/calls → 201 { item }. SINGLE-WRITER: the viewer is the caller. */
@Serializable
data class CallLogCreatedDto(
    val item: CallLogItemDto? = null,
)

/** POST /api/messages/{id}/transcribe { requesterId } → { transcript, transcribedAt, cached }. */
@Serializable
data class TranscribeResultDto(
    val transcript: String,
    val transcribedAt: String? = null,
    val cached: Boolean = false,
)

/** DELETE /api/topics/{id}?userId= → { ok: true }. */
@Serializable
data class OkDto(
    val ok: Boolean = false,
)

/** Tolerant poll decode from message.poll (any JsonElement shape → null on mismatch). */
fun JsonElement?.decodePollDto(): PollDto? = runCatching {
    val el = this ?: return@runCatching null
    PulseJson.decodeFromJsonElement(PollDto.serializer(), el)
}.getOrNull()

/** Tolerant Open-Graph decode from message.linkPreview. */
fun JsonElement?.decodeLinkPreviewDto(): LinkPreviewDto? = runCatching {
    val el = this ?: return@runCatching null
    PulseJson.decodeFromJsonElement(LinkPreviewDto.serializer(), el)
}.getOrNull()

/** One shared decoder for every Pulse client surface. */
val PulseJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/** Tolerant unwrap: takes `{"<key>": …}` inner object when present, else the root. */
fun JsonElement.unwrapOrRoot(key: String): JsonElement =
    (this as? JsonObject)?.get(key)?.takeIf { it is JsonObject } ?: this

/** Serializer handle for the Room reactions column (shared by data mappers). */
val ReactionListSerializer = kotlinx.serialization.builtins.ListSerializer(ReactionDto.serializer())

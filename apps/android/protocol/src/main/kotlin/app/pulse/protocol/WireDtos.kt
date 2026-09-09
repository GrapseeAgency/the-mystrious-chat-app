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

/** GET /api/stories — 24h status rail (tolerant subset: ring/name/mine/allSeen only). */
@Serializable
data class StoryItemDto(
    val id: String = "",
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

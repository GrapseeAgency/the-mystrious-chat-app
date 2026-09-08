package app.pulse.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

/** One shared decoder for every Pulse client surface. */
val PulseJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

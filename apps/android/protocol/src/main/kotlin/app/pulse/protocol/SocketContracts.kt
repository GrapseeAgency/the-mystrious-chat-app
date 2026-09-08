package app.pulse.protocol

import kotlinx.serialization.Serializable

/** Socket.IO contract — the pulse-socket relay's client-facing events. */
object SocketEvents {
    /** client → server: register identity ({ userId }) */
    const val JOIN = "join"
    /** server → client ack for join ({ onlineUserIds: [...] }) */
    const val JOINED = "joined"
    const val PRESENCE_SNAPSHOT = "presence:snapshot"
    const val TYPING = "typing"
    const val MESSAGE_NEW = "message:new"
    const val MESSAGE_DELETED = "message:deleted"
    const val MESSAGE_READ = "message:read"
    const val VOICE_TRANSCRIPT = "voice:transcript"
    const val CALL_OFFER = "call:offer"
    const val CALL_ANSWER = "call:answer"
    const val CALL_ICE = "call:ice"
    const val CALL_CANCEL = "call:cancel"
    const val CALL_HANGUP = "call:hangup"
}

@Serializable
data class JoinPayload(val userId: String)

@Serializable
data class JoinedAck(val onlineUserIds: List<String> = emptyList())

@Serializable
data class TypingPayload(
    val recipients: List<String>,
    val conversationId: String,
    val userId: String,
    val userName: String,
    val isTyping: Boolean,
)

@Serializable
data class MessageNewPayload(
    val conversationId: String,
    val message: ChatMessageDto? = null,
    val messageV2: JsonElementLike? = null,
)

/** Placeholder for payload shapes relayed opaquely (kept typed at the edge). */
typealias JsonElementLike = String

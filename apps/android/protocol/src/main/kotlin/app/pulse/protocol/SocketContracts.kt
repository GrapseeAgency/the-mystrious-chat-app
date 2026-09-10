package app.pulse.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Socket.IO contract — the COMPLETE pulse-socket relay surface, mirroring
 * packages/protocol/src/contracts.ts v2 exactly (CLIENT_EVENTS 22 C→S,
 * SERVER_EVENTS 27 S→C, NOTIFY_EVENTS 11 HTTP-relay whitelist). Every
 * payload DTO is tolerant: unknown keys are dropped (PulseJson
 * ignoreUnknownKeys) and every non-essential field defaults so partial
 * events still decode. Source of truth is contracts.ts — change there first.
 */
object SocketEvents {
    // ── client → server ─────────────────────────────────────────
    const val JOIN = "join"
    const val TYPING = "typing"
    const val VOICE_JOIN = "voice:join"
    const val VOICE_LEAVE = "voice:leave"
    const val VOICE_PTT = "voice:ptt"
    const val VOICE_CHUNK = "voice:chunk"
    const val VOICE_TRANSCRIPT = "voice:transcript"
    const val STAGE_JOIN = "stage:join"
    const val STAGE_HAND = "stage:hand"
    const val STAGE_APPROVE = "stage:approve"
    const val STAGE_MUTE = "stage:mute"
    const val STAGE_END = "stage:end"
    const val STAGE_LEAVE = "stage:leave"
    const val SPACE_JOIN = "space:join"
    const val SPACE_MOVE = "space:move"
    const val SPACE_LEAVE = "space:leave"
    const val CALL_OFFER = "call:offer"
    const val CALL_ANSWER = "call:answer"
    const val CALL_ICE = "call:ice"
    const val CALL_REJECT = "call:reject"
    const val CALL_CANCEL = "call:cancel"
    const val CALL_HANGUP = "call:hangup"

    // ── server → client ─────────────────────────────────────────
    const val JOINED = "joined"
    const val PRESENCE_SNAPSHOT = "presence:snapshot"
    const val MESSAGE_NEW = "message:new"
    const val MESSAGE_DELETED = "message:deleted"
    const val MESSAGE_READ = "message:read"
    const val MESSAGE_REACT = "message:react"
    const val MESSAGE_EDITED = "message:edited"
    const val MESSAGE_PINNED = "message:pinned"
    const val MESSAGE_VIEWED = "message:viewed"
    const val POLL_VOTED = "poll:voted"
    const val LINK_PREVIEW = "link:preview"
    const val TRANSLATION_ADDED = "translation:added"
    const val CONVERSATION_UPDATED = "conversation:updated"
    const val VOICE_ROSTER = "voice:roster"
    const val STAGE_STATE = "stage:state"
    const val STAGE_ENDED = "stage:ended"
    const val SPACE_STATE = "space:state"

    /** Full C→S registry — flat mirror of contracts.ts CLIENT_EVENTS. */
    val CLIENT_EVENTS: List<String> = listOf(
        JOIN, TYPING,
        VOICE_JOIN, VOICE_LEAVE, VOICE_PTT, VOICE_CHUNK, VOICE_TRANSCRIPT,
        STAGE_JOIN, STAGE_HAND, STAGE_APPROVE, STAGE_MUTE, STAGE_END, STAGE_LEAVE,
        SPACE_JOIN, SPACE_MOVE, SPACE_LEAVE,
        CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_REJECT, CALL_CANCEL, CALL_HANGUP,
    )

    /** Full S→C registry — flat mirror of contracts.ts SERVER_EVENTS. */
    val SERVER_EVENTS: List<String> = listOf(
        JOINED, PRESENCE_SNAPSHOT, TYPING,
        MESSAGE_NEW, MESSAGE_DELETED, MESSAGE_READ,
        MESSAGE_REACT, MESSAGE_EDITED, MESSAGE_PINNED, MESSAGE_VIEWED,
        POLL_VOTED, LINK_PREVIEW, TRANSLATION_ADDED, CONVERSATION_UPDATED,
        VOICE_ROSTER, VOICE_PTT, VOICE_CHUNK, VOICE_TRANSCRIPT,
        STAGE_STATE, STAGE_ENDED,
        SPACE_STATE,
        CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_REJECT, CALL_CANCEL, CALL_HANGUP,
    )

    /** HTTP relay whitelist — POST /notify accepts exactly these. */
    val NOTIFY_EVENTS: List<String> = listOf(
        MESSAGE_NEW, MESSAGE_DELETED, MESSAGE_READ,
        MESSAGE_REACT, MESSAGE_EDITED, MESSAGE_PINNED, MESSAGE_VIEWED,
        POLL_VOTED, LINK_PREVIEW, TRANSLATION_ADDED, CONVERSATION_UPDATED,
    )

    /** Every S→C event that carries a fresh message row in its envelope. */
    val MESSAGE_ENVELOPE_EVENTS: List<String> = listOf(
        MESSAGE_NEW, MESSAGE_DELETED, MESSAGE_REACT, MESSAGE_EDITED, MESSAGE_PINNED,
        MESSAGE_VIEWED, POLL_VOTED, LINK_PREVIEW, TRANSLATION_ADDED,
    )
}

// ── presence ────────────────────────────────────────────────────

/** C→S join { userId } — registers presence + enters room user:{userId}. */
@Serializable
data class JoinPayload(val userId: String)

/** S→C ack for join. */
@Serializable
data class JoinedAck(val onlineUserIds: List<String> = emptyList())

/** S→C presence:snapshot — re-broadcast whenever anyone joins/leaves. */
@Serializable
data class PresenceSnapshotPayload(val onlineUserIds: List<String> = emptyList())

/**
 * typing — C→S carries `recipients`; the S→C relay drops them (the server
 * resolves rooms), so they decode as an empty list on the way back.
 */
@Serializable
data class TypingPayload(
    val recipients: List<String> = emptyList(),
    val conversationId: String = "",
    val userId: String = "",
    val userName: String = "",
    val isTyping: Boolean = false,
)

// ── message family ──────────────────────────────────────────────

/**
 * Envelope for every event that relays a fresh message row
 * (message:new/deleted/react/edited/pinned/viewed, poll:voted,
 * link:preview, translation:added). `message` is the authoritative row.
 */
@Serializable
data class SocketMessageEnvelope(
    val type: String = "",
    val message: ChatMessageDto? = null,
    /** ids of all members except the actor. */
    val recipientIds: List<String> = emptyList(),
    val conversationId: String? = null,
)

/** S→C message:read — a viewer advanced their watermark. */
@Serializable
data class ReadEventPayload(
    val conversationId: String = "",
    val userId: String = "",
    val lastReadAt: String? = null,
)

/** S→C conversation:updated — metadata changed (members, name, photo, flags). */
@Serializable
data class ConversationUpdatedPayload(
    val conversationId: String = "",
    val conversation: ConversationSummaryDto? = null,
)

// ── voice ───────────────────────────────────────────────────────

/** One member of a voice room (contracts.ts VoicePeer). */
@Serializable
data class VoicePeerDto(
    val userId: String = "",
    val name: String = "",
    val username: String? = null,
    val color: String = "",
    val joinedAt: Long? = null,
)

/** S→C voice:roster — who is in the voice room. */
@Serializable
data class VoiceRosterPayload(
    val conversationId: String = "",
    val roster: List<VoicePeerDto> = emptyList(),
)

/** S→C voice:ptt — push-to-talk latch state of one peer. */
@Serializable
data class VoicePttPayload(
    val conversationId: String = "",
    val userId: String = "",
    val active: Boolean = false,
)

/** voice:chunk — 16 kHz PCM base64 frames (~4 s), relayed to the room. */
@Serializable
data class VoiceChunkPayload(
    val conversationId: String = "",
    val userId: String = "",
    val seq: Long = 0,
    val data: String = "",
)

/** S→C voice:transcript — ephemeral caption strip. */
@Serializable
data class VoiceTranscriptPayload(
    val conversationId: String = "",
    val userId: String = "",
    val text: String = "",
)

// ── stage / space ───────────────────────────────────────────────

/** S→C stage:state — full stage roster (host/speakers/hand-queue/muted). */
@Serializable
data class StageStatePayload(
    val conversationId: String = "",
    val state: JsonElement? = null,
)

/** S→C stage:ended — host closed the stage. */
@Serializable
data class StageEndedPayload(val conversationId: String = "")

/** S→C space:state — spatial map snapshot (players + positions). */
@Serializable
data class SpaceStatePayload(
    val conversationId: String = "",
    val state: JsonElement? = null,
)

// ── call signaling (mirrors web src/lib/call-types.ts — R33-a) ──

/** Thin ICE candidate triple — JSON-safe everywhere. */
@Serializable
data class CallCandidateDto(
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/**
 * Tolerant call:* payload — ONE shape covers every call event the relay
 * emits (offer/answer/ice/reject/cancel/hangup): the five CallSignalBase
 * fields are always present, everything else is optional per event.
 */
@Serializable
data class CallSignalDto(
    val callId: String = "",
    val conversationId: String = "",
    val from: String = "",
    val to: String = "",
    /** "voice" | "video" — tolerant string, the caller decides the surface. */
    val kind: String = "voice",
    val sdp: String? = null,
    /** call:reject → "busy" | "declined"; call:cancel → "timeout" | "cancel" | "busy" | "offline". */
    val reason: String? = null,
    val candidate: CallCandidateDto? = null,
    /** call:hangup only. */
    val durationMs: Long? = null,
    // call:offer decoration — who is ringing (the callee may not know them).
    val callerName: String? = null,
    val callerColor: String? = null,
    val callerAvatar: String? = null,
)

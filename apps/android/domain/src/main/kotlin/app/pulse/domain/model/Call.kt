package app.pulse.domain.model

import kotlinx.serialization.Serializable

/**
 * Wave-3 native calls — domain models mirroring the wire contract in
 * src/lib/call-types.ts (R33-a) and the pinned W3-PLAN shared design.
 * Pure Kotlin: every wire field is restated here so :domain stays free of
 * :protocol/:data dependencies (same pattern as OutboxEntry).
 */

/** Audio-only vs camera call (wire "voice" | "video"). */
enum class CallKind(val wire: String) {
    VOICE("voice"),
    VIDEO("video");

    companion object {
        /** Tolerant wire decode — anything unknown degrades to VOICE. */
        fun of(wire: String?): CallKind = if (wire == VIDEO.wire) VIDEO else VOICE
    }
}

/** Terminal call-log status — the CALLER's client writes every row (wire statuses). */
enum class CallStatus(val wire: String) {
    COMPLETED("completed"),
    MISSED("missed"),
    DECLINED("declined");

    companion object {
        fun of(wire: String?): CallStatus = when (wire) {
            COMPLETED.wire -> COMPLETED
            DECLINED.wire -> DECLINED
            else -> MISSED
        }
    }
}

/** Why a ringing call ended without an answer (wire call:cancel reason). */
enum class CallCancelReason(val wire: String) {
    TIMEOUT("timeout"),
    CANCEL("cancel"),
    BUSY("busy"),
    OFFLINE("offline");

    companion object {
        /** Tolerant wire decode — unknown/absent degrades to CANCEL (web parity). */
        fun of(wire: String?): CallCancelReason =
            entries.firstOrNull { it.wire == wire } ?: CANCEL
    }
}

/** Who is on the other end of the call (web CallPeer overlay mirror). */
@Serializable
data class CallPeer(
    val id: String,
    val name: String,
    val color: String? = null,
    val avatar: String? = null,
)

/** Call direction — drives who is allowed to write the terminal log row. */
enum class CallDirection { OUTGOING, INCOMING }

/**
 * Call flow states (W3-PLAN shared design): idle → outgoingRinging →
 * connecting → connected → ended; idle → incomingRinging → connecting →
 * connected → ended. UI maps them onto the web overlay's five surfaces.
 */
enum class CallState { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED }

/**
 * Terminal outcome of one call session — maps 1:1 onto the wire CallStatus
 * (completed|missed|declined) via CallLogMapper. null = ended without any
 * outcome (e.g. mic-denied incoming attempt; the caller writes that row).
 */
enum class CallOutcome { COMPLETED, MISSED, DECLINED }

/**
 * One call-history row as the native client sees it (wire CallLogItem +
 * the POST /api/calls input). [outgoing] is relative to the viewer; `peer`
 * carries the resolved other-party identity for the history list.
 */
@Serializable
data class CallLogEntry(
    val id: String,
    val conversationId: String,
    val callerId: String,
    val calleeId: String,
    val kind: CallKind = CallKind.VOICE,
    val status: CallStatus = CallStatus.MISSED,
    val durationSec: Long = 0,
    /** ISO-8601 wire timestamp (startedAt) — verbatim, displayed relatively. */
    val startedAt: String = "",
    /** true when the listing viewer was the caller of this row. */
    val outgoing: Boolean = false,
    val peer: CallPeer? = null,
)

/**
 * A decoded call:* relay signal as the repository surface hands it to the
 * call engine. One tolerant shape covers every event — [kind] discriminates
 * nothing (both sides know the call), each consumer reads what it needs:
 * offer → [sdp]/[callerName]/[callerColor]/[callerAvatar], answer → [sdp],
 * ice → [candidate]/[sdpMid]/[sdpMLineIndex], cancel → [reason],
 * hangup → [durationSec].
 */
@Serializable
data class CallSignalData(
    /** The wire event name: "call:offer" | "call:answer" | "call:ice" | "call:reject" | "call:cancel" | "call:hangup". */
    val event: String,
    val callId: String = "",
    val conversationId: String = "",
    val from: String = "",
    val to: String = "",
    val kind: CallKind = CallKind.VOICE,
    val sdp: String? = null,
    val reason: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val durationSec: Long? = null,
    val callerName: String? = null,
    val callerColor: String? = null,
    val callerAvatar: String? = null,
)

/** A C→S call signal waiting to be emitted — the exact wire payload fields. */
@Serializable
data class CallSignalOut(
    val event: String,
    val callId: String,
    val conversationId: String,
    val from: String,
    val to: String,
    val kind: CallKind,
    val sdp: String? = null,
    val reason: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val durationSec: Long? = null,
    val callerName: String? = null,
    val callerColor: String? = null,
    val callerAvatar: String? = null,
)

package app.pulse.domain.model

import kotlinx.serialization.Serializable

/**
 * R8 Task 3-c - GROUP call domain models (mesh WebRTC), mirroring the wire
 * contract served by the pulse-socket relay (`gcall:*`) and the web session
 * in src/components/chat/group-call-overlay.tsx. Pure Kotlin, restated here
 * so :domain stays free of :protocol/:data dependencies (same pattern as the
 * 1:1 CallSignalData/CallSignalOut pair).
 */

/** One member of a group call (wire GroupCallMember, join-ordered). */
@Serializable
data class GroupCallMember(
    val id: String,
    val name: String = "Someone",
    val color: String = "emerald",
    val avatar: String? = null,
)

/**
 * A decoded gcall:* relay signal - one tolerant shape covers every event
 * (like the 1:1 [CallSignalData]):
 *   gcall:state → [members]/[hostId]/[startedAt]
 *   gcall:offer/answer → [sdp]
 *   gcall:ice → [candidate]/[sdpMid]/[sdpMLineIndex]
 *   gcall:ring → [callerName]/[callerColor]/[callerAvatar]/[title]
 *   gcall:ended → [reason]
 *   gcall:full → [max]
 */
@Serializable
data class GroupCallSignalData(
    /** The wire event name: gcall:state | offer | answer | ice | ring | ended | full. */
    val event: String,
    val callId: String = "",
    val conversationId: String = "",
    val from: String = "",
    val to: String = "",
    val kind: CallKind = CallKind.VOICE,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val hostId: String? = null,
    val startedAt: Long? = null,
    val members: List<GroupCallMember> = emptyList(),
    val title: String? = null,
    val reason: String? = null,
    val max: Int? = null,
    val callerName: String? = null,
    val callerColor: String? = null,
    val callerAvatar: String? = null,
)

/** A C→S gcall signal waiting to be emitted - the exact wire payload fields. */
@Serializable
data class GroupCallSignalOut(
    /** gcall:join | gcall:offer | gcall:answer | gcall:ice | gcall:leave. */
    val event: String,
    val conversationId: String,
    val kind: CallKind = CallKind.VOICE,
    /** The emitting member identity (join only) - id/name/color/avatar. */
    val user: GroupCallMember? = null,
    val callId: String = "",
    val from: String = "",
    val to: String = "",
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/**
 * The GET /api/group-call-state probe result - how a late-opening client
 * learns about an ONGOING call without a live ring (web probe parity).
 * [members] empty = no live call.
 */
@Serializable
data class GroupCallProbe(
    val callId: String? = null,
    val kind: CallKind = CallKind.VOICE,
    val startedAt: Long? = null,
    val members: List<GroupCallMember> = emptyList(),
)

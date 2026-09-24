package app.pulse.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R8 Task 3-c — GROUP call signaling (mesh WebRTC), the exact `gcall:*`
 * contract the pulse-socket relay (mini-services/pulse-socket/index.ts) has
 * served since R8-web. Web truth: src/components/chat/group-call-overlay.tsx.
 *
 *   C→S  gcall:join   { conversationId, kind, user:{id,name,color,avatar} }
 *   C→S  gcall:offer  { conversationId, callId, from, to, kind, sdp }
 *   C→S  gcall:answer { conversationId, callId, from, to, sdp }
 *   C→S  gcall:ice    { conversationId, callId, from, to, candidate, sdpMid, sdpMLineIndex }
 *   C→S  gcall:leave  { conversationId, from }
 *   S→C  gcall:state  { callId, conversationId, kind, hostId, startedAt, members[] join-ordered }
 *   S→C  gcall:ring   { conversationId, kind, caller:{...}, title }   (HTTP relay via /notify)
 *   S→C  gcall:ended  { conversationId, callId, reason }
 *   S→C  gcall:full   { conversationId, callId, max }
 *
 * MESH RULE (web-verbatim, group-call-overlay.tsx:287): between any two
 * members the one with the lexicographically SMALLER user id creates the
 * offer; the other answers. One peer connection per remote member, one local
 * media flow feeding all. Pure decision kernels live in [GroupCallMesh] so
 * the JVM tests pin them exactly like the web behavior.
 */
object GroupCallEvents {
    const val JOIN = "gcall:join"
    const val STATE = "gcall:state"
    const val OFFER = "gcall:offer"
    const val ANSWER = "gcall:answer"
    const val ICE = "gcall:ice"
    const val LEAVE = "gcall:leave"
    const val RING = "gcall:ring"
    const val ENDED = "gcall:ended"
    const val FULL = "gcall:full"

    /** MAX_GROUP_CALL_PARTICIPANTS on the relay — join #9 gets gcall:full. */
    const val MAX_PARTICIPANTS = 8
}

/** One member of a group call — the relay's join-ordered roster row. */
@Serializable
data class GroupCallMemberDto(
    val id: String = "",
    val name: String = "",
    val color: String = "emerald",
    val avatar: String? = null,
)

/** S→C gcall:state — the roster rides join-ordered (server sorts by joinedAt). */
@Serializable
data class GroupCallStatePayload(
    val callId: String = "",
    val conversationId: String = "",
    val kind: String = "voice",
    val hostId: String = "",
    val startedAt: Long? = null,
    val members: List<GroupCallMemberDto> = emptyList(),
)

/** C→S/S→C gcall:offer — joiner → existing member, relayed to the target's user room. */
@Serializable
data class GroupCallOfferDto(
    val conversationId: String = "",
    val callId: String = "",
    val from: String = "",
    val to: String = "",
    val kind: String = "voice",
    val sdp: String = "",
)

/** C→S/S→C gcall:answer — the targeted peer answers. */
@Serializable
data class GroupCallAnswerDto(
    val conversationId: String = "",
    val callId: String = "",
    val from: String = "",
    val to: String = "",
    val sdp: String = "",
)

/** C→S/S→C gcall:ice — flat candidate triple, targeted relay. */
@Serializable
data class GroupCallIceDto(
    val conversationId: String = "",
    val callId: String = "",
    val from: String = "",
    val to: String = "",
    val candidate: String = "",
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/** S→C gcall:ring — HTTP-relayed ring for ONLINE members (banner). */
@Serializable
data class GroupCallRingPayload(
    val type: String = "",
    val conversationId: String = "",
    val kind: String = "voice",
    val caller: GroupCallMemberDto? = null,
    val title: String = "",
)

/** S→C gcall:ended — the call was torn down (host/last member left, or kicked). */
@Serializable
data class GroupCallEndedPayload(
    val conversationId: String = "",
    val callId: String = "",
    val reason: String = "",
)

/** S→C gcall:full — join rejected (8 max). */
@Serializable
data class GroupCallFullPayload(
    val conversationId: String = "",
    val callId: String = "",
    val max: Int = GroupCallEvents.MAX_PARTICIPANTS,
)

// ── wire-perfect C→S JSON builders (pure kotlinx — JVM-test friendly) ──────

/** gcall:join — the relay identity-gates user.id against the socket's user. */
fun groupCallJoinPayload(
    conversationId: String,
    kind: String,
    user: GroupCallMemberDto,
): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("kind", kind)
    put("user", buildJsonObject {
        put("id", user.id)
        put("name", user.name)
        put("color", user.color)
        put("avatar", user.avatar ?: "")
    })
}

/** gcall:offer — every field the relay reads, nothing else. */
fun GroupCallOfferDto.toJsonObject(): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("callId", callId)
    put("from", from)
    put("to", to)
    put("kind", kind)
    put("sdp", sdp)
}

/** gcall:answer. */
fun GroupCallAnswerDto.toJsonObject(): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("callId", callId)
    put("from", from)
    put("to", to)
    put("sdp", sdp)
}

/** gcall:ice — flat candidate triple exactly like call:ice (not nested). */
fun GroupCallIceDto.toJsonObject(): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("callId", callId)
    put("from", from)
    put("to", to)
    put("candidate", candidate)
    if (sdpMid != null) put("sdpMid", sdpMid)
    if (sdpMLineIndex != null) put("sdpMLineIndex", sdpMLineIndex)
}

/** gcall:leave — explicit exit (roster rebroadcast; last member ends the call). */
fun groupCallLeavePayload(conversationId: String, from: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("from", from)
}

/**
 * The mesh decision kernels — every pure rule the web group-call session
 * applies, restated for the JVM tests to pin (web-verbatim semantics from
 * src/components/chat/group-call-overlay.tsx).
 */
object GroupCallMesh {

    /**
     * MESH RULE: between any two members, the one with the lexicographically
     * SMALLER user id creates the offer (web `if (me < member.id)`). Stateless
     * and glare-free — both sides of a pair independently agree on the roles.
     */
    fun shouldOffer(meId: String, peerId: String): Boolean = meId < peerId

    /**
     * Deterministic roster-sync plan (web applyRoster :252-312):
     *   • `departed` — peers whose ids are NOT in the roster anymore → close
     *     their peer connections + drop their ICE queues + remote streams;
     *   • `offerTo` — roster members I have NO peer connection for AND whose
     *     id sorts BELOW mine → I create the offer for that pair.
     * Members that sort above me will offer me (their own plan says so).
     */
    data class RosterPlan(
        val departedPeerIds: List<String>,
        val offerToPeerIds: List<String>,
    )

    fun planRoster(
        meId: String,
        rosterIdsJoinOrdered: List<String>,
        existingPeerIds: Set<String>,
    ): RosterPlan {
        val others = rosterIdsJoinOrdered.filter { it != meId }.distinct()
        val departed = existingPeerIds.filter { peerId -> others.none { it == peerId } }
        val offerTo = others.filter { it !in existingPeerIds && shouldOffer(meId, it) }
        return RosterPlan(departedPeerIds = departed, offerToPeerIds = offerTo)
    }

    /**
     * ICE gate (web gcall:ice handler :555-578): a candidate applies
     * immediately only when the peer connection exists AND its remote
     * description is set; otherwise it joins that peer's queue (drained in
     * arrival order right after the answer/offer SDP lands).
     */
    fun applyIceNow(pcExists: Boolean, remoteDescriptionSet: Boolean): Boolean =
        pcExists && remoteDescriptionSet

    /** Web formatCallDuration — H:MM:SS past one hour, M:SS below (call-overlay.tsx:97). */
    fun formatDuration(totalSec: Long): String {
        val sec = maxOf(0L, totalSec)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Web leaveCall summary (:389-392): sub-second exits stay honest ("You left"). */
    fun leaveSummary(elapsedSec: Long): String =
        if (elapsedSec >= 1) "You left · ${formatDuration(elapsedSec)}" else "You left"

    /** Web gcall:full toast (:595). */
    const val FULL_ERROR = "That call is full (8 max)"

    /** Web mic-denied error card (:354). */
    const val MIC_DENIED_ERROR =
        "Microphone access is needed for calls. Check the browser permissions and try again."

    /**
     * Ring banner headline (web :916-918): a titled call shows
     * "<title> · group call", otherwise "Group call from <name>".
     */
    fun ringTitle(title: String, callerName: String): String =
        if (title.isNotBlank()) "$title · group call" else "Group call from ${callerName.ifBlank { "Someone" }}"

    /** Ring banner subtitle (web :919-921). */
    fun ringSubtitle(kind: String, callerName: String): String =
        "${if (kind == "video") "Video call" else "Voice call"} · started by ${callerName.ifBlank { "Someone" }}"

    /** Ongoing banner headline (web :957-959). */
    fun ongoingTitle(memberCount: Int): String = "Ongoing group call · $memberCount in call"

    /** Ongoing banner subtitle — first 3 members' first names, ", "-joined (web :940-944). */
    fun ongoingNames(members: List<GroupCallMemberDto>): String =
        members.take(3).joinToString(", ") { it.name.trim().split(" ").firstOrNull().orEmpty() }

    /**
     * The ring gate (web :459-464): only an IDLE session shows a ring, and a
     * participant never sees their own ring echo.
     */
    fun shouldShowRing(sessionIdle: Boolean, fromSelf: Boolean): Boolean =
        sessionIdle && !fromSelf

    /**
     * gcall:state routing (web :468-493): I'm a member → roster sync; idle
     * outsider → ongoing banner state; anything else ignores the broadcast.
     */
    fun shouldSyncRoster(iAmMember: Boolean): Boolean = iAmMember

    fun shouldShowOngoing(
        iAmMember: Boolean,
        sessionIdle: Boolean,
        notJoinedElsewhere: Boolean,
    ): Boolean = !iAmMember && sessionIdle && notJoinedElsewhere
}

package app.pulse.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wave 5 — voice rooms / stage / space wire layer (spec WAVE5-VOICE-SPACES-
 * PARITY §0). The event-name constants live in [SocketEvents]; this file adds
 * the C→S payload builders (mirroring the Wave-3 call builder style — pure
 * kotlinx JsonObject, JVM-test friendly) and the typed S→C decoders for the
 * two JsonElement passthroughs (`stage:state`, `space:state`).
 *
 * Wire truth is the relay (mini-services/pulse-socket/index.ts):
 *   voice:join    { conversationId, user: {id, name, username, color} }
 *   voice:leave   { conversationId }
 *   voice:ptt     { conversationId, userId, on }
 *   voice:chunk   { conversationId, userId, seq, data }
 *   voice:transcript { conversationId, userId, text }
 *   stage:join    { conversationId, user: {…}, asHost }
 *   stage:hand    { conversationId, user: {id}, raised }
 *   stage:approve { conversationId, byUserId, targetUserId }
 *   stage:mute    { conversationId, byUserId, targetUserId }
 *   stage:end     { conversationId, byUserId }
 *   stage:leave   { conversationId }
 *   space:join    { conversationId, user: {…} }
 *   space:move    { conversationId, x, y }
 *   space:leave   { conversationId }
 */

/** The C→S user decoration every voice/stage/space join carries. */
@Serializable
data class PulseVoiceUser(
    val id: String,
    val name: String,
    val username: String? = null,
    val color: String = "",
)

/** POST /api/voice/transcribe → { transcript } (≤280 chars; 422 when empty). */
@Serializable
data class VoiceTranscriptResultDto(
    val transcript: String = "",
)

// ── C→S payload builders (wire-perfect, mirrors call* builders) ──────────

/** voice:join / stage:join / space:join user object — username omitted when null. */
private fun userJson(user: PulseVoiceUser): JsonObject = buildJsonObject {
    put("id", user.id)
    put("name", user.name)
    if (user.username != null) put("username", user.username)
    put("color", user.color)
}

/** voice:join — registers this socket's seat in `voice:{conversationId}`. */
fun voiceJoinPayload(conversationId: String, user: PulseVoiceUser): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("user", userJson(user))
}

/** voice:leave — releases the seat; the relay re-broadcasts the roster. */
fun voiceLeavePayload(conversationId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
}

/** voice:ptt — push-to-talk latch; identity-gated (seat must be this socket's). */
fun voicePttPayload(conversationId: String, userId: String, on: Boolean): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("userId", userId)
    put("on", on)
}

/** voice:chunk — 16kHz Int16LE PCM base64, 4000 samples (250ms), seq starts at 1. */
fun voiceChunkPayload(conversationId: String, userId: String, seq: Long, data: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("userId", userId)
    put("seq", seq)
    put("data", data)
}

/** voice:transcript — ephemeral caption (server caps 280 chars, rate-limits 700ms). */
fun voiceTranscriptPayload(conversationId: String, userId: String, text: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("userId", userId)
    put("text", text)
}

/** stage:join — ALWAYS asHost:false on entry; asHost:true only for claim-host. */
fun stageJoinPayload(conversationId: String, user: PulseVoiceUser, asHost: Boolean): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("user", userJson(user))
    put("asHost", asHost)
}

/** stage:hand — listener raise/lower; the wire carries `user:{id}` (not userId). */
fun stageHandPayload(conversationId: String, userId: String, raised: Boolean): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("user", buildJsonObject { put("id", userId) })
    put("raised", raised)
}

/** stage:approve — host promotes the hand at [targetUserId] into the speaker row. */
fun stageApprovePayload(conversationId: String, byUserId: String, targetUserId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("byUserId", byUserId)
    put("targetUserId", targetUserId)
}

/** stage:mute — host demotes a speaker (voice seat force-removed) or dismisses a hand. */
fun stageMutePayload(conversationId: String, byUserId: String, targetUserId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("byUserId", byUserId)
    put("targetUserId", targetUserId)
}

/** stage:end — host-only; every member receives stage:ended. */
fun stageEndPayload(conversationId: String, byUserId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("byUserId", byUserId)
}

/** stage:leave — releases the stage seat. */
fun stageLeavePayload(conversationId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
}

/** space:join — enters `space:{conversationId}` (previous position kept while alive). */
fun spaceJoinPayload(conversationId: String, user: PulseVoiceUser): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("user", userJson(user))
}

/** space:move — normalized 0..1 coords; server throttles 80ms + clamps. */
fun spaceMovePayload(conversationId: String, x: Double, y: Double): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("x", x)
    put("y", y)
}

/** space:leave — releases the spatial seat. */
fun spaceLeavePayload(conversationId: String): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
}

// ── typed S→C decoders for the two passthrough payloads ──────────────────
//
// The relay emits BOTH payloads with the room object at TOP level:
//   stage:state { conversationId, host, speakers, hands, listeners, listenerCount }
//   space:state { conversationId, players: [{id, name, color, x, y}] }
// The existing StageStatePayload/SpaceStatePayload DTOs carry the room as a
// JsonElement passthrough; these parsers accept either the wire shape or a
// nested `{"state": {…}}` wrapper, and are tolerant: entries without a
// string id+name are dropped, listenerCount falls back to listeners.size,
// and listenerCount never goes below zero.

/** One stage person — host/speakers/hands/listeners rows (relay shape). */
@Serializable
data class StagePersonDto(
    val id: String = "",
    val name: String = "",
    val color: String = "",
)

/** Decoded stage:state — the full stage roster this device renders. */
@Serializable
data class StageRoomState(
    val host: StagePersonDto?,
    val speakers: List<StagePersonDto>,
    val hands: List<StagePersonDto>,
    val listeners: List<StagePersonDto>,
    val listenerCount: Int,
)

/** One spatial player of space:state (positions normalized 0..1). */
@Serializable
data class SpacePlayerDto(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val x: Double = 0.5,
    val y: Double = 0.5,
)

/** Decoded space:state — the whole board, wholesale-replaced on every event. */
@Serializable
data class SpaceBoardState(
    val players: List<SpacePlayerDto>,
)

private fun stringField(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** id+name must both be non-blank strings — everything else is dropped. */
private fun personOf(obj: JsonObject): StagePersonDto? {
    val id = stringField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
    val name = stringField(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
    return StagePersonDto(id = id, name = name, color = stringField(obj, "color") ?: "")
}

private fun peopleOf(element: JsonElement?): List<StagePersonDto> {
    val arr = element as? JsonArray ?: return emptyList()
    return arr.mapNotNull { entry -> (entry as? JsonObject)?.let(::personOf) }
}

private fun roomObject(element: JsonElement?): Pair<JsonObject, JsonObject>? {
    val obj = element as? JsonObject ?: return null
    val nested = obj["state"] as? JsonObject
    return obj to (nested ?: obj)
}

/**
 * Parses a stage:state passthrough. Returns null when the element is not an
 * object or the conversationId is missing/blank (a malformed event must not
 * blank out a live stage render).
 */
fun parseStageRoomState(element: JsonElement?): StageRoomState? {
    val (payload, state) = roomObject(element) ?: return null
    val cid = stringField(payload, "conversationId")
        ?: stringField(state, "conversationId")
    if (cid.isNullOrBlank()) return null
    val host = (state["host"] as? JsonObject)?.let(::personOf)
    val speakers = peopleOf(state["speakers"])
    val hands = peopleOf(state["hands"])
    val listeners = peopleOf(state["listeners"])
    val rawCount = (state["listenerCount"] as? JsonPrimitive)?.content?.toIntOrNull()
    // Missing → listeners.size; negative → clamped to 0 (never render a negative count).
    val count = when {
        rawCount == null -> listeners.size
        rawCount < 0 -> 0
        else -> rawCount
    }
    return StageRoomState(
        host = host,
        speakers = speakers,
        hands = hands,
        listeners = listeners,
        listenerCount = count,
    )
}

private fun playerOf(obj: JsonObject): SpacePlayerDto? {
    val id = stringField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
    val name = stringField(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
    fun coord(key: String): Double =
        (obj[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.5
    return SpacePlayerDto(
        id = id,
        name = name,
        color = stringField(obj, "color") ?: "",
        x = coord("x").coerceIn(0.0, 1.0),
        y = coord("y").coerceIn(0.0, 1.0),
    )
}

/**
 * Parses a space:state passthrough. Returns null when the element is not an
 * object or the conversationId is missing/blank.
 */
fun parseSpaceBoardState(element: JsonElement?): SpaceBoardState? {
    val (payload, state) = roomObject(element) ?: return null
    val cid = stringField(payload, "conversationId")
        ?: stringField(state, "conversationId")
    if (cid.isNullOrBlank()) return null
    val arr = state["players"] as? JsonArray ?: return SpaceBoardState(players = emptyList())
    return SpaceBoardState(players = arr.mapNotNull { entry -> (entry as? JsonObject)?.let(::playerOf) })
}

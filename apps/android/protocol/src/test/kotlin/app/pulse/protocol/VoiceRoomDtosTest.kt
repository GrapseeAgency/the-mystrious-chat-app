package app.pulse.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave 5 wire contract — C→S builders must serialize to EXACTLY the relay
 * shapes (mini-services/pulse-socket/index.ts), and the two S→C passthrough
 * parsers must be tolerant to the documented degrees.
 */
class VoiceRoomDtosTest {

    private val user = PulseVoiceUser(id = "u1", name = "Ada", username = "ada", color = "emerald")

    @Test
    fun `voice join payload matches the wire shape`() {
        assertEquals("""{"conversationId":"c1","user":{"id":"u1","name":"Ada","username":"ada","color":"emerald"}}""", voiceJoinPayload("c1", user).toString())
    }

    @Test
    fun `username omitted when null`() {
        val json = voiceJoinPayload("c1", user.copy(username = null))
        assertFalse(json.toString().contains("username"))
    }

    @Test
    fun `stage join carries asHost — plain entry is always false`() {
        val json = stageJoinPayload("c1", user, asHost = false)
        assertEquals("false", (json["asHost"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(user.id, ((json["user"] as kotlinx.serialization.json.JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content)
        val claim = stageJoinPayload("c1", user, asHost = true)
        assertEquals("true", (claim["asHost"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `stage hand carries user-id wrapper — not a flat userId`() {
        val json = stageHandPayload("c1", "u9", raised = true)
        val wrapped = json["user"] as kotlinx.serialization.json.JsonObject
        assertEquals("u9", (wrapped["id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("true", (json["raised"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `ptt chunk transcript and move builders carry the exact keys`() {
        assertEquals("u2", ((voicePttPayload("c", "u2", on = true)["userId"]) as kotlinx.serialization.json.JsonPrimitive).content)
        val chunk = voiceChunkPayload("c", "u2", seq = 7, data = "AAAA")
        assertEquals("7", (chunk["seq"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("AAAA", (chunk["data"] as kotlinx.serialization.json.JsonPrimitive).content)
        val transcript = voiceTranscriptPayload("c", "u2", text = "hello")
        assertEquals("hello", (transcript["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        val move = spaceMovePayload("c", x = 0.25, y = 0.75)
        assertEquals("0.25", (move["x"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("0.75", (move["y"] as kotlinx.serialization.json.JsonPrimitive).content)
        for (bare in listOf(voiceLeavePayload("c"), stageLeavePayload("c"), spaceLeavePayload("c"))) {
            assertEquals("""{"conversationId":"c"}""", bare.toString())
        }
        assertEquals("u3", ((stageApprovePayload("c", "u1", "u3")["targetUserId"]) as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("u4", ((stageMutePayload("c", "u1", "u4")["targetUserId"]) as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("u1", ((stageEndPayload("c", "u1")["byUserId"]) as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `stage state parses the top-level wire shape`() {
        val wire = buildJsonObject {
            put("conversationId", "c1")
            put("host", buildJsonObject { put("id", "h1"); put("name", "Host"); put("color", "amber") })
            put("speakers", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("id", "s1"); put("name", "Spk"); put("color", "teal") },
            )))
            put("hands", kotlinx.serialization.json.JsonArray(emptyList()))
            put("listeners", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("id", "l1"); put("name", "Lst"); put("color", "") },
            )))
            put("listenerCount", 12)
        }
        val state = parseStageRoomState(wire)
        assertNotNull(state)
        assertEquals("h1", state!!.host?.id)
        assertEquals(1, state.speakers.size)
        assertEquals(12, state.listenerCount)
        assertEquals(1, state.listeners.size)
    }

    @Test
    fun `missing listenerCount falls back to listeners size - negative clamps to zero`() {
        val listeners = kotlinx.serialization.json.JsonArray(listOf(
            buildJsonObject { put("id", "l1"); put("name", "Lst") },
        ))
        val missing = buildJsonObject {
            put("conversationId", "c1")
            put("listeners", listeners)
        }
        assertEquals(1, parseStageRoomState(missing)!!.listenerCount)
        val negative = buildJsonObject {
            put("conversationId", "c1")
            put("listeners", listeners)
            put("listenerCount", -3)
        }
        assertEquals(0, parseStageRoomState(negative)!!.listenerCount)
    }

    @Test
    fun `stage entries without string id or name are dropped`() {
        val wire = buildJsonObject {
            put("conversationId", "c1")
            put("speakers", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("id", "s1"); put("name", "Ok") },
                buildJsonObject { put("name", "no-id") },
                buildJsonObject { put("id", "no-name") },
            )))
        }
        assertEquals(1, parseStageRoomState(wire)!!.speakers.size)
    }

    @Test
    fun `stage state without conversationId or non-object input is null`() {
        assertNull(parseStageRoomState(buildJsonObject { put("host", null) }))
        assertNull(parseStageRoomState(null))
        assertNull(parseStageRoomState(kotlinx.serialization.json.JsonPrimitive("nope")))
    }

    @Test
    fun `nested state wrapper is tolerated`() {
        val wire = buildJsonObject {
            put("conversationId", "c1")
            put("state", buildJsonObject {
                put("conversationId", "c1")
                put("host", buildJsonObject { put("id", "h1"); put("name", "Host") })
            })
        }
        assertEquals("h1", parseStageRoomState(wire)!!.host?.id)
    }

    @Test
    fun `space state parses players and clamps coords`() {
        val wire = buildJsonObject {
            put("conversationId", "c1")
            put("players", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("id", "p1"); put("name", "One"); put("color", "violet"); put("x", 0.25); put("y", 0.75) },
                buildJsonObject { put("id", "p2"); put("name", "Two"); put("x", 2.5); put("y", -1.0) },
                buildJsonObject { put("id", ""); put("name", "ghost") },
            )))
        }
        val board = parseSpaceBoardState(wire)!!
        assertEquals(2, board.players.size)
        assertEquals(0.25, board.players[0].x, 0.0)
        assertEquals(0.75, board.players[0].y, 0.0)
        // out-of-range coords clamp into 0..1
        assertEquals(1.0, board.players[1].x, 0.0)
        assertEquals(0.0, board.players[1].y, 0.0)
        assertNull(parseSpaceBoardState(buildJsonObject { put("players", kotlinx.serialization.json.JsonArray(emptyList())) }))
    }

    @Test
    fun `space state without conversationId is null`() {
        val wire = buildJsonObject {
            put("players", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        assertNull(parseSpaceBoardState(wire))
        assertNull(parseSpaceBoardState(null))
    }

    @Test
    fun `transcript result dto decodes tolerate-unknown`() {
        val dto = PulseJson.decodeFromString(
            VoiceTranscriptResultDto.serializer(),
            """{"transcript":"hi","extra":true}""",
        )
        assertEquals("hi", dto.transcript)
        assertTrue(PulseJson.decodeFromString(VoiceTranscriptResultDto.serializer(), "{}").transcript.isEmpty())
    }
}

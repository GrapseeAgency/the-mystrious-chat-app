package app.pulse.protocol

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave-0 contract test — every socket payload DTO decodes from real JSON
 * fixtures shaped exactly like packages/protocol/src/contracts.ts, tolerates
 * unknown keys (forward-compat) and tolerates missing/null fields.
 */
class SocketContractsTest {

    @Test
    fun `registry mirrors contracts ts exactly`() {
        val client = listOf(
            "join", "typing",
            "voice:join", "voice:leave", "voice:ptt", "voice:chunk", "voice:transcript",
            "stage:join", "stage:hand", "stage:approve", "stage:mute", "stage:end", "stage:leave",
            "space:join", "space:move", "space:leave",
            "call:offer", "call:answer", "call:ice", "call:reject", "call:cancel", "call:hangup",
        )
        val server = listOf(
            "joined", "presence:snapshot", "typing",
            "message:new", "message:deleted", "message:read",
            "message:react", "message:edited", "message:pinned", "message:viewed",
            "poll:voted", "link:preview", "translation:added", "conversation:updated",
            "voice:roster", "voice:ptt", "voice:chunk", "voice:transcript",
            "stage:state", "stage:ended",
            "space:state",
            "call:offer", "call:answer", "call:ice", "call:reject", "call:cancel", "call:hangup",
        )
        val notify = listOf(
            "message:new", "message:deleted", "message:read",
            "message:react", "message:edited", "message:pinned", "message:viewed",
            "poll:voted", "link:preview", "translation:added", "conversation:updated",
        )
        assertEquals(22, SocketEvents.CLIENT_EVENTS.size)
        assertEquals(27, SocketEvents.SERVER_EVENTS.size)
        assertEquals(11, SocketEvents.NOTIFY_EVENTS.size)
        assertEquals(client, SocketEvents.CLIENT_EVENTS)
        assertEquals(server, SocketEvents.SERVER_EVENTS)
        assertEquals(notify, SocketEvents.NOTIFY_EVENTS)
        // envelope family ⊆ server registry
        assertTrue(SocketEvents.MESSAGE_ENVELOPE_EVENTS.all { it in SocketEvents.SERVER_EVENTS })
        assertTrue(SocketEvents.NOTIFY_EVENTS.all { it in SocketEvents.SERVER_EVENTS })
    }

    @Test
    fun `join and joined ack decode`() {
        val join = PulseJson.decodeFromString(JoinPayload.serializer(), """{"userId":"u-1"}""")
        assertEquals("u-1", join.userId)

        val ack = PulseJson.decodeFromString(
            JoinedAck.serializer(),
            """{"onlineUserIds":["a","b"],"futureField":{"x":1}}""",
        )
        assertEquals(listOf("a", "b"), ack.onlineUserIds)
        // tolerant: missing array → empty
        assertEquals(emptyList<String>(), PulseJson.decodeFromString(JoinedAck.serializer(), "{}").onlineUserIds)
    }

    @Test
    fun `presence snapshot decodes with unknown keys`() {
        val snap = PulseJson.decodeFromString(
            PresenceSnapshotPayload.serializer(),
            """{"onlineUserIds":["a"],"room":"user:a","ts":1730000000}""",
        )
        assertEquals(listOf("a"), snap.onlineUserIds)
    }

    @Test
    fun `typing decodes both relay (no recipients) and client (with recipients) shapes`() {
        val relayed = PulseJson.decodeFromString(
            TypingPayload.serializer(),
            """{"conversationId":"c1","userId":"u2","userName":"Bob","isTyping":true,"extra":true}""",
        )
        assertEquals("c1", relayed.conversationId)
        assertEquals(true, relayed.isTyping)
        assertEquals(emptyList<String>(), relayed.recipients)

        val sent = PulseJson.decodeFromString(
            TypingPayload.serializer(),
            """{"recipients":["u2","u3"],"conversationId":"c1","userId":"u1","userName":"Alice","isTyping":false}""",
        )
        assertEquals(listOf("u2", "u3"), sent.recipients)
        assertEquals(false, sent.isTyping)
    }

    @Test
    fun `message envelope decodes the full authoritative row`() {
        val json = """
        {
          "type": "message:new",
          "message": {
            "id": "m1", "conversationId": "c1", "senderId": "u1", "content": "hello",
            "kind": "text", "createdAt": "2026-02-14T10:00:00.000Z",
            "sender": {"id": "u1", "name": "Alice", "color": "emerald"},
            "reactions": [{"emoji": "❤️", "userIds": ["u2"], "count": 1}],
            "replyTo": {"id": "m0", "conversationId": "c1", "senderId": "u2", "content": "hi", "createdAt": "2026-02-14T09:59:00.000Z"},
            "someFutureFlag": {"deep": [1, 2, 3]}
          },
          "recipientIds": ["u2"],
          "conversationId": "c1"
        }
        """.trimIndent()
        val envelope = PulseJson.decodeFromString(SocketMessageEnvelope.serializer(), json)
        assertEquals("message:new", envelope.type)
        assertEquals(listOf("u2"), envelope.recipientIds)
        assertEquals("c1", envelope.conversationId)
        // checkNotNull (not Jupiter assertNotNull — that one returns void):
        // a null row here IS a failure, and we need the decoded value below.
        val row = checkNotNull(envelope.message)
        assertEquals("m1", row.id)
        assertEquals("hello", row.content)
        assertEquals("Alice", row.sender?.name)
        assertEquals(1, row.reactions.size)
        assertEquals("❤️", row.reactions.first().emoji)
        assertEquals("m0", row.replyTo?.id)
    }

    @Test
    fun `message envelope tolerates null and missing message`() {
        val withNull = PulseJson.decodeFromString(
            SocketMessageEnvelope.serializer(),
            """{"type":"poll:voted","message":null,"recipientIds":["u2"]}""",
        )
        assertEquals("poll:voted", withNull.type)
        assertNull(withNull.message)

        val empty = PulseJson.decodeFromString(SocketMessageEnvelope.serializer(), "{}")
        assertEquals("", empty.type)
        assertNull(empty.message)
        // contracts.ts: conversationId?: string — optional on the wire → null here.
        assertNull(empty.conversationId)
    }

    @Test
    fun `read event decodes and tolerates missing lastReadAt`() {
        val read = PulseJson.decodeFromString(
            ReadEventPayload.serializer(),
            """{"conversationId":"c1","userId":"u2","lastReadAt":"2026-02-14T10:01:00.000Z"}""",
        )
        assertEquals("c1", read.conversationId)
        assertEquals("u2", read.userId)
        assertEquals("2026-02-14T10:01:00.000Z", read.lastReadAt)

        assertEquals(null, PulseJson.decodeFromString(ReadEventPayload.serializer(), "{}").lastReadAt)
    }

    @Test
    fun `conversation updated decodes with and without the summary`() {
        val full = PulseJson.decodeFromString(
            ConversationUpdatedPayload.serializer(),
            """
            {"conversationId":"c1","conversation":{
               "id":"c1","isGroup":true,"name":"Pulse Crew",
               "members":[{"id":"u1","name":"Alice"}],
               "unreadCount":3,"myStreak":{"count":3}
            }}
            """.trimIndent(),
        )
        assertEquals("c1", full.conversationId)
        assertEquals("Pulse Crew", full.conversation?.name)
        assertEquals(true, full.conversation?.isGroup)
        assertEquals(3, full.conversation?.unreadCount)

        val bare = PulseJson.decodeFromString(ConversationUpdatedPayload.serializer(), """{"conversationId":"c1"}""")
        assertNull(bare.conversation)
    }

    @Test
    fun `voice payloads decode`() {
        val roster = PulseJson.decodeFromString(
            VoiceRosterPayload.serializer(),
            """
            {"conversationId":"c1","roster":[
                {"userId":"u1","name":"Alice","username":null,"color":"emerald","joinedAt":1730000000},
                {"userId":"u2","name":"Bob"}
            ],"unknown":1}
            """.trimIndent(),
        )
        assertEquals(2, roster.roster.size)
        assertEquals("Alice", roster.roster[0].name)
        assertNull(roster.roster[0].username)
        assertEquals(1730000000L, roster.roster[0].joinedAt)
        assertNull(roster.roster[1].joinedAt)

        val ptt = PulseJson.decodeFromString(
            VoicePttPayload.serializer(),
            """{"conversationId":"c1","userId":"u1","active":true}""",
        )
        assertTrue(ptt.active)

        val chunk = PulseJson.decodeFromString(
            VoiceChunkPayload.serializer(),
            """{"conversationId":"c1","userId":"u1","seq":7,"data":"QUJD"}""",
        )
        assertEquals(7L, chunk.seq)
        assertEquals("QUJD", chunk.data)

        val transcript = PulseJson.decodeFromString(
            VoiceTranscriptPayload.serializer(),
            """{"conversationId":"c1","userId":"u1","text":"hello there"}""",
        )
        assertEquals("hello there", transcript.text)
    }

    @Test
    fun `stage and space state decode with arbitrary state objects`() {
        val stage = PulseJson.decodeFromString(
            StageStatePayload.serializer(),
            """{"conversationId":"c1","state":{"host":"u1","speakers":["u1","u2"],"handQueue":[],"muted":["u3"]},"future":true}""",
        )
        assertEquals("c1", stage.conversationId)
        assertTrue((stage.state as? kotlinx.serialization.json.JsonObject)?.containsKey("host") == true)

        val ended = PulseJson.decodeFromString(StageEndedPayload.serializer(), """{"conversationId":"c1"}""")
        assertEquals("c1", ended.conversationId)

        val space = PulseJson.decodeFromString(
            SpaceStatePayload.serializer(),
            """{"conversationId":"c1","state":{"players":{"u1":{"x":1.5,"y":-2}}}}""",
        )
        assertTrue(space.state is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun `call family decodes from one tolerant shape`() {
        val offer = PulseJson.decodeFromString(
            CallSignalDto.serializer(),
            """
            {"callId":"call-1","conversationId":"c1","from":"u1","to":"u2","kind":"video",
             "sdp":"v=0 o=- ...","callerName":"Alice","callerColor":"emerald","callerAvatar":null,
             "futureField":{"nested":true}}
            """.trimIndent(),
        )
        assertEquals("call-1", offer.callId)
        assertEquals("video", offer.kind)
        assertEquals("v=0 o=- ...", offer.sdp)
        assertEquals("Alice", offer.callerName)
        assertEquals("emerald", offer.callerColor)
        assertNull(offer.callerAvatar)
        assertNull(offer.reason)

        val ice = PulseJson.decodeFromString(
            CallSignalDto.serializer(),
            """{"callId":"call-1","conversationId":"c1","from":"u2","to":"u1","kind":"voice",
                "candidate":{"candidate":"candidate:1 1 UDP ...","sdpMid":"0","sdpMLineIndex":0}}""",
        )
        assertEquals("candidate:1 1 UDP ...", ice.candidate?.candidate)
        assertEquals("0", ice.candidate?.sdpMid)
        assertEquals(0, ice.candidate?.sdpMLineIndex)

        val reject = PulseJson.decodeFromString(
            CallSignalDto.serializer(),
            """{"callId":"call-1","conversationId":"c1","from":"u2","to":"u1","kind":"voice","reason":"busy"}""",
        )
        assertEquals("busy", reject.reason)

        val cancel = PulseJson.decodeFromString(
            CallSignalDto.serializer(),
            """{"callId":"call-1","conversationId":"c1","from":"u1","to":"u2","kind":"voice","reason":"timeout"}""",
        )
        assertEquals("timeout", cancel.reason)

        val hangup = PulseJson.decodeFromString(
            CallSignalDto.serializer(),
            """{"callId":"call-1","conversationId":"c1","from":"u1","to":"u2","kind":"voice","durationMs":62000}""",
        )
        assertEquals(62000L, hangup.durationMs)
        assertNull(hangup.sdp)
    }

    @Test
    fun `join payload round-trips through PulseJson`() {
        val encoded = PulseJson.encodeToString(JoinPayload.serializer(), JoinPayload(userId = "u-9"))
        val decoded = PulseJson.decodeFromString(JoinPayload.serializer(), encoded)
        assertEquals("u-9", decoded.userId)
        assertEquals(JsonPrimitive("u-9"), JsonPrimitive(decoded.userId))
    }
}

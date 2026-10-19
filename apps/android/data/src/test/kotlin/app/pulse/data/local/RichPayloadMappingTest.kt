package app.pulse.data.local

import app.pulse.data.repository.toDomain
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave7Logic
import app.pulse.protocol.objectPayloadText
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RichPayloadMappingTest {
    @Test fun `object and string carriers retain their references through the wire domain and cache`() {
        val payloads = mapOf(
            "game" to """{"matchId":"match"}""",
            "tournament" to """{"tournamentId":"season"}""",
            "red_packet" to """{"packetId":"packet","total":100,"count":2,"note":"hello"}""",
            "text" to """{"effect":"sparkles"}""",
            "sticker" to """{"emoji":"heart","pack":"classic"}""",
        )
        for ((kind, raw) in payloads) {
            for (wirePayload in listOf(JsonPrimitive(raw), PulseJson.parseToJsonElement(raw))) {
                val wire = ChatMessageDto(id = "carrier", conversationId = "room", senderId = "owner",
                    content = "hello", kind = kind, payload = wirePayload, createdAt = "2026-10-20T12:00:00.000Z")
                val domain = wire.toDomain()
                val cached = MessageEntity.from(domain).toDomain()
                assertEquals(raw, domain.payload)
                assertEquals(raw, cached.payload)
                when (kind) {
                    "game" -> assertEquals("match", PulseWave7Logic.gamePayload(cached.payload)?.matchId)
                    "tournament" -> assertEquals("season", PulseWave7Logic.tournamentPayload(cached.payload)?.tournamentId)
                    "red_packet" -> assertEquals("packet", PulseWave7Logic.redPacketPayload(cached.payload)?.packetId)
                    "text" -> assertEquals("sparkles", PulseWave7Logic.effectPayload(cached.payload)?.effect)
                }
            }
        }
    }

    @Test fun `invalid scalar array empty and absent payloads are not usable carriers`() {
        for (raw in listOf("null", "true", "42", "[]", "{}", "\"broken\"", "\"[]\"")) {
            assertNull(PulseJson.parseToJsonElement(raw).objectPayloadText())
        }
        assertNull(null.objectPayloadText())
    }
}

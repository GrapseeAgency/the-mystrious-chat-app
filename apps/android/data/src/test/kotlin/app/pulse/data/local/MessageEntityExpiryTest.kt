package app.pulse.data.local

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Section-5 "Old Android expiry loss": the raw wire deadline rides the frozen
 * payload so a poisoned (malformed sentinel) stored value rebuilds from the
 * authoritative string instead of dropping the row into the expired bucket. */
class MessageEntityExpiryTest {
    private fun message(expiry: Long?, raw: String?) = Message(
        id = "m1", conversationId = "room", authorId = "peer", authorName = "Real Name",
        kind = Message.Kind.TEXT, body = "body", createdAt = "2026-10-24T00:00:00Z",
        expiresAtEpochMs = expiry, expiresAtRaw = raw,
    )

    @Test fun `raw wire string heals a malformed stored deadline on read`() {
        val poisoned = MessageEntity.from(message(Long.MIN_VALUE, "2026-10-24T00:00:05Z")).toDomain()
        assertEquals(java.time.Instant.parse("2026-10-24T00:00:05Z").toEpochMilli(), poisoned.expiresAtEpochMs)
        assertEquals("2026-10-24T00:00:05Z", poisoned.expiresAtRaw)
    }

    @Test fun `malformed raw without a parseable value keeps the unavailable sentinel`() {
        val kept = MessageEntity.from(message(Long.MIN_VALUE, "not-a-date")).toDomain()
        assertEquals(Long.MIN_VALUE, kept.expiresAtEpochMs)
        assertEquals("not-a-date", kept.expiresAtRaw)
    }

    @Test fun `absent expiry stays permanent and a parseable column is untouched`() {
        assertEquals(null, MessageEntity.from(message(null, null)).toDomain().expiresAtEpochMs)
        assertEquals(null, MessageEntity.from(message(null, "2026-10-24T00:00:05Z")).toDomain().expiresAtEpochMs)
        assertEquals(5000L, MessageEntity.from(message(5000L, null)).toDomain().expiresAtEpochMs)
    }

    @Test fun `raw key round-trips through the frozen payload without leaking into the wire payload`() {
        val entity = MessageEntity.from(message(5000L, "2026-10-24T00:00:05Z"))
        val payload = entity.payloadJson.orEmpty()
        assertTrue("pulse:expiresAtRaw" in payload)
        assertTrue("pulse:expiresAtMs" in payload)
        val round = entity.toDomain()
        assertEquals(5000L, round.expiresAtEpochMs)
        assertEquals("2026-10-24T00:00:05Z", round.expiresAtRaw)
        // The clean wire payload never carries the reserved pulse:* keys.
        assertFalse("pulse:" in (round.payload ?: ""))
    }
}

package app.pulse.data.repository

import app.pulse.data.local.MessageEntity
import app.pulse.domain.sync.messageExpiryEpochMs
import app.pulse.domain.sync.withExpiredSummaryRemoved
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.UnreadExpirationDto
import org.junit.Assert.*
import org.junit.Test

class MessageExpiryMappingTest {
    private fun row(expiry: String?) = ChatMessageDto("message", "room", "peer", "private", createdAt = "2026-10-24T00:00:00Z", expiresAt = expiry)
    @Test fun onlyAbsentExpiryIsPermanentAcrossWireAndCacheRoundTrip() {
        for ((raw, expected) in listOf(null to null, "" to Long.MIN_VALUE, "bad-date" to Long.MIN_VALUE,
            "1970-01-01T00:00:00Z" to 0L, "1969-12-31T23:59:59Z" to -1000L,
            "2090-01-01T00:00:00Z" to 3_786_912_000_000L)) {
            assertEquals(expected, messageExpiryEpochMs(raw))
            val restored = MessageEntity.from(row(raw).toDomain()).toDomain()
            assertEquals(expected, restored.expiresAtEpochMs)
        }
    }
    @Test fun corruptedInternalDeadlineCannotBecomePermanent() {
        val cache = MessageEntity.from(row(null).toDomain()).copy(payloadJson = """{"pulse:expiresAtMs":"bad"}""")
        assertEquals(Long.MIN_VALUE, cache.toDomain().expiresAtEpochMs)
    }
    @Test fun malformedInboxDeadlineAndBucketsRetireWithoutClearingUnrelatedUnread() {
        val snapshot = ConversationSummaryDto("room", lastMessage = row("bad"), unreadCount = 3,
            unreadExpirations = listOf(UnreadExpirationDto("bad", 2))).toDomain("me")
        val projected = snapshot.withExpiredSummaryRemoved(1000)
        assertNull(projected.lastMessagePreview)
        assertEquals(1, projected.unreadCount)
        assertTrue(projected.unreadExpirations.isEmpty())
        assertEquals(projected, projected.withExpiredSummaryRemoved(2000))
    }
}

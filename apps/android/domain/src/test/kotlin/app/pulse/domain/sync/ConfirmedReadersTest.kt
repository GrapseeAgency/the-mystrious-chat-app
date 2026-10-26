package app.pulse.domain.sync

import app.pulse.domain.model.ConversationMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfirmedReadersTest {
    @Test fun `hidden missing self and malformed timestamps cannot assert Seen`() {
        val reader = ConversationMember("peer", "Peer", lastReadAt = 2000)
        assertEquals(listOf(reader), confirmedReaders(listOf(reader), "me", 1000))
        for (stamp in listOf(null, 0L, -1L)) {
            assertTrue(confirmedReaders(listOf(reader.copy(lastReadAt = stamp)), "me", 1000).isEmpty())
        }
        assertTrue(confirmedReaders(emptyList(), "me", 1000).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "peer", 1000).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), null, 1000).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "me", 0).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "me", -1).isEmpty())
        assertEquals(1, confirmedReaders(listOf(reader, reader), "me", 1000).size)
    }
}

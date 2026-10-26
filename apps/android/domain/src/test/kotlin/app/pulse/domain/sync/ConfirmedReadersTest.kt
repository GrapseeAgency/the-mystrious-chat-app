package app.pulse.domain.sync

import app.pulse.domain.model.ConversationMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfirmedReadersTest {
    @Test fun `hidden missing self and malformed timestamps cannot assert Seen`() {
        val reader = ConversationMember("peer", "Peer", lastReadAt = 2000, readMembershipId = "membership")
        assertEquals(listOf(reader), confirmedReaders(listOf(reader), "me", 1000, listOf("membership")))
        for (stamp in listOf(null, 0L, -1L)) {
            assertTrue(confirmedReaders(listOf(reader.copy(lastReadAt = stamp)), "me", 1000, listOf("membership")).isEmpty())
        }
        assertTrue(confirmedReaders(emptyList(), "me", 1000, listOf("membership")).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "peer", 1000, listOf("membership")).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), null, 1000, listOf("membership")).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "me", 0, listOf("membership")).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "me", -1, listOf("membership")).isEmpty())
        assertTrue(confirmedReaders(listOf(reader), "me", 1000).isEmpty(), "A timestamp without exact proof cannot assert Seen")
        assertTrue(confirmedReaders(listOf(reader.copy(readMembershipId = "rejoined")), "me", 1000, listOf("membership")).isEmpty())
        assertEquals(1, confirmedReaders(listOf(reader, reader), "me", 1000, listOf("membership")).size)
    }
}

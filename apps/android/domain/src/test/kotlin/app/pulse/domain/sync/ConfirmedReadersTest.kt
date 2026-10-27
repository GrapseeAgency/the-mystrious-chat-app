package app.pulse.domain.sync

import app.pulse.domain.model.ConversationMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfirmedReadersTest {
    @Test fun `incoming Info includes the viewing recipient and excludes the sender instead`() {
        val sender = ConversationMember("sender", "Sender", lastReadAt = 2000, readMembershipId = "sender-member")
        val viewer = ConversationMember("me", "Me", lastReadAt = 2000, readMembershipId = "my-member")
        val hidden = ConversationMember("peer", "Peer", lastReadAt = null, readMembershipId = "peer-member")
        val rows = listOf(sender, viewer, hidden, viewer)
        val proof = listOf("sender-member", "my-member", "peer-member")
        val incoming = messageInfoReaders(rows, "me", "sender", 1000, proof)
        assertEquals(listOf(viewer), incoming.seenBy)
        assertEquals(listOf(hidden), incoming.unconfirmed)
        val outgoing = messageInfoReaders(rows, "me", "me", 1000, proof)
        assertEquals(listOf(sender), outgoing.seenBy)
        assertEquals(listOf(hidden), outgoing.unconfirmed)
        assertTrue(messageInfoReaders(rows, null, "sender", 1000, proof).seenBy.isEmpty())
        assertTrue(messageInfoReaders(emptyList(), "me", "sender", 1000, proof).seenBy.isEmpty())
        val withoutEvidence = messageInfoReaders(rows, "me", "sender", 1000, emptyList())
        assertTrue(withoutEvidence.seenBy.isEmpty())
        assertEquals(listOf(viewer, hidden), withoutEvidence.unconfirmed)
    }

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

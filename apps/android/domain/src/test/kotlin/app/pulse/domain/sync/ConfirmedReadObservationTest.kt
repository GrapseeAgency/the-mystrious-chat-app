package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfirmedReadObservationTest {
    private val current = Message("message", "room", "author", "Author", Message.Kind.TEXT,
        "Current edited body", "2026-10-26T00:00:00Z", editedAt = "2026-10-26T00:01:00Z",
        readByMembershipIds = listOf("other-member"))

    @Test fun `confirmed observation changes only exact membership proof`() {
        val updated = confirmedReadObservation(current, "room", "message", "original.member")!!
        assertEquals(listOf("other-member", "member"), updated.readByMembershipIds)
        assertEquals(current, updated.copy(readByMembershipIds = current.readByMembershipIds))
        assertNull(confirmedReadObservation(updated, "room", "message", "original.member"))
        val deleted = current.copy(deletedAt = "2026-10-26T00:02:00Z")
        assertEquals(deleted.deletedAt, confirmedReadObservation(deleted, "room", "message", "original.member")!!.deletedAt)
    }

    @Test fun `proof cannot manufacture or cross a message identity`() {
        assertNull(confirmedReadObservation(null, "room", "message", "original.member"))
        assertNull(confirmedReadObservation(current, "room", "another-message", "original.member"))
        assertNull(confirmedReadObservation(current, "other-room", "message", "original.member"))
        assertNull(confirmedReadObservation(current.copy(id = "local_pending"), "room", "local_pending", "original.member"))
        for (bad in listOf("", "member", ".member", "original.", "a.b.c")) {
            assertNull(confirmedReadObservation(current, "room", "message", bad))
        }
    }
}

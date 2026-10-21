package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ViewOnceAccessTest {
    private fun row() = Message(id = "photo", conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.IMAGE, body = "caption", createdAt = "2026-10-21T00:00:00Z", imagePath = "private.jpg", viewOnce = true)

    @Test fun `only an unopened live received photo may request a grant`() {
        assertTrue(canRequestViewOnce(row(), "me", 1000))
        assertFalse(canRequestViewOnce(row(), "", 1000))
        for (bad in listOf(row().copy(deletedAt = "now"), row().copy(viewedAt = 1), row().copy(expiresAtEpochMs = 1000),
            row().copy(imagePath = null), row().copy(viewOnce = false), row().copy(authorId = "me"), row().copy(id = "local_pending"))) {
            assertFalse(canRequestViewOnce(bad, "me", 1000))
        }
    }

    @Test fun `cached burn or replay is not a new grant and mismatched or unavailable replies fail closed`() {
        val requested = row()
        val granted = requested.copy(viewedAt = 1000)
        assertTrue(acceptsViewOnceGrant(true, requested, granted, "me", 1000))
        assertFalse(acceptsViewOnceGrant(false, requested, granted, "me", 1000))
        for (bad in listOf(granted.copy(id = "other"), granted.copy(conversationId = "other"), granted.copy(deletedAt = "now"),
            granted.copy(expiresAtEpochMs = 1000), granted.copy(imagePath = null), granted.copy(viewedAt = null))) {
            assertFalse(acceptsViewOnceGrant(true, requested, bad, "me", 1000))
        }
    }
}

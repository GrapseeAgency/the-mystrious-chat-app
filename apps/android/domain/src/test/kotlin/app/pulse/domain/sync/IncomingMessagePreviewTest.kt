package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IncomingMessagePreviewTest {
    private fun row() = Message("message", "room", "peer", "Real Name", Message.Kind.TEXT, "secret", "2026-10-23T00:00:00Z")
    @Test fun `anonymous and single use previews never reveal account name or caption`() {
        val preview = row().copy(anon = true, anonAlias = "Quiet Raven", viewOnce = true).incomingPreview("me", "room", 0)
        assertEquals("Quiet Raven", preview?.senderName)
        assertEquals("View-once message", preview?.body)
        assertEquals("Anonymous", row().copy(anon = true).incomingPreview("me", "room", 0)?.senderName)
    }
    @Test fun `server machine flag is incoming even for represented viewer but metadata alone is not`() {
        val own = row().copy(authorId = "me", payload = """{"webhook":true,"webhookName":"Builds"}""")
        assertNull(own.incomingPreview("me", "room", 0))
        val machine = own.copy(viaAutomation = true)
        assertFalse(machine.isOutgoingFor("me"))
        assertEquals("Webhook · Builds", machine.incomingPreview("me", "room", 0)?.senderName)
        assertEquals("me", machine.authorId) // ownership is not rewritten
    }
    @Test fun `expired deleted foreign thread and optimistic rows are silent`() {
        assertNull(row().incomingPreview(null, "room", 0))
        assertNull(row().incomingPreview("me", "foreign", 0))
        for (message in listOf(row().copy(expiresAtEpochMs = 1000), row().copy(deletedAt = "now"),
            row().copy(threadRootId = "root"), row().copy(id = "local_pending"))) {
            assertNull(message.incomingPreview("me", "room", 1000))
        }
    }
}

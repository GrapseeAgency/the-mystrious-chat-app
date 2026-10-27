package app.pulse.domain.sync

import app.pulse.domain.model.MentionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionVisibilityTest {
    @Test fun `expired malformed and unaddressable previews cannot remain visible or navigate`() {
        val row = MentionItem("message", "room", expiresAt = "1970-01-01T00:00:01Z")
        assertTrue(row.isVisible(999)); assertFalse(row.isVisible(1000))
        assertFalse(row.copy(expiresAt = "invalid").isVisible(0))
        assertTrue(row.copy(expiresAt = null).isVisible(1000))
        assertFalse(row.copy(messageId = " ").isVisible(0))
        assertFalse(row.copy(conversationId = "").isVisible(0))
    }
}

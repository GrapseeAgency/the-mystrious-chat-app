package app.pulse.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10-a - the notification quick-reply payload kernel, pinned as a pure JVM
 * test: parse (trim / blank rejection / server length cap), the queued verdict
 * (temp `local_` rows are outbox entries, not deliveries), and the shared
 * notification id math with PulseMessagingService.
 */
class PulseReplyReceiverTest {

    @Test
    fun `parse trims and keeps the conversation`() {
        val parsed = PulseReplyPayload.parse(conversationId = " c1 ", text = "  hello pulse  ")
        assertEquals(PulseReplyPayload.Parsed("c1", "hello pulse"), parsed)
    }

    @Test
    fun `parse rejects blank reply text and blank conversation`() {
        assertNull(PulseReplyPayload.parse("c1", "   "))
        assertNull(PulseReplyPayload.parse("c1", null))
        assertNull(PulseReplyPayload.parse("  ", "hi"))
        assertNull(PulseReplyPayload.parse(null, "hi"))
    }

    @Test
    fun `parse caps the reply at the server message cap`() {
        val capped = PulseReplyPayload.parse("c1", "x".repeat(2500))
        assertEquals(2000, capped?.text?.length)
        assertEquals(PulseReplyPayload.MAX_CHARS, capped?.text?.length)
    }

    @Test
    fun `isQueued is true only for the temp local_ prefix`() {
        assertTrue(PulseReplyPayload.isQueued("local_abc123"))
        assertFalse(PulseReplyPayload.isQueued("m9"))
        assertFalse(PulseReplyPayload.isQueued(""))
    }

    @Test
    fun `notification id matches the messaging service scheme`() {
        // Same math PulseMessagingService uses:
        // (conversationId).hashCode().and(0x3FFFFFFF) - reply/cancel must
        // address the SAME notification the service posted.
        val conv = "conv-42"
        assertEquals(conv.hashCode().and(0x3FFFFFFF), PulseReplyPayload.notificationId(conv))
        assertTrue(PulseReplyPayload.notificationId(conv) >= 0)
    }
}

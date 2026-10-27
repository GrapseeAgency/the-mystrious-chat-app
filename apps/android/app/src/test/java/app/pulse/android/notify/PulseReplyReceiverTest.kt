package app.pulse.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10-a - the notification quick-reply payload kernel, pinned as a pure JVM
 * test: parse (trim / blank rejection / lossless oversized input), the queued verdict
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
    fun `parse never sends a truncated reply or splits a surrogate pair`() {
        val body = "x".repeat(1999) + "😀" + "y".repeat(500)
        val parsed = PulseReplyPayload.parse("c1", body)
        assertEquals(body, parsed?.text)
        assertTrue(parsed!!.text.length > PulseReplyPayload.MAX_CHARS)
        assertEquals(app.pulse.domain.usecase.SendMessageUseCase.MAX_LENGTH, PulseReplyPayload.MAX_CHARS)
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
    @Test
    fun `direct reply allows RemoteInput fill in without an immutable flag`() {
        val immutable = android.app.PendingIntent.FLAG_IMMUTABLE
        val mutable = android.app.PendingIntent.FLAG_MUTABLE
        for (sdk in listOf(24, 30, 31, 34, 35)) {
            val flags = PulseReplyPayload.replyPendingIntentFlags(sdk)
            assertEquals(0, flags and immutable)
            assertEquals(if (sdk >= 31) mutable else 0, flags and mutable)
        }
    }
}

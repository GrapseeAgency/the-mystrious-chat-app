package app.pulse.android.mirror

import app.pulse.domain.model.Message
import org.junit.Assert.*
import org.junit.Test

class ThreadPresentationTest {
    private fun row(id: String, parent: String? = null) = Message(id = id, conversationId = "room", authorId = "real-id",
        authorName = "Real name", kind = Message.Kind.TEXT, body = "private caption", createdAt = "2026-10-21T00:00:00Z", threadRootId = parent)

    @Test fun sensitiveThreadBodiesAndAnonymousNamesAreNotRenderedAsPlainText() {
        val message = row("reply", "root")
        assertEquals("View-once message", threadMessageLabel(message.copy(viewOnce = true)))
        assertEquals("Message deleted", threadMessageLabel(message.copy(deletedAt = "now", viewOnce = true)))
        assertEquals("Autumn Fox", threadAuthorLabel(message.copy(anon = true, anonAlias = "Autumn Fox")))
        assertEquals("Anonymous", threadAuthorLabel(message.copy(anon = true, anonAlias = "")))
        assertEquals("Real name", threadAuthorLabel(message))
    }

    @Test fun exactReplyUsesParentOffsetAndDoesNotFallbackToFirstRow() {
        val root = row("root")
        val replies = listOf(row("a", "root"), row("target", "root"))
        assertEquals(2, threadJumpIndex(replies, root, "target", 1000))
        assertNull(threadJumpIndex(replies, root, "missing", 1000))
        for (bad in listOf(replies[1].copy(deletedAt = "now"), replies[1].copy(expiresAtEpochMs = 1000),
            replies[1].copy(conversationId = "other"), replies[1].copy(threadRootId = "other"))) {
            assertNull(threadJumpIndex(listOf(bad), root, "target", 1000))
        }
        assertEquals(1, threadJumpIndex(listOf(replies[1].copy(expiresAtEpochMs = 1001)), root, "target", 1000))
    }
    @Test fun unavailableParentCannotAnchorAnOtherwiseLiveReply() {
        val root = row("root")
        val replies = listOf(row("target", "root"))
        assertNull(threadJumpIndex(replies, root.copy(deletedAt = "now"), "target", 1000))
        assertNull(threadJumpIndex(replies, root.copy(expiresAtEpochMs = 1000), "target", 1000))
    }

}

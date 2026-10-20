package app.pulse.android.mirror

import app.pulse.domain.model.Message
import org.junit.Assert.*
import org.junit.Test

class RoomTimelineTest {
    private fun row(id: String, topic: String? = null, parent: String? = null) = Message(
        id = id, conversationId = "room", authorId = "sender", authorName = "Sender",
        kind = Message.Kind.TEXT, body = "text", createdAt = "2026-10-19T10:00:00Z",
        topicId = topic, threadRootId = parent,
    )
    @Test fun topicOnlyShowsItsRootMessages() {
        val rows = listOf(row("a", "selected"), row("b", "other"), row("c", "selected", "a"))
        assertEquals(listOf("a"), visibleRoomMessages(rows, "selected").map { it.id })
        assertEquals(listOf("a", "b"), visibleRoomMessages(rows, null).map { it.id })
    }
    @Test fun hiddenRepliesAdvanceTheRawHistoryCursor() {
        val page = listOf(row("a", parent = "root"), row("b", parent = "root"))
        assertTrue(visibleRoomMessages(page, null).isEmpty())
        assertEquals("a", oldestRoomMessage(page)?.id)
    }
    @Test fun timestampTiesUseIdAndPendingRowsNeverAnchorHistory() {
        val rows = listOf(row("c"), row("local_pending"), row("b"), row("a"))
        assertEquals("a", oldestRoomMessage(rows)?.id)
        assertEquals(listOf("a", "b", "c", "local_pending"), visibleRoomMessages(rows, null).map { it.id })
    }
    @Test fun expiredMessagesDisappearWithoutNeedingAnotherServerEvent() {
        val message = row("expiry").copy(expiresAtEpochMs = 1000L)
        assertEquals(1, visibleRoomMessages(listOf(message), null, now = 999L).size)
        assertTrue(visibleRoomMessages(listOf(message), null, now = 1000L).isEmpty())
    }

}

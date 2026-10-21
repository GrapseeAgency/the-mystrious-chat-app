package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryThreadSafetyTest {
    private fun row(id: String) = Message(id = id, conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.TEXT, body = id, createdAt = "2026-10-21T00:00:00Z")

    @Test fun `raw cursor crosses hidden replies and topic rows with tied timestamps`() {
        val cursor = MessageHistoryCursor.oldest(listOf(row("c")))!!
        val hidden = listOf(row("b").copy(threadRootId = "root"), row("a").copy(topicId = "other"), row("local_pending"))
        val next = MessageHistoryCursor.oldest(hidden)!!
        assertEquals("a", next.id)
        assertEquals("2026-10-21T00:00:00Z", next.createdAt)
        assertTrue(next.precedes(cursor))
        assertFalse(next.precedes(next))
        assertFalse(cursor.precedes(next))
        assertNull(MessageHistoryCursor.oldest(listOf(row("local_pending"))))
    }

    @Test fun `cursor compares actual instants rather than timestamp spellings`() {
        val utc = MessageHistoryCursor("a", "2026-10-21T00:00:00Z")
        val offset = MessageHistoryCursor("b", "2026-10-21T06:00:00+06:00")
        assertTrue(utc.precedes(offset))
        assertFalse(offset.precedes(utc))
    }

    @Test fun `thread root rejects deleted expired nested foreign or wrong id rows`() {
        val root = row("root")
        assertTrue(threadRootAvailable(root, "room", "root", 1000))
        for (bad in listOf(null, root.copy(deletedAt = "now"), root.copy(expiresAtEpochMs = 1000),
            root.copy(threadRootId = "another"), root.copy(conversationId = "other"), root.copy(id = "different"))) {
            assertFalse(threadRootAvailable(bad, "room", "root", 1000))
        }
        assertTrue(threadRootAvailable(root.copy(expiresAtEpochMs = 1001), "room", "root", 1000))
    }

    @Test fun `explicit refusal closes cached thread but offline and server errors are not revocation`() {
        for (code in listOf(403, 404, 410)) assertTrue(threadAccessDenied(code))
        for (code in listOf(null, 429, 500, 503)) assertFalse(threadAccessDenied(code))
    }
    @Test fun `live deletion filtering cannot erase the raw page boundary`() {
        val raw = listOf(row("a"), row("b"))
        val page = MessageHistoryPage.fromResponse(raw, merged = emptyList(), hasMore = true)
        assertTrue(page.messages.isEmpty())
        assertTrue(page.hasMore)
        assertEquals(MessageHistoryCursor("a", raw.first().createdAt), page.nextCursor)
        assertTrue(page.nextCursor!!.precedes(MessageHistoryCursor("c", raw.first().createdAt)))
        val exhausted = MessageHistoryPage.fromResponse(emptyList(), emptyList(), hasMore = false)
        assertNull(exhausted.nextCursor)
        assertFalse(exhausted.hasMore)
    }

    @Test fun `raw cursor remains paired with its timestamp when cache rows are replaced`() {
        val raw = listOf(row("a"), row("b"))
        val merged = listOf(row("b").copy(deletedAt = "now"))
        val page = MessageHistoryPage.fromResponse(raw, merged, hasMore = true)
        assertEquals(merged, page.messages)
        assertEquals("a", page.nextCursor?.id)
        assertEquals(raw.first().createdAt, page.nextCursor?.createdAt)
        // A cache purge needs no ID lookup: the next request still has the timestamp.
        assertTrue(MessageHistoryCursor("0", raw.first().createdAt).precedes(page.nextCursor!!))
    }

}

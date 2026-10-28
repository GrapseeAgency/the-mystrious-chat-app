package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryThreadSafetyTest {
    private fun row(id: String) = Message(id = id, conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.TEXT, body = id, createdAt = "2026-10-21T00:00:00Z")

    @Test fun `thread read boundary excludes prefetched rejected expired and foreign rows`() {
        val root = row("root")
        val reply = row("shown").copy(threadRootId = "root")
        val candidates = listOf(reply, reply.copy(id = "prefetched"), reply.copy(id = "local_pending"),
            reply.copy(id = "deleted", deletedAt = "now"), reply.copy(id = "expired", expiresAtEpochMs = 1000),
            reply.copy(id = "foreign", conversationId = "other"), reply.copy(id = "wrong-parent", threadRootId = "elsewhere"))
        val visible = candidates.map { it.id }.toSet() - "prefetched"
        assertEquals("shown", visibleThreadReadBoundary(root, candidates, visible, 1000))
        assertEquals("root", visibleThreadReadBoundary(root, candidates, setOf("root"), 1000))
        assertNull(visibleThreadReadBoundary(root, candidates, emptySet(), 1000))
        assertNull(visibleThreadReadBoundary(root.copy(deletedAt = "now"), candidates, visible, 1000))
        assertNull(visibleThreadReadBoundary(root.copy(expiresAtEpochMs = 1000), candidates, visible, 1000))
    }

    @Test fun `offline jump consults cache only after transport failure`() = kotlinx.coroutines.test.runTest {
        val root = row("root")
        val reply = row("reply").copy(threadRootId = "root")
        var failure: RuntimeException? = null
        var cacheReads = 0
        var saved: Pair<Message, Message>? = root to reply
        val type = app.pulse.domain.repository.PulseRepository::class.java
        val repo = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "threadWindow" -> {
                    assertEquals(listOf("room", "root", "reply"), args!!.take(3))
                    failure?.let { throw it }; MessageWindow(listOf(reply), root, "root", null, null, false, false)
                }
                "cachedThreadTarget" -> {
                    assertEquals(listOf("room", "root", "reply"), args!!.take(3))
                    cacheReads++; saved
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as app.pulse.domain.repository.PulseRepository
        assertEquals(root to listOf(reply), resolveThreadJump(repo, "room", "root", "reply"))
        assertEquals(0, cacheReads)
        failure = app.pulse.domain.model.PulseApiException("NETWORK", "offline")
        assertEquals(root to listOf(reply), resolveThreadJump(repo, "room", "root", "reply"))
        assertEquals(1, cacheReads)
        saved = null
        val absent = runCatching { resolveThreadJump(repo, "room", "root", "reply") }.exceptionOrNull()
        assertSame(failure, absent)
        assertEquals(2, cacheReads)
        for (error in listOf(app.pulse.domain.model.PulseApiException("FORBIDDEN", "refused", 403),
                app.pulse.domain.model.PulseApiException("NETWORK", "superseded", 409),
                kotlinx.coroutines.CancellationException("left room"))) {
            failure = error
            assertSame(error, runCatching { resolveThreadJump(repo, "room", "root", "reply") }.exceptionOrNull())
        }
        assertEquals(2, cacheReads)
    }

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

    @Test fun `reply jump carries the exact target and never falls back to index zero`() {
        val root = row("root")
        val first = row("a").copy(threadRootId = "root")
        val target = row("target").copy(threadRootId = "root")
        assertEquals(1, threadReplyTargetIndex(listOf(first, target), root, target.id, 1000))
        assertEquals(0, threadReplyTargetIndex(listOf(target), root, target.id, 1000))
        assertNull(threadReplyTargetIndex(listOf(first), root, target.id, 1000))
        for (bad in listOf(target.copy(deletedAt = "now"), target.copy(expiresAtEpochMs = 1000),
            target.copy(conversationId = "other"), target.copy(threadRootId = "other"))) {
            assertNull(threadReplyTargetIndex(listOf(first, bad), root, target.id, 1000))
        }
        for (badRoot in listOf(root.copy(deletedAt = "now"), root.copy(expiresAtEpochMs = 1000),
            root.copy(threadRootId = "nested"))) {
            assertNull(threadReplyTargetIndex(listOf(target), badRoot, target.id, 1000))
        }
        assertEquals(0, threadReplyTargetIndex(listOf(target.copy(expiresAtEpochMs = 1001)), root, target.id, 1000))
    }

    @Test fun `pending echo cannot become a thread root before acknowledgement`() {
        val pending = row("local_pending")
        assertFalse(threadRootAvailable(pending, "room", pending.id, 1000))
        assertNull(threadReplyTargetIndex(listOf(row("reply").copy(threadRootId = pending.id)), pending, "reply", 1000))
    }


    @Test fun `older thread validation cannot grant or deny after a newer request`() {
        val fence = ThreadValidationFence()
        val old = fence.begin("root")
        val current = fence.begin("root")
        val other = fence.begin("other")
        assertFalse(fence.isCurrent("root", old))
        fence.finish("root", old)
        assertTrue(fence.isCurrent("root", current))
        assertTrue(fence.isCurrent("other", other))
        fence.finish("root", current)
        assertFalse(fence.isCurrent("root", old))
        assertFalse(fence.isCurrent("root", current))
        assertTrue(fence.isCurrent("other", other))
        assertEquals("thread-access-denied:v1|root", threadDenialKey("root"))
    }

    @Test fun `offline target requires live exact root and reply without refusal`() {
        val root = row("root")
        val reply = row("reply").copy(threadRootId = "root")
        assertEquals(root to reply, cachedThreadTarget(root, reply, "room", "root", "reply", false, 1000))
        assertNull(cachedThreadTarget(root, reply, "room", "root", "reply", true, 1000))
        for (invalid in listOf(null, root.copy(deletedAt = "now"), root.copy(expiresAtEpochMs = 1000),
            root.copy(threadRootId = "nested"), root.copy(conversationId = "other"), root.copy(id = "local_root"))) {
            assertNull(cachedThreadTarget(invalid, reply, "room", "root", "reply", false, 1000))
        }
        for (invalid in listOf(null, reply.copy(deletedAt = "now"), reply.copy(expiresAtEpochMs = 1000),
            reply.copy(threadRootId = "another"), reply.copy(conversationId = "other"))) {
            assertNull(cachedThreadTarget(root, invalid, "room", "root", "reply", false, 1000))
        }
    }

    @Test fun `offline navigation never turns HTTP refusal or cancellation into cache permission`() {
        assertTrue(mayUseOfflineThread(app.pulse.domain.model.PulseApiException("NETWORK", "offline")))
        assertFalse(mayUseOfflineThread(kotlinx.coroutines.CancellationException()))
        for (status in listOf(401, 403, 404, 409, 410, 429, 500)) {
            assertFalse(mayUseOfflineThread(app.pulse.domain.model.PulseApiException("NETWORK", "failure", status)))
        }
        assertFalse(mayUseOfflineThread(IllegalStateException("decode failed")))
    }
}

package app.pulse.domain.sync

import app.pulse.domain.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageExpiryTest {
    private fun row(id: String, expires: Long? = null) = Message(
        id = id, conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.TEXT, body = id, createdAt = "2026-10-20T00:00:00Z", expiresAtEpochMs = expires,
    )

    @Test fun `offline rows disappear at deadline without database or socket emissions`() = runTest {
        val source = MutableStateFlow(listOf(row("expired", 0), row("short", 1000), row("forever")))
        val emissions = mutableListOf<List<String>>()
        val reader = backgroundScope.launch {
            source.withMessageExpiry { testScheduler.currentTime }.collect { emissions += it.map(Message::id) }
        }
        runCurrent()
        assertEquals(listOf("short", "forever"), emissions.last())
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("forever"), emissions.last())
        assertEquals(2, emissions.size)
        reader.cancelAndJoin()
    }

    @Test fun `new envelopes replace the expiry timer and a cancelled observer stays silent`() = runTest {
        val source = MutableStateFlow(listOf(row("old", 10000)))
        val emissions = mutableListOf<List<String>>()
        val reader = backgroundScope.launch {
            source.withMessageExpiry { testScheduler.currentTime }.collect { emissions += it.map(Message::id) }
        }
        runCurrent()
        source.value = listOf(row("new", 100))
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertTrue(emissions.last().isEmpty())
        reader.cancelAndJoin()
        val count = emissions.size
        source.value = listOf(row("after-cancellation", 200))
        advanceTimeBy(20000); runCurrent()
        assertEquals(count, emissions.size)
    }

    @Test fun `a clock jump is rechecked even when the original TTL was far away`() = runTest {
        var wallClock = 0L
        val source = MutableStateFlow(listOf(row("long", 100000)))
        var visible = emptyList<Message>()
        val reader = backgroundScope.launch { source.withMessageExpiry { wallClock }.collect { visible = it } }
        runCurrent()
        assertEquals(1, visible.size)
        wallClock = 100000
        advanceTimeBy(30000); runCurrent()
        assertTrue(visible.isEmpty())
        reader.cancelAndJoin()
    }
    @Test fun `reply-only changes and offline expiry update counts with an unchanged river`() = runTest {
        val root = row("root", 2000)
        val reply = row("reply", 1000).copy(threadRootId = "root")
        val source = MutableStateFlow(listOf(root))
        var counts = emptyMap<String, Int>()
        val reader = backgroundScope.launch {
            source.withMessageExpiry { testScheduler.currentTime }
                .map { cachedThreadReplyCounts(it, testScheduler.currentTime) }
                .collect { counts = it }
        }
        runCurrent()
        assertTrue(counts.isEmpty())
        source.value = listOf(root, reply)
        runCurrent()
        assertEquals(mapOf("root" to 1), counts)
        source.value = listOf(root, reply.copy(deletedAt = "now"))
        runCurrent()
        assertTrue(counts.isEmpty())
        source.value = listOf(root, reply)
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertTrue(counts.isEmpty()) // no database write at this deadline
        source.value = listOf(root, reply.copy(expiresAtEpochMs = null))
        runCurrent()
        assertEquals(mapOf("root" to 1), counts)
        advanceTimeBy(1000); runCurrent()
        assertTrue(counts.isEmpty()) // expired root cannot retain a reply chip
        reader.cancelAndJoin()
    }

    @Test fun `reply counts exclude foreign roots nested roots tombstones and expired rows`() {
        val root = row("root")
        val reply = row("reply").copy(threadRootId = "root")
        val bad = listOf(reply.copy(id = "foreign", conversationId = "other"),
            reply.copy(id = "deleted", deletedAt = "now"), reply.copy(id = "expired", expiresAtEpochMs = 10),
            reply.copy(id = "orphan", threadRootId = "missing"), reply.copy(id = "nested", threadRootId = "reply"))
        assertEquals(mapOf("root" to 1), cachedThreadReplyCounts(listOf(root, reply) + bad, now = 10))
        assertTrue(cachedThreadReplyCounts(listOf(root.copy(deletedAt = "now"), reply), now = 10).isEmpty())
    }

}

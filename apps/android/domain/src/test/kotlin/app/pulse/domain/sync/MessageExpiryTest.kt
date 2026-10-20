package app.pulse.domain.sync

import app.pulse.domain.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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
}

package app.pulse.domain.sync

import app.pulse.domain.model.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MissedMessageArrivalsTest {
    private fun row(id: String) = Message(id, "room", "peer", "Peer", Message.Kind.TEXT, id, "2026-10-23T00:00:00Z")
    private fun tracker() = MissedMessageArrivals().apply {
        configure("room", "me", null)
        observe(listOf(row("initial")), 0)
        setReadingTail(false)
    }
    @Test fun `history initial load edits and rows from another day never create arrivals`() {
        val tracker = tracker()
        tracker.observe(listOf(row("old"), row("initial"), row("from-search")), 0)
        assertEquals(0, tracker.count)
        tracker.observe(listOf(row("initial").copy(body = "edited")), 0)
        assertEquals(0, tracker.count)
    }
    @Test fun `realtime arrival counts once before or after its cache emission`() {
        for (cacheFirst in listOf(true, false)) {
            val tracker = tracker()
            val arrival = row("new")
            if (cacheFirst) tracker.observe(listOf(arrival), 0)
            tracker.receive(arrival, 0)
            if (!cacheFirst) {
                tracker.observe(listOf(row("initial")), 0) // stale emission must not erase a not-yet-observed arrival
                assertEquals(1, tracker.count)
                tracker.observe(listOf(arrival), 0)
            }
            tracker.receive(arrival, 0)
            assertEquals(1, tracker.count)
        }
    }
    @Test fun `deleting one arrival does not clear unrelated pending IDs and expiry prunes offline`() {
        val tracker = tracker()
        val a = row("a")
        val b = row("b").copy(expiresAtEpochMs = 1000)
        tracker.receive(a, 0); tracker.receive(b, 0)
        tracker.observe(listOf(a, b), 0)
        tracker.observe(listOf(a.copy(deletedAt = "now"), b), 0)
        assertEquals(1, tracker.count)
        tracker.observe(emptyList(), 1000)
        assertEquals(0, tracker.count)
    }
    @Test fun `outgoing echoes threads other topics and stale initial replays do not count`() {
        val tracker = tracker()
        for (row in listOf(row("initial"), row("own").copy(authorId = "me"), row("local_pending"),
            row("thread").copy(threadRootId = "root"), row("foreign").copy(conversationId = "other"))) tracker.receive(row, 0)
        assertEquals(0, tracker.count)
        tracker.receive(row("machine").copy(authorId = "me", viaAutomation = true), 0)
        assertEquals(1, tracker.count)
        tracker.configure("room", "me", "topic")
        tracker.receive(row("other-topic"), 0)
        assertEquals(0, tracker.count)
        tracker.receive(row("topic-arrival").copy(topicId = "topic"), 0)
        assertEquals(1, tracker.count)
        tracker.configure("room", "another-account", "topic")
        assertEquals(0, tracker.count)
    }
    @Test fun `only reaching the tail clears pending and its replay stays cleared`() {
        val tracker = tracker()
        tracker.receive(row("new"), 0)
        assertEquals(1, tracker.count)
        tracker.setReadingTail(true)
        assertEquals(0, tracker.count)
        tracker.setReadingTail(false)
        tracker.receive(row("new"), 0)
        assertEquals(0, tracker.count)
    }
}

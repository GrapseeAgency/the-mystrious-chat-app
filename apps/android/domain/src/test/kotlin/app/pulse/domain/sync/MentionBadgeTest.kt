package app.pulse.domain.sync

import app.pulse.domain.model.FeedPage
import app.pulse.domain.model.MentionItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MentionBadgeTest {
    @Test fun `empty scan windows do not truncate old mentions and badge memory is capped`() = runTest {
        var calls = 0
        val badge = mentionBadgeSnapshot(now = { 0L }) { cursor ->
            val page = cursor?.toInt() ?: 0
            calls++
            val rows = if (page < 120) emptyList() else (0..<50).map { MentionItem("$page-$it", "r") }
            FeedPage(rows, (page + 1).toString(), true)
        }
        assertEquals(100, badge.count); assertEquals(122, calls); assertNull(badge.expiresAt)
    }
    @Test fun `expiry invalid and duplicate identities do not inflate badge`() = runTest {
        val badge = mentionBadgeSnapshot(now = { 0L }) {
            FeedPage(listOf(MentionItem("m", "r", expiresAt = "1970-01-01T00:00:01Z"),
                MentionItem("m", "r", expiresAt = "1970-01-01T00:00:01Z"),
                MentionItem("bad", "r", expiresAt = "invalid")), null, false)
        }
        assertEquals(1, badge.count); assertEquals(1000L, badge.expiresAt)
    }
    @Test fun `live changes discard an in-flight stale result and expiry invalidates without polling`() = runTest {
        val hints = MutableSharedFlow<Unit>()
        val gate = CompletableDeferred<Unit>()
        val values = mutableListOf<Int?>()
        var calls = 0
        val job = backgroundScope.launch {
            mentionBadgeFlow(hints, now = { testScheduler.currentTime }) {
                calls++
                if (calls == 1) gate.await()
                FeedPage(listOf(MentionItem("m", "r", expiresAt = "1970-01-01T00:00:01Z")), null, false)
            }.collect { values.add(it) }
        }
        runCurrent(); hints.emit(Unit); gate.complete(Unit); runCurrent()
        assertEquals(2, calls); assertEquals(listOf(null, 1), values)
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, values.last()); assertEquals(3, calls)
        job.cancel(); runCurrent()
        hints.emit(Unit); assertEquals(3, calls)
    }
    @Test fun `transport failure stays unknown instead of false zero`() = runTest {
        val values = mutableListOf<Int?>()
        val job = backgroundScope.launch {
            mentionBadgeFlow(MutableSharedFlow()) { error("offline") }.collect { values.add(it) }
        }
        runCurrent(); assertEquals(listOf<Int?>(null), values); job.cancel()
    }
    @Test fun `multi step cursor cycles stop without retaining every visited page`() = runTest {
        var calls = 0
        val error = runCatching {
            mentionBadgeSnapshot(now = { 0L }) { cursor ->
                check(++calls <= 20) { "cycle guard failed" }
                FeedPage(emptyList(), when (cursor) { null -> "a"; "a" -> "b"; "b" -> "c"; else -> "a" }, true)
            }
        }.exceptionOrNull()
        assertNotNull(error); assertTrue(calls <= 7)
    }

}

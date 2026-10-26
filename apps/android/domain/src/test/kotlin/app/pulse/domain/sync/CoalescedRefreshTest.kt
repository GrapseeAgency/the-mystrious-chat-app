package app.pulse.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoalescedRefreshTest {
    @Test fun `continuous read hints finish the in flight count and coalesce to one trailing pass`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = Job()
        var calls = 0
        val counts = mutableListOf<Int>()
        val refresh = CoalescedRefresh(CoroutineScope(coroutineContext + owner)) {
            calls++
            if (calls == 1) { firstStarted.complete(Unit); release.await() }
            currentCoroutineContext().ensureActive()
            counts.add(calls)
        }
        try {
            refresh.request()
            firstStarted.await()
            repeat(100) { refresh.request() }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(counts.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), counts)
            refresh.cancel()
            refresh.request()
            runCurrent()
            assertEquals(2, calls)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun `owner disposal cancels the current request and drops queued hints`() = runTest {
        val owner = Job()
        var started = 0
        var cancelled = 0
        val refresh = CoalescedRefresh(CoroutineScope(coroutineContext + owner)) {
            started++
            try { awaitCancellation() } finally { cancelled++ }
        }
        refresh.request(); runCurrent()
        refresh.request()
        owner.cancelAndJoin()
        refresh.request(); runCurrent()
        assertEquals(1, started)
        assertEquals(1, cancelled)
    }

    @Test fun `failed counts stay unknown and do not disable future hints`() = runTest {
        val owner = Job()
        var calls = 0
        val refresh = CoalescedRefresh(CoroutineScope(coroutineContext + owner)) {
            calls++
            if (calls == 1) error("Offline")
        }
        try {
            refresh.request(); runCurrent()
            refresh.request(); runCurrent()
            assertEquals(2, calls)
        } finally { owner.cancelAndJoin() }
    }
}

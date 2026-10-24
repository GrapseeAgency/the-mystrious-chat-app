package app.pulse.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWriteQueueTest {
    @Test fun `new local choices stage while HTTP stays strictly ordered`() = runTest {
        val queue = SettingsWriteQueue(backgroundScope)
        val local = mutableListOf<Int>(); val sent = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val first = queue.submit({ local += 1; 1 }, { sent += it; release.await(); Result.success(Unit) })
        val second = queue.submit({ local += 2; 2 }, { sent += it; Result.success(Unit) })
        runCurrent()
        assertEquals(listOf(1, 2), local); assertEquals(listOf(1), sent)
        release.complete(Unit)
        assertTrue(first.await().isSuccess); assertTrue(second.await().isSuccess)
        assertEquals(listOf(1, 2), sent)
    }
    @Test fun `cancelling a UI waiter cannot let the next request overtake the first`() = runTest {
        val queue = SettingsWriteQueue(backgroundScope)
        val release = CompletableDeferred<Unit>(); val sent = mutableListOf<Int>()
        val first = queue.submit({ 1 }, { sent += it; release.await(); Result.success(Unit) })
        val waiter = backgroundScope.async { first.await() }
        val second = queue.submit({ 2 }, { sent += it; Result.success(Unit) })
        runCurrent(); waiter.cancel(); runCurrent()
        assertFalse(first.isCancelled); assertEquals(listOf(1), sent)
        release.complete(Unit); second.await()
        assertEquals(listOf(1, 2), sent)
    }
    @Test fun `failed local staging never dispatches and does not poison following work`() = runTest {
        val queue = SettingsWriteQueue(backgroundScope)
        val sent = mutableListOf<Int>()
        val failed = queue.submit<Int>({ error("storage full") }, { sent += it; Result.success(Unit) })
        val next = queue.submit({ 2 }, { sent += it; Result.success(Unit) })
        assertTrue(failed.await().isFailure); assertTrue(next.await().isSuccess)
        assertEquals(listOf(2), sent)
    }
}

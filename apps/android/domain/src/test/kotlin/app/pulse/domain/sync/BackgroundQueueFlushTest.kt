package app.pulse.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundQueueFlushTest {
    @Test fun `read replay and alert reconciliation do not wait for a slow upload`() = runTest {
        val releaseUpload = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val work = async {
            flushBackgroundQueues(messages = { releaseUpload.await(); true }, readState = {
                calls += "replay"
                calls += "canonical inbox"
                calls += "exact alerts"
                true
            })
        }
        runCurrent()
        assertEquals(listOf("replay", "canonical inbox", "exact alerts"), calls)
        assertFalse(work.isCompleted, "The worker must still own its upload")
        releaseUpload.complete(Unit)
        assertTrue(work.await())
    }

    @Test fun `slow read state does not postpone messages or falsely complete the worker`() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        var sent = false
        val work = async {
            flushBackgroundQueues(messages = { sent = true; true }, readState = { releaseRead.await(); true })
        }
        runCurrent()
        assertTrue(sent)
        assertFalse(work.isCompleted)
        releaseRead.complete(Unit)
        assertTrue(work.await())
    }

    @Test fun `ordinary failure in either lane does not cancel the other durable delivery`() = runTest {
        for (failMessages in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            var finished = false
            val failed: suspend () -> Boolean = { error("offline") }
            val independent: suspend () -> Boolean = { release.await(); finished = true; true }
            val work = async {
                flushBackgroundQueues(
                    messages = if (failMessages) failed else independent,
                    readState = if (failMessages) independent else failed,
                )
            }
            runCurrent()
            assertFalse(work.isCompleted)
            release.complete(Unit)
            assertFalse(work.await())
            assertTrue(finished)
        }
    }

    @Test fun `a retired read owner promptly cancels the sibling upload`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val work = async {
            flushBackgroundQueues(messages = {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }, readState = {
                started.await()
                throw kotlinx.coroutines.CancellationException("owner retired")
            })
        }
        runCurrent()
        assertTrue(runCatching { work.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(cancelled)
    }

    @Test fun `background expiration cancels both lanes without detached work`() = runTest {
        var cancelled = 0
        val blocked: suspend () -> Boolean = {
            try { awaitCancellation() } finally { cancelled++ }
        }
        val work = async { flushBackgroundQueues(blocked, blocked) }
        runCurrent()
        work.cancelAndJoin()
        assertEquals(2, cancelled)
    }
}

package app.pulse.core.media

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * R43 - StreamGuard close-on-cancel: a provider read blocked BELOW the
 * bounded reader (inside InputStream.read, past every ensureActive
 * checkpoint) must unblock the moment its job is cancelled or the deadline
 * fires, because the guard closes the stream.
 */
class StreamGuardTest {

    /** A hung cloud provider: read() blocks until close() lands. */
    private class BlockedProvider : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            closed.await()
            throw java.io.IOException("stream closed by the guard")
        }
        override fun close() { closed.countDown() }
    }

    @Test
    fun `cancellation closes the stream and unblocks a read blocked below the bounded reader`() = runBlocking {
        val provider = BlockedProvider()
        val job = launch(Dispatchers.Default) {
            StreamGuard.read(deadlineMillis = 0, open = { provider }) { it.read() }
        }
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS), "the provider read never started")
        // Cancel while the read is blocked inside the provider. Without the
        // guard's close this join hangs forever (or until the test timeout).
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(provider.closed.await(5, TimeUnit.SECONDS), "the guard never closed the blocked stream")
    }

    @Test
    fun `the deadline closes a hung provider even without user cancellation`() = runBlocking {
        val provider = BlockedProvider()
        val started = launch(Dispatchers.Default) {
            try {
                StreamGuard.read(deadlineMillis = 150, open = { provider }) { it.read() }
            } catch (expected: Exception) {
                // The watchdog closed the stream; the blocked read failed -
                // either the read's own IOException or the scope's
                // cancellation unwinding. Both mean "unblocked, failed fast".
            }
        }
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS), "the provider read never started")
        withTimeout(5_000) { started.join() }
        assertTrue(provider.closed.await(5, TimeUnit.SECONDS), "the watchdog never closed the hung stream")
    }

    @Test
    fun `a normal read returns the value and closes the stream exactly once`() = runBlocking {
        var closes = 0
        val stream = object : InputStream() {
            override fun read(): Int = 'A'.code
            override fun close() { closes++ }
        }
        val value = StreamGuard.read(deadlineMillis = 60_000, open = { stream }) { input ->
            input.read().toChar().toString()
        }
        assertEquals("A", value)
        assertEquals(1, closes, "the guard must close the stream exactly once on the normal path")
    }

    @Test
    fun `a blocked read surfaces as failure after cancellation, not as silent success`() = runBlocking {
        val provider = BlockedProvider()
        var observed: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                observed = runCatching { StreamGuard.read(deadlineMillis = 0, open = { provider }) { it.read() } }.exceptionOrNull()
            } catch (cancelled: CancellationException) {
                observed = cancelled
            }
        }
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertNotNull(observed, "a cancelled blocked read must fail, never return data")
    }
}

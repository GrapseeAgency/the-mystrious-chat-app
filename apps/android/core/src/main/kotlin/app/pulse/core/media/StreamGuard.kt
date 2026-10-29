package app.pulse.core.media

import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * R43 - the blocked-read half of "Cancellation cannot be assumed to interrupt
 * a provider/disk operation already blocked below the bounded reader".
 *
 * A cloud/third-party document provider (Drive, a hung ContentProvider) can
 * block INSIDE InputStream.read() long past every ensureActive() checkpoint
 * in the bounded reader above it. Coroutine cancellation never interrupts a
 * blocked native read - but closing the stream DOES: the blocked read fails
 * with "stream closed" on the spot and the regular cancellation path takes
 * over.
 *
 * [read] bridges the blocking read into a cancellable suspension (public API
 * only): the read runs on an IO child, and a cancellation of the calling
 * scope fires close-on-cancel through [suspendCancellableCoroutine]'s
 * [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation]. A
 * deadline watchdog closes hung providers even when nobody cancels.
 *
 * Honest limits (documented, not hidden): a provider read that ignores close
 * stays wedged until the OS gives up - the coroutineScope then waits with it
 * (the same platform limit the reader had before, minus the silent-hang
 * case close actually fixes). Device acceptance keeps the final word.
 */
object StreamGuard {

    /** Providers are rare events; one shared daemon thread serves every guard. */
    private val watchdog: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pulse-stream-guard").apply { isDaemon = true }
        }
    }

    /**
     * @param deadlineMillis a hung provider is closed at the deadline even if
     * the user never cancels, so a stuck read can never hang the flow forever.
     * Zero disables the watchdog (tests pin tiny deadlines instead).
     */
    suspend fun <R> read(
        deadlineMillis: Long = 120_000,
        open: () -> InputStream,
        block: (InputStream) -> R,
    ): R {
        val stream = open()
        val lock = Any()
        var closed = false
        fun closeOnce() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            runCatching { stream.close() }
        }
        var deadline: ScheduledFuture<*>? = null
        if (deadlineMillis > 0) {
            deadline = watchdog.schedule({ closeOnce() }, deadlineMillis, TimeUnit.MILLISECONDS)
        }
        try {
            return coroutineScope {
                val reader = async(Dispatchers.IO, start = CoroutineStart.ATOMIC) { block(stream) }
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { closeOnce() }
                    reader.invokeOnCompletion { if (cont.isActive) cont.resume(Unit) { _ -> } }
                }
                reader.await()
            }
        } finally {
            deadline?.cancel(false)
            closeOnce()
        }
    }
}

package app.pulse.core.media

import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

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
 * [read] registers close-on-cancel on the calling job plus a deadline
 * watchdog: whichever fires first (user cancellation, completion, or the
 * deadline) closes the stream exactly once. The caller must NOT wrap the
 * stream in use{} - the guard owns closing.
 *
 * Honest limits (documented, not hidden): a provider read that ignores both
 * cancellation and close stays blocked until the OS gives up - the deadline
 * bounds the wait for the CALLER'S coroutine (the block throws once close
 * lands), but a pathological provider below the stream can outlive it. The
 * device-acceptance item stays: physical providers get the final word.
 */
object StreamGuard {

    /** Providers are rare events; one shared daemon thread serves every guard. */
    private val watchdog: java.util.concurrent.ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { thread ->
            thread.isDaemon = true
            thread.name = "pulse-stream-guard"
            thread
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
        val job = coroutineContext[Job]
        val stream = open()
        val lock = Object()
        var closed = false
        fun closeOnce() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            runCatching { stream.close() }
        }
        val completion = job?.invokeOnCompletion(onCancelling = true) { closeOnce() }
        var deadline: ScheduledFuture<*>? = null
        try {
            if (deadlineMillis > 0) {
                deadline = watchdog.schedule({ closeOnce() }, deadlineMillis, TimeUnit.MILLISECONDS)
            }
            return block(stream)
        } finally {
            deadline?.cancel(false)
            completion?.dispose()
            closeOnce()
        }
    }
}

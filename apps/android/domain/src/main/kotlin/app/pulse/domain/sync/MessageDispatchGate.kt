package app.pulse.domain.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by immediate sends and recovery. Persistence happens BEFORE this gate. */
class MessageDispatchGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val mutex = Mutex()
    private val retryNotBefore = AtomicLong(Long.MIN_VALUE)

    fun remainingDelayMillis(): Long {
        val deadline = retryNotBefore.get()
        return if (deadline == Long.MIN_VALUE) 0L else (deadline - nowMillis()).coerceAtLeast(0L)
    }

    fun holdRetries(seconds: Int?) {
        val deadline = nowMillis() + (seconds ?: 60).coerceIn(1, 86_400).toLong() * 1_000L
        var previous = retryNotBefore.get()
        while (deadline > previous && !retryNotBefore.compareAndSet(previous, deadline)) {
            previous = retryNotBefore.get()
        }
    }

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    /** null means keep the already-persisted intent queued, not fail or wait on UI. */
    suspend fun <T : Any> tryImmediate(isHead: suspend () -> Boolean, send: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            if (remainingDelayMillis() > 0 || !isHead()) null else send()
        } finally { mutex.unlock() }
    }
}

package app.pulse.domain.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by immediate sends and recovery. Persistence happens BEFORE this gate. */
class MessageDispatchGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val mutex = Mutex()
    private val retryNotBefore = AtomicLong(Long.MIN_VALUE)

    private val roomDeadlines = mutableMapOf<String, Long>()

    fun remainingDelayMillis(conversationId: String? = null): Long {
        val deadline = retryNotBefore.get()
        val global = if (deadline == Long.MIN_VALUE) 0L else (deadline - nowMillis()).coerceAtLeast(0L)
        val room = synchronized(roomDeadlines) { conversationId?.let { roomDeadlines[it] }?.let { (it - nowMillis()).coerceAtLeast(0L) } ?: 0L }
        return maxOf(global, room)
    }

    fun blockedConversations(): Set<String> = synchronized(roomDeadlines) {
        val now = nowMillis()
        roomDeadlines.entries.removeAll { it.value <= now }
        roomDeadlines.keys.toSet()
    }

    fun holdRetries(seconds: Int?, conversationId: String? = null) {
        val deadline = nowMillis() + (seconds ?: 60).coerceIn(1, 86_400).toLong() * 1_000L
        if (conversationId != null) {
            synchronized(roomDeadlines) {
                roomDeadlines[conversationId] = maxOf(roomDeadlines[conversationId] ?: Long.MIN_VALUE, deadline)
            }
            return
        }
        var previous = retryNotBefore.get()
        while (deadline > previous && !retryNotBefore.compareAndSet(previous, deadline)) {
            previous = retryNotBefore.get()
        }
    }

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    /** null means keep the already-persisted intent queued, not fail or wait on UI. */
    suspend fun <T : Any> tryImmediate(isHead: suspend () -> Boolean, conversationId: String? = null, send: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            if (remainingDelayMillis(conversationId) > 0 || !isHead()) null else send()
        } finally { mutex.unlock() }
    }
}

package app.pulse.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Neither media uploads nor read-state outages may starve the other lane.
 * Both remain owned by the worker; cancellation retires both, ordinary failures
 * keep the worker pending without cancelling an independent durable delivery. */
suspend fun flushBackgroundQueues(
    messages: suspend () -> Boolean,
    readState: suspend () -> Boolean,
): Boolean = coroutineScope {
    suspend fun settled(work: suspend () -> Boolean): Boolean = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    awaitAll(async { settled(messages) }, async { settled(readState) }).all { it }
}

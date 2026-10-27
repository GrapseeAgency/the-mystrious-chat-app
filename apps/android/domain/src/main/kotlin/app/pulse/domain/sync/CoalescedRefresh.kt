package app.pulse.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Hints cannot starve a canonical read by cancelling it. Keep at most one
 * trailing pass; the owning scope (or explicit disposal) cancels all work. */
class CoalescedRefresh(scope: CoroutineScope, refresh: suspend () -> Unit) {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (ignored in pending) {
            currentCoroutineContext().ensureActive()
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep last confirmed state; another hint remains a retry.
            }
        }
    }.also { it.invokeOnCompletion { pending.cancel() } }

    fun request() { pending.trySend(Unit) }
    fun cancel() { worker.cancel(); pending.cancel() }
}

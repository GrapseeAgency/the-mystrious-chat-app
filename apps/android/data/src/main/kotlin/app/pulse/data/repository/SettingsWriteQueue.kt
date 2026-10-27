package app.pulse.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/** Session-owned ordered lanes. Cancelling a UI waiter does not cancel an
 * already authorized write or let the next HTTP request overtake it. The
 * repository's session scope cancels both lanes when that session ends. */
internal class SettingsWriteQueue(private val scope: CoroutineScope) {
    private val lock = Any()
    private var preparedTail: Job? = null
    private var sentTail: Job? = null

    fun <T> submit(prepare: suspend () -> T, send: suspend (T) -> Result<Unit>): Deferred<Result<Unit>> = synchronized(lock) {
        val previousPreparation = preparedTail
        val previousSend = sentTail
        val prepared: Deferred<Result<T>> = scope.async(start = CoroutineStart.LAZY) {
            previousPreparation?.join()
            try { Result.success(prepare()) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
        }
        val sent: Deferred<Result<Unit>> = scope.async(start = CoroutineStart.LAZY) {
            previousSend?.join()
            try { send(prepared.await().getOrThrow()) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
        }
        preparedTail = prepared; sentTail = sent
        prepared.invokeOnCompletion { synchronized(lock) { if (preparedTail === prepared) preparedTail = null } }
        sent.invokeOnCompletion { synchronized(lock) { if (sentTail === sent) sentTail = null } }
        prepared.start(); sent.start()
        sent
    }
}

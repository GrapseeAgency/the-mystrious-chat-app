package app.pulse.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive

/** Room observation may terminate after its first good value. Keep buffer events
 * independent, resubscribe to Room, and retry snapshot-only failures as well.
 * Never turn a failed read into an authoritative null/clear or swallow cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> recoveringDraftObservation(
    buffers: Flow<*>, database: () -> Flow<*>,
    onFailure: (Throwable) -> Unit = {}, read: suspend () -> T,
): Flow<T> {
    val changes = flow {
        emit(Unit)
        emitAll(database().map { Unit })
    }.retryWhen { error, _ ->
        if (error is CancellationException) throw error
        onFailure(error)
        delay(1_000)
        true
    }
    // A snapshot query can fail even when Room's invalidation subscription is
    // healthy and emits nothing else. This finite-frequency wake recovers it.
    val recovery = flow {
        while (currentCoroutineContext().isActive) { delay(5_000); emit(Unit) }
    }
    return merge(buffers.map { Unit }, changes, recovery).transform {
        val value = try { read() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { onFailure(error); return@transform }
        emit(value)
    }
}

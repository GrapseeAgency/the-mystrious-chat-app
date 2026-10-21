package app.pulse.domain.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transport refusal is not a successful action. Cancellation must also win
 * when an adapter returns after its caller has left the surface. */
suspend fun <T> confirmedAction(request: suspend () -> Result<T>, apply: suspend (T) -> Unit): Result<T> {
    val result = request()
    currentCoroutineContext().ensureActive()
    if (result.isSuccess) apply(result.getOrThrow())
    return result
}

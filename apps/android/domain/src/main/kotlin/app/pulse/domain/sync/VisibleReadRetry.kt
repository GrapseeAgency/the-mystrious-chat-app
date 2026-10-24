package app.pulse.domain.sync

import app.pulse.domain.model.PulseApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry outages while attention remains valid, not a fixed number of times.
 * Access/validation refusals need a fresh surface or revalidation, not polling. */
fun visibleReadRetryDelay(error: Throwable?, failures: Int): Long? {
    if (error is CancellationException) return null
    val failure = error as? PulseApiException
    val status = failure?.status
    if (status != null && status in 400..499 && status !in setOf(408, 409, 425, 429)) return null
    if (status == null && failure?.kind in setOf("AUTH", "FORBIDDEN", "NOT_FOUND", "VALIDATION")) return null
    val backoff = minOf(60_000L, 1_500L * (1L shl (failures.coerceIn(1, 7) - 1)))
    return maxOf(backoff, (failure?.retryAfter ?: 0).coerceIn(0, 86_400).toLong() * 1_000L)
}

suspend fun acknowledgeVisibleRead(
    isCurrent: () -> Boolean,
    send: suspend () -> Result<Unit>,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return
    pause(600L)
    var failures = 0
    while (isCurrent()) {
        currentCoroutineContext().ensureActive()
        val result: Result<Unit> = try {
            send()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        currentCoroutineContext().ensureActive()
        if (result.isSuccess) return
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        failures = (failures + 1).coerceAtMost(7)
        val wait = visibleReadRetryDelay(error, failures) ?: return
        pause(wait)
    }
}

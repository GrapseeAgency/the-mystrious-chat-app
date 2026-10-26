package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retain the original server fence across transport failures and reconnects.
 * Reacquiring after a lost ACK could erase a newer manual-unread choice. */
class VisibleReadIntent(private val capture: suspend () -> Result<String>, private val write: suspend (String) -> Result<Unit>) {
    private var intent: String? = null
    suspend fun send(): Result<Unit> {
        val token = intent ?: capture().getOrElse { return Result.failure(it) }.also {
            currentCoroutineContext().ensureActive()
            intent = it
        }
        currentCoroutineContext().ensureActive()
        return write(token)
    }
}

fun visibleReadIntent(repository: PulseRepository, room: String, message: String): VisibleReadIntent {
    val account = repository.boundToCurrentAccount()
    return VisibleReadIntent({ account.captureReadIntent(room) }, { account.markReadThrough(room, message, it) })
}

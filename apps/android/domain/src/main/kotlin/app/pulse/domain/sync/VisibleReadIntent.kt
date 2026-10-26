package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retain the original server fence across transport failures and reconnects.
 * Reacquiring after a lost ACK could erase a newer manual-unread choice. */
class VisibleReadIntent(private val capture: suspend () -> Result<String>, private val write: suspend (String) -> Result<Unit>) {
    private var intent: String? = null
    private var acknowledged = false
    suspend fun send(): Result<Unit> {
        currentCoroutineContext().ensureActive()
        // Dwell already proved visibility. Navigation may cancel delivery, but
        // cannot interrupt the short LOCAL fence + journal write halfway through.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { persist() }
    }
    private suspend fun persist(): Result<Unit> {
        if (acknowledged) return Result.success(Unit)
        val token = intent ?: capture().getOrElse { return Result.failure(it) }.also {
            currentCoroutineContext().ensureActive()
            intent = it
        }
        currentCoroutineContext().ensureActive()
        return write(token).also { if (it.isSuccess) acknowledged = true }
    }
}

fun visibleReadIntent(repository: PulseRepository, room: String, message: String): VisibleReadIntent {
    val account = repository.boundToCurrentAccount()
    return VisibleReadIntent({ account.captureReadIntent(room) }, { account.markReadThrough(room, message, it) })
}

/** One mounted reading session owns ONE fence. Scanning another visible row
 * later must not acquire permission to erase another device's manual marker. */
class VisibleReadScope(repository: PulseRepository, private val room: String) {
    private val account = repository.boundToCurrentAccount()
    private val mutex = Mutex()
    private var intent: String? = null
    private suspend fun capture(): Result<String> = mutex.withLock {
        intent?.let { return@withLock Result.success(it) }
        account.captureReadIntent(room).also { result ->
            currentCoroutineContext().ensureActive()
            result.getOrNull()?.let { intent = it }
        }
    }
    fun message(id: String) = VisibleReadIntent({ capture() }, { account.markReadThrough(room, id, it) })
}

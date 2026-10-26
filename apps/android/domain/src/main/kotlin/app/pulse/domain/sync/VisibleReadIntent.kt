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
class VisibleReadScope(
    repository: PulseRepository,
    private val room: String,
    private val memberSnapshot: () -> app.pulse.domain.model.ConversationMember?,
) {
    private val account = repository.boundToCurrentAccount()
    private val mutex = Mutex()
    private var intent: String? = null
    private suspend fun capture(): Result<String> = mutex.withLock {
        intent?.let { return@withLock Result.success(it) }
        // Native surfaces supply their displayed membership synchronously at
        // qualification. A queued DAO lookup must not borrow a newer manual
        // intent while the original observation is waiting to be journaled.
        val member = memberSnapshot.invoke()?.takeIf { it.id == account.viewerId }
        val membership = member?.readMembershipId?.takeIf { it.isNotBlank() }
        val result = if (membership == null) Result.failure(IllegalStateException("Waiting for current room membership"))
            else Result.success(member?.readIntent ?: "offline.$membership")
        result.also {
            currentCoroutineContext().ensureActive()
            it.getOrNull()?.let { captured -> intent = captured }
        }
    }
    fun message(id: String) = VisibleReadIntent({ capture() }, { account.markReadThrough(room, id, it) })
}

/** A later real visit is not a retry: retain its distinct captured fence too.
 * The legacy prefix remains readable; ACKs still compare exact stored payloads. */
fun readObservationJournalKey(owner: String, message: String, intent: String): String =
    "read-journal:v1|$owner|${intent.substringAfterLast('.')}|$message|$intent"

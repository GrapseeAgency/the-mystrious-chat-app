package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*

/** Collect only inside an open, resumed Info surface. Hints coalesce without
 * cancelling the in-flight GET; a small fallback repairs lost relay delivery.
 * This reads one exact proof and the roster, never history or a read mutation. */
fun PulseRepository.messageReadStateRefreshes(room: String, message: String): Flow<Boolean> = flow {
    if (message.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX)) return@flow
    val repository = boundToCurrentAccount()
    emitAll(messageReadStateRefreshFlow(
        owner = repository::attachmentSessionKey,
        revisions = repository.observeReadStateRevision(room),
        refresh = { repository.refreshMessageReadState(room, message) },
        metadata = { repository.conversationDetail(room); Unit },
    ))
}

/** Typed operations keep suspension/cancellation tests on the real collector,
 * without reflection across Kotlin's inline Result suspend ABI. */
internal fun messageReadStateRefreshFlow(
    owner: () -> String?,
    revisions: Flow<Long>,
    refresh: suspend () -> Result<Boolean>,
    metadata: suspend () -> Unit,
): Flow<Boolean> = flow {
    val epoch = owner() ?: return@flow
    coroutineScope {
        // trySend on one explicit conflated mailbox cannot leave suspended
        // merge producers holding extra stale hints behind the pending pass.
        val pending = Channel<Unit>(Channel.CONFLATED)
        val hints = launch { revisions.collect { pending.trySend(Unit) } }
        val timer = launch {
            while (currentCoroutineContext().isActive) { delay(5_000); pending.trySend(Unit) }
        }
        try {
            for (ignored in pending) {
                currentCoroutineContext().ensureActive()
                if (owner() != epoch) throw CancellationException("Info owner retired")
                val available = refresh().getOrNull()
                currentCoroutineContext().ensureActive()
                if (owner() != epoch) throw CancellationException("Info owner retired")
                if (available != null) emit(available)
                if (available != false) metadata()
            }
        } finally {
            hints.cancel(); timer.cancel(); pending.cancel()
        }
    }
}

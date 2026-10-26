package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*

/** Collect only inside an open, resumed Info surface. Hints coalesce without
 * cancelling the in-flight GET; a small fallback repairs lost relay delivery.
 * This reads one exact proof and the roster, never history or a read mutation. */
@OptIn(ExperimentalCoroutinesApi::class)
fun PulseRepository.messageReadStateRefreshes(room: String, message: String): Flow<Boolean> = flow {
    if (message.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX)) return@flow
    val repository = boundToCurrentAccount()
    val owner = repository.attachmentSessionKey() ?: return@flow
    val ticks = flow {
        while (currentCoroutineContext().isActive) { delay(5_000); emit(Unit) }
    }
    merge(repository.observeReadStateRevision(room).map { Unit }, ticks).conflate().collect {
        if (repository.attachmentSessionKey() != owner) throw CancellationException("Info owner retired")
        val available = repository.refreshMessageReadState(room, message).getOrNull()
        if (repository.attachmentSessionKey() != owner) throw CancellationException("Info owner retired")
        if (available != null) emit(available)
        if (available != false) repository.conversationDetail(room)
    }
}

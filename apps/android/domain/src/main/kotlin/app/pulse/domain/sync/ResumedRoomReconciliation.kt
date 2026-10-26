package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Call from repeatOnLifecycle(RESUMED), never from the read/dwell writer.
 * Reconcile on each return and reconnect, not on every message/read hint.
 * Fetches canonical membership, cached message range and topic badges only. */
suspend fun PulseRepository.reconcileResumedRoom(room: String, topicId: String? = null) {
    val account = boundToCurrentAccount()
    val epoch = account.attachmentSessionKey() ?: return
    fun checkOwner() {
        if (account.attachmentSessionKey() != epoch) throw CancellationException("Room session retired")
    }
    reconcileResumedConnections(account.observeConnected()) {
        checkOwner()
        account.conversationDetail(room)
        currentCoroutineContext().ensureActive()
        checkOwner()
        account.refreshMessages(room, topicId)
        currentCoroutineContext().ensureActive()
        checkOwner()
        account.refreshTopics(room)
        currentCoroutineContext().ensureActive()
        checkOwner()
    }
}

/** A failed relay does not imply failed HTTP. Try once on resume even offline;
 * later disconnects cancel the obsolete pass and a rejoin starts a fresh one. */
internal suspend fun reconcileResumedConnections(connections: Flow<Boolean>, refresh: suspend () -> Unit) {
    var first = true
    connections.distinctUntilChanged().collectLatest { connected ->
        if (connected || first) {
            first = false
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Automatic reconciliation cannot discard the offline cache or
                // crash the room. A later resume/rejoin remains a retry boundary.
            }
        }
    }
}

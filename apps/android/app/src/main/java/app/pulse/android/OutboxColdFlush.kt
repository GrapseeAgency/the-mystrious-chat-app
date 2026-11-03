package app.pulse.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Section 14 item 5 - the cold outbox worker contract, extracted from
 * [OutboxWorker] so account-bound bootstrap, process recreation, mid-flight
 * account switches and fault injection are JVM-testable without WorkManager.
 * The Worker shell maps [Verdict] to WorkManager results; every semantic
 * (owner binding, token rechecks, read-projection invalidation, the
 * no-count-from-a-push replay rule) is preserved verbatim.
 */
internal class OutboxColdFlush(
    private val deps: Deps,
) {

    /** Narrow seams the Worker provides from its @EntryPoint graph. */
    internal interface Deps {
        suspend fun viewerId(): String?
        suspend fun serverBase(): String?
        fun recipientUserId(): String?
        fun readStateRoom(): String?
        /** Throws when the stored credential cannot be decrypted (fail-closed). */
        suspend fun loadToken(): String?
        fun cachedToken(): String?
        suspend fun applyPersistedBase()
        fun applyBase(base: String)
        /** Binds the process-scoped repository to this owner; false when another account holds it. */
        suspend fun startBackgroundIfIdle(owner: String): Boolean
        fun boundViewerId(): String?
        fun invalidateReadProjections(room: String)
        /** Remaining pending saved-intent rows (0 = fully drained). */
        suspend fun flushSavedIntents(): Result<Int>
        suspend fun flushComposerIntents(): Result<Int>
        /** Remaining pending outbox entries. */
        suspend fun flushOutbox(): app.pulse.domain.model.FlushReport
        suspend fun flushReadReceipts(): Result<Int>
        suspend fun refreshConversations(): Result<Unit>
        /** Reconciles message notifications; `stillOwner` re-checks binding on every step. */
        suspend fun reconcileNotifications(stillOwner: suspend () -> Boolean): Boolean
    }

    internal sealed interface Verdict {
        /** Queue drained; WorkManager maps this to Result.success. */
        data object Success : Verdict
        /** Still owed work (or a fault the queue must retry); maps to Result.retry. */
        data object Retry : Verdict
    }

    internal suspend fun run(): Verdict {
        val owner = deps.viewerId() ?: return Verdict.Success
        val recipient = deps.recipientUserId()
        if (recipient != null && recipient != owner) return Verdict.Success
        val base = deps.serverBase()
        if (!base.isNullOrBlank()) deps.applyBase(base) else deps.applyPersistedBase()
        val token = try {
            deps.loadToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Fail-closed credential store (unreadable key, invalidated key):
            // nothing can be delivered without it, but the queue still owes
            // its entries - retry, never success, never data loss.
            return Verdict.Retry
        } ?: return Verdict.Retry
        if (deps.viewerId() != owner || deps.cachedToken() != token) return Verdict.Retry
        if (!deps.startBackgroundIfIdle(owner)) return Verdict.Retry
        if (deps.viewerId() != owner || deps.boundViewerId() != owner || deps.cachedToken() != token) return Verdict.Retry
        deps.readStateRoom()?.takeIf { it.isNotBlank() && it.length <= 128 }?.let(deps::invalidateReadProjections)
        val complete = app.pulse.domain.sync.flushBackgroundQueues(
            messages = {
                coroutineScope {
                    val bookmarks = async { deps.flushSavedIntents().getOrThrow() == 0 }
                    val composer = async { deps.flushComposerIntents().getOrThrow() == 0 }
                    val messages = async { deps.flushOutbox().pending == 0 }
                    bookmarks.await() && messages.await() && composer.await()
                }
            },
            readState = {
                val pendingReads = deps.flushReadReceipts().getOrThrow()
                // No count from a push is trusted. Replay, then fetch the
                // canonical inbox and exact alert proofs while media sends run.
                deps.refreshConversations().getOrThrow()
                if (deps.viewerId() != owner || deps.boundViewerId() != owner || deps.cachedToken() != token) false
                else {
                    val notifications = deps.reconcileNotifications {
                        deps.viewerId() == owner && deps.cachedToken() == token && deps.boundViewerId() == owner
                    }
                    pendingReads == 0 && notifications
                }
            },
        )
        return if (complete) Verdict.Success else Verdict.Retry
    }
}

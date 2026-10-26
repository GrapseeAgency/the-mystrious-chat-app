package app.pulse.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import app.pulse.domain.repository.PulseRepository

/**
 * Wave 0 offline core - the persistent outbox flusher. The repository drains
 * on its own triggers (start / socket reconnect / foreground / self-heal);
 * this worker guarantees the queue ALSO drains when only the OS knows the
 * network is back (app process dead, WorkManager wakes us up).
 *
 * Dependencies come through an @EntryPoint over the repository - no
 * hilt-work artifact needed (plan §6: "no hilt-work").
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, OutboxWorkerEntryPoint::class.java)
        return try {
            val prefs = graph.prefs()
            val owner = prefs.viewerId.first() ?: return Result.success()
            val recipient = inputData.getString("recipientUserId")
            if (recipient != null && recipient != owner) return Result.success()
            val base = prefs.serverBase.first()
            if (!base.isNullOrBlank()) app.pulse.core.PulseEndpoints.applyBase(base)
            else graph.endpoints().applyPersisted()
            val token = graph.tokens().load() ?: return Result.retry()
            if (prefs.viewerId.first() != owner || graph.tokens().cached != token) return Result.retry()
            val repo = graph.pulseRepository()
            if (!repo.startBackgroundIfIdle(owner)) return Result.retry()
            val target = repo.boundToCurrentAccount()
            if (prefs.viewerId.first() != owner || target.viewerId != owner || graph.tokens().cached != token) return Result.retry()
            // A slow upload must not postpone the open thread's canonical refresh.
            inputData.getString("readStateRoom")?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?.let(target::invalidateReadProjections)
            val report = target.flushOutbox()
            val pendingReads = target.flushReadReceipts().getOrThrow()
            // No count from a push is trusted. Read the full current inbox after replay.
            target.refreshConversations().getOrThrow()
            if (prefs.viewerId.first() != owner || target.viewerId != owner || graph.tokens().cached != token) return Result.retry()
            val notifications = app.pulse.android.push.PulseMessageNotifications.reconcile(applicationContext, target) {
                prefs.viewerId.first() == owner && graph.tokens().cached == token && repo.viewerId == owner
            }
            if (report.pending > 0 || pendingReads > 0 || !notifications) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OutboxWorkerEntryPoint {
        fun pulseRepository(): PulseRepository
        fun prefs(): app.pulse.domain.repository.PulsePrefsStore
        fun tokens(): app.pulse.data.local.SessionTokenStore
        fun endpoints(): app.pulse.data.remote.ManifestEndpoints
    }

    companion object {
        private const val UNIQUE_NAME = "pulse-outbox-flush"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /**
         * Enqueued on empty → pending transitions. Append never cancels a
         * running delivery and cannot lose an observation enqueued as that
         * worker completes. Failed/cancelled chains are replaced safely.
         */
        fun enqueue(context: Context, recipientUserId: String? = null, readStateRoom: String? = null) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInputData(androidx.work.workDataOf("recipientUserId" to recipientUserId, "readStateRoom" to readStateRoom))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

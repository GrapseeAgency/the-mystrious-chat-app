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
import kotlinx.coroutines.async
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
            val repo = graph.pulseRepository()
            // Section 14 item 5 - the cold-worker contract lives in
            // OutboxColdFlush (JVM-testable, fault-injectable); this shell
            // only adapts the @EntryPoint graph and maps the verdict.
            val verdict = OutboxColdFlush(object : OutboxColdFlush.Deps {
                override suspend fun viewerId(): String? = prefs.viewerId.first()
                override suspend fun serverBase(): String? = prefs.serverBase.first()
                override fun recipientUserId(): String? = inputData.getString("recipientUserId")
                override fun readStateRoom(): String? = inputData.getString("readStateRoom")
                override suspend fun loadToken(): String? = graph.tokens().load()
                override fun cachedToken(): String? = graph.tokens().cached
                override suspend fun applyPersistedBase() { graph.endpoints().applyPersisted() }
                override fun applyBase(base: String) = app.pulse.core.PulseEndpoints.applyBase(base)
                override suspend fun startBackgroundIfIdle(owner: String): Boolean = repo.startBackgroundIfIdle(owner)
                override fun boundViewerId(): String? = repo.boundToCurrentAccount().viewerId
                override fun invalidateReadProjections(room: String) =
                    repo.boundToCurrentAccount().invalidateReadProjections(room)
                override suspend fun flushSavedIntents(): kotlin.Result<Int> = repo.boundToCurrentAccount().flushSavedIntents()
                override suspend fun flushComposerIntents(): kotlin.Result<Int> = repo.boundToCurrentAccount().flushComposerIntents()
                override suspend fun flushOutbox(): app.pulse.domain.model.FlushReport = repo.boundToCurrentAccount().flushOutbox()
                override suspend fun flushReadReceipts(): kotlin.Result<Int> = repo.boundToCurrentAccount().flushReadReceipts()
                override suspend fun refreshConversations(): kotlin.Result<Unit> = repo.boundToCurrentAccount().refreshConversations()
                override suspend fun reconcileNotifications(stillOwner: suspend () -> Boolean): Boolean =
                    app.pulse.android.push.PulseMessageNotifications.reconcile(
                        applicationContext, repo.boundToCurrentAccount(),
                    ) { stillOwner() }
            }).run()
            when (verdict) {
                OutboxColdFlush.Verdict.Success -> Result.success()
                OutboxColdFlush.Verdict.Retry -> Result.retry()
            }
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

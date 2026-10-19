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
            val base = prefs.serverBase.first()
            if (!base.isNullOrBlank()) app.pulse.core.PulseEndpoints.applyBase(base)
            else graph.endpoints().applyPersisted()
            val token = graph.tokens().load() ?: return Result.retry()
            if (prefs.viewerId.first() != owner || graph.tokens().cached != token) return Result.retry()
            val repo = graph.pulseRepository()
            repo.start(owner)
            val report = repo.flushOutbox()
            if (report.pending > 0) Result.retry() else Result.success()
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
         * Network-constrained, unique-name KEEP: multiple
         * enqueue bursts collapse into one pending flush.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

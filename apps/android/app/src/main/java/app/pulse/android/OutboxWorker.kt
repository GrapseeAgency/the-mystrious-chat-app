package app.pulse.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import app.pulse.domain.repository.PulseRepository

/**
 * Wave 0 offline core — the expedited outbox flusher. The repository drains
 * on its own triggers (start / socket reconnect / foreground / self-heal);
 * this worker guarantees the queue ALSO drains when only the OS knows the
 * network is back (app process dead, WorkManager wakes us up).
 *
 * Dependencies come through an @EntryPoint over the repository — no
 * hilt-work artifact needed (plan §6: "no hilt-work").
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors
            .fromApplication(applicationContext, OutboxWorkerEntryPoint::class.java)
            .pulseRepository()
        return try {
            val report = repo.flushOutbox()
            if (report.pending > 0) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OutboxWorkerEntryPoint {
        fun pulseRepository(): PulseRepository
    }

    companion object {
        private const val UNIQUE_NAME = "pulse-outbox-flush"

        /**
         * Expedited, network-constrained, unique-name REPLACE: multiple
         * enqueue bursts collapse into one pending flush.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

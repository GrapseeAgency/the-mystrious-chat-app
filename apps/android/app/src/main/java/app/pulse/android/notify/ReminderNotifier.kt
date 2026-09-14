package app.pulse.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import androidx.work.Data
import java.util.concurrent.TimeUnit

/**
 * Wave 7 F-RO-06 — Tier-1 local reminder notifications.
 *
 * Two independent delivery paths (spec: "local schedule fires offline; the
 * due loop polls the API when online"):
 *  1. WorkManager one-shot at remindAt — survives process death (NOT reboot;
 *     documented honest limitation), fires without network.
 *  2. The shell due-loop (MainActivity, 30 s foreground) — server truth,
 *     PATCHes firedAt after the nudge so the web sheet and other devices
 *     converge.
 *
 * Channel + permission model: POST_NOTIFICATIONS is requested once on 33+;
 * denial is honest (loop still fires in-app surfaces, no crash).
 */
object ReminderNotifier {

    const val CHANNEL_ID = "pulse_reminders"
    private const val BASE_NOTIFICATION_ID = 70_000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set in chats"
            },
        )
    }

    /** Runtime notifications permission state (API 33+; below → granted by install). */
    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, reminderId: String, title: String, body: String) {
        ensureChannel(context)
        if (!notificationsAllowed(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
        builder.setContentTitle(title.take(64))
        builder.setContentText(body.take(178))
        builder.setAutoCancel(true)
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(BASE_NOTIFICATION_ID + reminderId.hashCode(), notification)
    }

    private fun workName(reminderId: String) = "pulse-reminder-$reminderId"

    /** Schedule the offline-capable one-shot; replaces any pending copy of the same reminder. */
    fun schedule(context: Context, reminderId: String, note: String, remindAtEpochMs: Long) {
        if (remindAtEpochMs <= System.currentTimeMillis()) return
        val data = Data.Builder()
            .putString(KEY_ID, reminderId)
            .putString(KEY_NOTE, note)
            .build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(remindAtEpochMs - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(reminderId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, reminderId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
    }

    private const val KEY_ID = "reminderId"
    private const val KEY_NOTE = "note"

    /**
     * Fires the local notification at remindAt (no network needed). Server
     * convergence stays with the due loop — this worker NEVER PATCHes.
     */
    class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = inputData.getString(KEY_ID) ?: return Result.success()
            val note = inputData.getString(KEY_NOTE).orEmpty().ifBlank { "Reminder" }
            show(applicationContext, id, note, "Reminder")
            return Result.success()
        }
    }
}

package app.pulse.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private const val BASE_NOTIFICATION_ID = 70_000

    /**
     * Wave 8 — alert prefs gate. Channels are versioned per (sound, vibrate)
     * combo because Android notification channels are create-once: the user's
     * Notifications toggles select the channel, quiet hours force the silent
     * one at show-time (a time-dependent decision channel settings can't hold).
     */
    fun channelIdFor(sound: Boolean, vibrate: Boolean): String =
        "pulse_reminders_" + (if (sound) "s1" else "s0") + (if (vibrate) "v1" else "v0")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (sound in listOf(true, false)) {
            for (vibrate in listOf(true, false)) {
                val id = channelIdFor(sound, vibrate)
                if (manager.getNotificationChannel(id) != null) continue
                manager.createNotificationChannel(
                    NotificationChannel(id, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Reminders you set in chats"
                        if (sound) {
                            setSound(
                                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(),
                            )
                        } else {
                            setSound(null, null)
                        }
                        enableVibration(vibrate)
                    },
                )
            }
        }
    }

    /** Runtime notifications permission state (API 33+; below → granted by install). */
    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * R2-C item 6 — [conversationId] (when known) arms the contentIntent:
     * tapping the notification deep-links `pulse://room/<id>` (the Wave-6
     * routing in MainActivity handles the rest — the notification opens the
     * exact chat, web parity for reminder nudges). R7 item 4 — [messageId]
     * (when the reminder anchors a message) appends the `?jump=<mid>` payload
     * so the room auto-jumps + flashes the anchored bubble on open.
     */
    fun show(
        context: Context,
        reminderId: String,
        title: String,
        body: String,
        conversationId: String? = null,
        messageId: String? = null,
    ) {
        ensureChannel(context)
        if (!notificationsAllowed(context)) return
        // Wave 8 alert gate — server prefs (sound/vibrate) + LOCAL quiet hours:
        // quiet hours win over everything; otherwise the toggles pick the channel.
        val sound = ReminderAlertPolicy.soundOn && !ReminderAlertPolicy.quietNow()
        val vibrate = ReminderAlertPolicy.vibrateOn && !ReminderAlertPolicy.quietNow()
        val builder = NotificationCompat.Builder(context, channelIdFor(sound, vibrate))
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
        builder.setContentTitle(title.take(64))
        builder.setContentText(body.take(178))
        builder.setAutoCancel(true)
        if (!conversationId.isNullOrBlank()) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(app.pulse.core.link.PulseDeepLink.roomUri(conversationId, messageId)),
            ).apply {
                    // Confined to OUR pulse:// handler (the manifest VIEW
                    // intent-filter) — never handed to another app.
                    setPackage(context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    reminderId.hashCode(),
                    intent,
                    // Immutable per Android-12+ rules; UPDATE so re-armed
                    // reminders refresh the same tap target.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(BASE_NOTIFICATION_ID + reminderId.hashCode(), notification)
    }

    private fun workName(reminderId: String) = "pulse-reminder-$reminderId"

    /** Schedule the offline-capable one-shot; replaces any pending copy of the same reminder. */
    fun schedule(
        context: Context,
        reminderId: String,
        note: String,
        remindAtEpochMs: Long,
        conversationId: String? = null,
        messageId: String? = null,
    ) {
        if (remindAtEpochMs <= System.currentTimeMillis()) return
        val data = Data.Builder()
            .putString(KEY_ID, reminderId)
            .putString(KEY_NOTE, note)
            .putString(KEY_CONVERSATION_ID, conversationId.orEmpty())
            .putString(KEY_MESSAGE_ID, messageId.orEmpty())
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
    private const val KEY_CONVERSATION_ID = "conversationId"
    private const val KEY_MESSAGE_ID = "messageId"

    /**
     * Fires the local notification at remindAt (no network needed). Server
     * convergence stays with the due loop — this worker NEVER PATCHes.
     */
    class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = inputData.getString(KEY_ID) ?: return Result.success()
            val note = inputData.getString(KEY_NOTE).orEmpty().ifBlank { "Reminder" }
            val conversationId = inputData.getString(KEY_CONVERSATION_ID).orEmpty().ifBlank { null }
            val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty().ifBlank { null }
            show(applicationContext, id, note, "Reminder", conversationId, messageId)
            return Result.success()
        }
    }
}

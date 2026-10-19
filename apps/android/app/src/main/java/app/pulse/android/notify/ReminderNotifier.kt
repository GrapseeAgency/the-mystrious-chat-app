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
import app.pulse.domain.notify.ReminderOwner
import app.pulse.domain.notify.ReminderDeliveryGate
import app.pulse.protocol.ReminderItemDto
import app.pulse.protocol.PulseJson
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** WorkManager owns reboot recovery. The shared delivery gate arbitrates all
 * local delivery paths; no independent boot-cache replay is permitted. */
object ReminderNotifier {

    private const val BASE_NOTIFICATION_ID = 70_000

    /**
     * Wave 8 - alert prefs gate. Channels are versioned per (sound, vibrate)
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

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /**
     * R2-C item 6 - [conversationId] (when known) arms the contentIntent:
     * tapping the notification deep-links `pulse://room/<id>` (the Wave-6
     * routing in MainActivity handles the rest - the notification opens the
     * exact chat, web parity for reminder nudges). R7 item 4 - [messageId]
     * (when the reminder anchors a message) appends the `?jump=<mid>` payload
     * so the room auto-jumps + flashes the anchored bubble on open.
     */
    fun show(
        context: Context,
        deliveryKey: String,
        reminderId: String,
        title: String,
        body: String,
        conversationId: String? = null,
        messageId: String? = null,
    ): Boolean {
        ensureChannel(context)
        if (!notificationsAllowed(context)) return false
        // Wave 8 alert gate - server prefs (sound/vibrate) + LOCAL quiet hours:
        // quiet hours win over everything; otherwise the toggles pick the channel.
        val sound = ReminderAlertPolicy.soundOn && !ReminderAlertPolicy.quietNow()
        val vibrate = ReminderAlertPolicy.vibrateOn && !ReminderAlertPolicy.quietNow()
        val channel = channelIdFor(sound, vibrate)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val builder = NotificationCompat.Builder(context, channel)
        builder.setOnlyAlertOnce(true)
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
        builder.setContentTitle(title.take(64))
        builder.setContentText(body.take(178))
        builder.setAutoCancel(true)
        if (!conversationId.isNullOrBlank()) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(app.pulse.core.link.PulseDeepLink.roomUri(conversationId, messageId))
                    .buildUpon().appendQueryParameter("reminder", deliveryKey).build(),
            ).apply {
                    // Confined to OUR pulse:// handler (the manifest VIEW
                    // intent-filter) - never handed to another app.
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
        return try {
            // Full string tag prevents Java hash collisions from replacing another alert.
            NotificationManagerCompat.from(context).notify(deliveryKey, BASE_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    private fun workName(key: String) = "pulse-reminder-v2-$key"

    fun schedule(context: Context, owner: ReminderOwner, reminder: ReminderItemDto, at: Long) {
        val key = "${app.pulse.data.local.AccountStorageKey.namespace(owner.origin, owner.userId)}:${reminder.id}"
        val data = Data.Builder()
            .putString("owner", owner.userId).putString("origin", owner.origin)
            .putString("reminder", PulseJson.encodeToString(ReminderItemDto.serializer(), reminder))
            .build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelWork(context: Context, key: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    fun cancel(context: Context, key: String, reminderId: String) {
        cancelWork(context, key)
        // Remove legacy jobs/alerts too, but never create unowned work again.
        WorkManager.getInstance(context).cancelUniqueWork("pulse-reminder-$reminderId")
        NotificationManagerCompat.from(context).cancel(key, BASE_NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(BASE_NOTIFICATION_ID + reminderId.hashCode())
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DeliveryEntryPoint {
        fun reminders(): ReminderDeliveryManager
    }

    class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Legacy jobs carry no trustworthy owner. Foreground refresh replaces
            // them with scoped work; never guess the currently signed-in user.
            val owner = inputData.getString("owner") ?: return Result.success()
            val origin = inputData.getString("origin") ?: return Result.success()
            val raw = inputData.getString("reminder") ?: return Result.success()
            val reminder = try {
                PulseJson.decodeFromString(ReminderItemDto.serializer(), raw)
            } catch (_: Exception) { return Result.failure() }
            val manager = EntryPointAccessors.fromApplication(applicationContext, DeliveryEntryPoint::class.java).reminders()
            return try {
                when (manager.deliver(ReminderOwner(owner, origin), reminder)) {
                    ReminderDeliveryGate.Verdict.RETRY -> Result.retry()
                    else -> Result.success()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { Result.retry() }
        }
    }
}

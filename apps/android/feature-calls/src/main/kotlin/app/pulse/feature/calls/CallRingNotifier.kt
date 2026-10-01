package app.pulse.feature.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pulse.core.link.PulseDeepLink

/**
 * R8 Task 3-c - the TELECOM FALLBACK ring surface. When Telecom (self-managed
 * ConnectionService) is unavailable - pre-O devices, a refused registration,
 * an OEM policy rejection - the 1:1 incoming ring still surfaces through a
 * full-screen-intent, high-priority CALL notification on top of the existing
 * in-app ring UI. Never a fake "system call": the notification deep-links
 * into the conversation where the ring UI + answer controls live.
 *
 * The same surface carries the remote-push call rings (FCM `kind: call|gcall`)
 * from the app module - one channel, one honest idiom.
 */
object CallRingNotifier {

    /** Distinct from CallForegroundService's LOW "pulse_calls" ongoing channel. */
    const val CHANNEL_ID = "pulse_calls_ring"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming Pulse calls"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 250, 400)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Post the incoming-call ring notification. Returns true when a real
     * notification was posted (POST_NOTIFICATIONS granted on 33+); a silent
     * false is the honest "no notification surfaced" signal.
     */
    fun showIncoming(
        context: Context,
        title: String,
        body: String,
        conversationId: String?,
        notificationId: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        val deepLink = conversationId?.takeIf { it.isNotBlank() }
            ?.let { PulseDeepLink.roomUri(it) }
        val launch = deepLink?.let { uri ->
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(contentPi, true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, builder.build())
            true
        }.getOrDefault(false)
    }

    /** Best-effort cancel of one posted ring (the app opened / call ended). */
    fun cancel(context: Context, notificationId: Int) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
        }
    }
}

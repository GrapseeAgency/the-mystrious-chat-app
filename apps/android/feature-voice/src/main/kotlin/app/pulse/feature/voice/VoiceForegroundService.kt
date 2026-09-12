package app.pulse.feature.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Wave 5 — ongoing voice-room foreground service (manifest type `microphone`).
 * Android 11+ requires this to keep mic capture alive once the app leaves the
 * foreground (spec VR-11). Started when a voice room is joined, stopped on
 * leave. The notification is deliberately low-key: "Live voice room active".
 */
class VoiceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra(EXTRA_STOP, false) ?: false
        if (stop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_ROOM_LABEL) ?: "Live voice room active"
        startForeground(
            NOTIFICATION_ID,
            buildNotification(label),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(label: String): Notification {
        val pm = applicationContext.packageManager
        val launch = pm.getLaunchIntentForPackage(applicationContext.packageName)
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pulse voice room")
            .setContentText(label)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pulse_voice_rooms"
        private const val NOTIFICATION_ID = 43
        const val EXTRA_ROOM_LABEL = "roomLabel"
        const val EXTRA_STOP = "stop"

        fun start(context: Context, label: String = "Live voice room active") {
            val intent = Intent(context, VoiceForegroundService::class.java)
                .putExtra(EXTRA_ROOM_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).putExtra(EXTRA_STOP, true)
            runCatching { context.startService(intent) }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Voice rooms", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

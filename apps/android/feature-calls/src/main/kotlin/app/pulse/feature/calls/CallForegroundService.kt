package app.pulse.feature.calls

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
 * Wave 3 — ongoing-call foreground service (manifest type `microphone`).
 * Android 11+ REQUIRES this to keep mic capture alive once the app leaves
 * the foreground; it also carries the honest ongoing-call notification.
 * Started by [CallEngine] when a call begins, stopped when it ends.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra(EXTRA_STOP, false) ?: false
        if (stop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Voice call"
        startForeground(NOTIFICATION_ID, buildNotification(label), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
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
            .setContentTitle("Pulse call")
            .setContentText(label)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pulse_calls"
        private const val NOTIFICATION_ID = 41
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_STOP = "stop"

        fun start(context: Context, peerName: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_PEER_NAME, peerName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).putExtra(EXTRA_STOP, true)
            runCatching { context.startService(intent) }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

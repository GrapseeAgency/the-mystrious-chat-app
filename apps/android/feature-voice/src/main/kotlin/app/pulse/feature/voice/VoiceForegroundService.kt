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
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Wave 5 - ongoing voice-room foreground service (manifest type `microphone`).
 * Android 11+ requires this to keep mic capture alive once the app leaves the
 * foreground (spec VR-11). Started when a voice room is joined, stopped on
 * leave. The notification is deliberately low-key: "Live voice room active".
 */
@AndroidEntryPoint
class VoiceForegroundService : Service() {
    @Inject lateinit var rooms: VoiceRoomsEngine
    private var ownerToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            if (intent.getStringExtra(EXTRA_OWNER) == rooms.serviceToken) rooms.leaveAll()
            return START_NOT_STICKY
        }
        if (!rooms.hasAudioRoom) { stopSelf(); return START_NOT_STICKY }
        ownerToken = rooms.serviceToken
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    if (Build.VERSION.SDK_INT >= 30 && rooms.needsMicrophoneService)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildNotification(if (rooms.needsMicrophoneService) "Microphone enabled · tap to open" else "Listening · tap to open"), type)
        } catch (error: RuntimeException) {
            rooms.onServiceFailure(ownerToken, "Live audio could not stay active. Rejoin while Pulse is open.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        rooms.onServiceFailure(ownerToken, "The live audio service stopped. Rejoin when ready.")
        super.onDestroy()
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
        val leave = PendingIntent.getService(this, 43,
            Intent(this, VoiceForegroundService::class.java).setAction("app.pulse.LEAVE_ROOM.$ownerToken").putExtra(EXTRA_STOP, true).putExtra(EXTRA_OWNER, ownerToken),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Pulse voice room")
            .setContentText(label)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave room", leave)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pulse_voice_rooms"
        private const val NOTIFICATION_ID = 43
        const val EXTRA_ROOM_LABEL = "roomLabel"
        const val EXTRA_STOP = "stop"
        private const val EXTRA_OWNER = "owner"

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
            // Stopping must never start a new service (forbidden from background on modern Android).
            context.stopService(Intent(context, VoiceForegroundService::class.java))
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

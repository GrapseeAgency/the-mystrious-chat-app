package app.pulse.android.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pulse.core.link.PulseDeepLink
import app.pulse.android.notify.PulseReplyPayload
import app.pulse.feature.calls.CallRingNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * R8 Task 3-c - the FCM entry point. The system only ever starts this service
 * when a FirebaseApp is initialized (PulsePush.init with baked credentials) -
 * with no config it stays dormant, which IS the fail-closed behavior.
 *
 * Push envelope (server fanout contract, src/lib/push/transport.ts):
 *   notification { title, body } + data { kind, conversationId, messageId?,
 *   callKind?, callerName? } with kind = message | gcall | call.
 *
 *   message → the "Messages" channel (default); tap deep-links into the
 *             conversation (pulse://room/<id>[?jump=<messageId>]).
 *   gcall | call → the "Calls" channel (high priority, sound + vibration)
 *             via the shared CallRingNotifier (full-screen intent when the
 *             system allows it); the tap lands in the conversation where the
 *             ring/ongoing banners + join controls live.
 */
@AndroidEntryPoint
class PulseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var repository: app.pulse.domain.repository.PulseRepository

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated (length=${token.length})")
        PulsePush.onNewToken(repository, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val kind = data["kind"] ?: "message"
        val conversationId = data["conversationId"]
        val notification = message.notification
        val title = notification?.title ?: data["title"] ?: "Pulse"
        val body = notification?.body ?: data["body"] ?: ""
        Log.d(TAG, "push received kind=$kind conv=$conversationId")

        when (kind) {
            "gcall", "call" -> {
                val callerName = data["callerName"].orEmpty()
                val callKind = data["callKind"] ?: "voice"
                CallRingNotifier.showIncoming(
                    context = this,
                    title = title.ifBlank { callerName.ifBlank { "Pulse" } },
                    body = body.ifBlank {
                        if (kind == "gcall") {
                            "${callerName.ifBlank { "Someone" }} started a ${if (callKind == "video") "video" else "voice"} call"
                        } else {
                            "Incoming ${if (callKind == "video") "video" else "voice"} call"
                        }
                    },
                    conversationId = conversationId,
                    notificationId = CALL_NOTIFICATION_BASE + (conversationId?.hashCode()?.and(0x3FFFFFFF) ?: 0),
                )
            }

            else -> showMessageNotification(title, body, conversationId, data["messageId"])
        }
    }

    /** The default-importance message push with the room deep-link tap. */
    private fun showMessageNotification(title: String, body: String, conversationId: String?, messageId: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        PulsePush.ensureChannels(this)
        val deepLink = conversationId?.takeIf { it.isNotBlank() }?.let { conv ->
            PulseDeepLink.roomUri(conv, messageId)
        }
        val launch = deepLink?.let { uri ->
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } ?: packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this,
            (conversationId ?: "pulse").hashCode().and(0x3FFFFFFF),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PulsePush.MESSAGES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
        // R10-a - quick reply (RemoteInput): the action routes through
        // PulseReplyReceiver → SendMessageUseCase - the SAME send path the
        // room composer uses, so offline replies queue in the outbox.
        if (!conversationId.isNullOrBlank()) {
            val remoteInput = androidx.core.app.RemoteInput.Builder(PulseReplyPayload.KEY_REPLY_TEXT)
                .setLabel(getString(app.pulse.android.R.string.reply_action))
                .build()
            val replyIntent = Intent(this, app.pulse.android.notify.PulseReplyReceiver::class.java).apply {
                putExtra(PulseReplyPayload.EXTRA_CONVERSATION_ID, conversationId)
            }
            val replyPi = PendingIntent.getBroadcast(
                this,
                PulseReplyPayload.notificationId(conversationId),
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            notification.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.sym_action_chat,
                    getString(app.pulse.android.R.string.reply_action),
                    replyPi,
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build(),
            )
        }
        val built = notification.build()
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify((conversationId ?: "pulse").hashCode().and(0x3FFFFFFF), built)
        }.onFailure { Log.w(TAG, "message notification failed", it) }
    }

    private companion object {
        const val TAG = "PulseMessaging"
        const val CALL_NOTIFICATION_BASE = 700
    }
}

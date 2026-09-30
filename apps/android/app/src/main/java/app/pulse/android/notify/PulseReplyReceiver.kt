package app.pulse.android.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.pulse.core.link.PulseDeepLink
import app.pulse.android.OutboxWorker
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * R10-a - notification quick-reply. The system RemoteInput hand-off for the
 * "Reply" action PulseMessagingService attaches to every message notification
 * with a conversationId.
 *
 * Contract (honest end to end):
 *  · the SAME send path ChatRoomViewModel uses - [SendMessageUseCase] - so a
 *    network-class failure lands in the offline outbox exactly like an
 *    in-app send (temp `local_` bubble + [OutboxWorker] flush);
 *  · no viewer session → NEVER a fake send. The notification is re-posted as
 *    an honest "Open Pulse to reply" pointer whose content intent opens the
 *    room (the same pulse:// deep link the message notification uses);
 *  · delivered → the notification is cancelled (a silent success, no fake
 *    "sent" theater);
 *  · queued → the notification STAYS and the user is told the truth
 *    ("Couldn't send - queued") plus an expedited outbox flush is armed;
 *  · definitive failure → the notification STAYS and the honest failure
 *    text is toasted.
 *
 * DI: receivers cannot constructor-inject; the house pattern is
 * @AndroidEntryPoint field injection (same as BootReceiver/MainActivity).
 */
@AndroidEntryPoint
class PulseReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var sendUseCase: SendMessageUseCase
    @Inject lateinit var repository: PulseRepository

    override fun onReceive(context: Context, intent: Intent) {
        val parsed = PulseReplyPayload.parse(
            conversationId = intent.getStringExtra(PulseReplyPayload.EXTRA_CONVERSATION_ID),
            text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(PulseReplyPayload.KEY_REPLY_TEXT),
        )
        // Nothing usable (no conversation, empty reply) - clear the reply UI
        // quietly instead of pretending a message went out.
        if (parsed == null) {
            intent.getStringExtra(PulseReplyPayload.EXTRA_CONVERSATION_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { PulseReplyPayload.cancelNotification(context, it) }
            return
        }
        // goAsync: the send round-trip may outlive onReceive by seconds; the
        // async grant keeps the process alive until finish() (BootReceiver idiom).
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                deliver(appContext, parsed)
            } catch (_: Throwable) {
                PulseReplyPayload.toast(appContext, "Couldn't send")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun deliver(context: Context, parsed: PulseReplyPayload.Parsed) {
        val notificationId = PulseReplyPayload.notificationId(parsed.conversationId)
        // No viewer identity → do NOT fake-send. Honest pointer back into the
        // app: the content intent opens the room.
        if (repository.viewerId == null) {
            PulseReplyPayload.postOpenPulseNotification(context, parsed.conversationId)
            return
        }
        try {
            sendUseCase(parsed.conversationId, parsed.text)
                .onSuccess { receipt ->
                    if (PulseReplyPayload.isQueued(receipt.message.id)) {
                        // Network-class failure → the outbox owns delivery now.
                        OutboxWorker.enqueue(context)
                        PulseReplyPayload.toast(context, "Couldn't send - queued")
                    } else {
                        // Delivered - the notification did its job.
                        NotificationManagerCompat.from(context).cancel(notificationId)
                    }
                }
                .onFailure { failure ->
                    // Definitive failure - keep the notification, say why.
                    PulseReplyPayload.toast(context, failure.message ?: "Couldn't send")
                }
        } catch (t: Throwable) {
            // Defensive honesty: the use case reports failures as Result values,
            // but a thrown error must also leave the notification UP and say why.
            PulseReplyPayload.toast(context, t.message ?: "Couldn't send")
        }
    }
}

/**
 * Pure quick-reply kernel - the RemoteInput/extras contract, the notification
 * id scheme (the SAME `(conversationId).hashCode().and(0x3FFFFFFF)` math the
 * messaging service uses, so reply/repost/cancel address one notification),
 * and the queued-vs-delivered verdict. Object-form so a plain JVM test pins
 * it (app/src/test - no Robolectric needed).
 */
object PulseReplyPayload {

    const val KEY_REPLY_TEXT = "pulse_reply_text"
    const val EXTRA_CONVERSATION_ID = "app.pulse.android.reply.conversationId"

    /** Server cap - SendMessageUseCase.MAX_LENGTH (kept in lockstep). */
    const val MAX_CHARS = 2000

    data class Parsed(val conversationId: String, val text: String)

    /** Trim + validate the receiver intent. Null = nothing usable, never a throw. */
    fun parse(conversationId: String?, text: CharSequence?): Parsed? {
        val conv = conversationId?.trim().orEmpty()
        if (conv.isEmpty()) return null
        val body = text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) return null
        return Parsed(conversationId = conv, text = body.take(MAX_CHARS))
    }

    /** `local_<clientId>` temp rows are queued in the outbox, not delivered. */
    fun isQueued(messageId: String): Boolean = messageId.startsWith(TEMP_MESSAGE_PREFIX)

    /** Shared notification id scheme with PulseMessagingService. */
    fun notificationId(conversationId: String): Int =
        conversationId.hashCode().and(0x3FFFFFFF)

    fun cancelNotification(context: Context, conversationId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(conversationId)) }
    }

    /** The honest no-session path: "Open Pulse to reply" with the room tap. */
    fun postOpenPulseNotification(context: Context, conversationId: String) {
        if (!canNotify(context)) return
        app.pulse.android.push.PulsePush.ensureChannels(context)
        val launch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PulseDeepLink.roomUri(conversationId))).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId(conversationId),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, app.pulse.android.push.PulsePush.MESSAGES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Pulse")
            .setContentText("Open Pulse to reply")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(conversationId), notification) }
    }

    /** Toasts must ride the main looper - the receiver finishes on IO. */
    fun toast(context: Context, message: String) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

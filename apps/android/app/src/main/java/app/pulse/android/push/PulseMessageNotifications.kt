package app.pulse.android.push

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.sync.MessageNotificationIdentity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reconcile only positively identified message alerts. Never infer read from
 * an empty cache, watermark, manual reminder, or an unrelated notification. */
object PulseMessageNotifications {
    suspend fun reconcile(context: Context, repository: PulseRepository, isCurrent: suspend () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true // No supported active-notification query on API 21/22.
        val owner = repository.viewerId ?: return false
        val epoch = repository.attachmentSessionKey() ?: return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alerts = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
        var complete = true
        for (alert in alerts) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || repository.viewerId != owner || repository.attachmentSessionKey() != epoch) return false
            val identity = MessageNotificationIdentity.parse(alert.tag) ?: continue
            if (identity.ownerId != owner) continue
            val unread = repository.notificationMessageUnread(identity.conversationId, identity.messageId).getOrNull()
            if (unread == null) complete = false
            if (unread == false) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || repository.viewerId != owner || repository.attachmentSessionKey() != epoch) return false
                // The exact tag cannot address a newer message arriving while the proof was fetched.
                manager.cancel(alert.tag, alert.id)
            }
        }
        return complete
    }
}

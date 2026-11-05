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
    /** R77 - must stay byte-identical to PulseMessagingService.SUMMARY_TAG_PREFIX
     * (private there; duplicated here so the sweep can address summaries). */
    private const val SUMMARY_TAG_PREFIX = "pulse-group-"

    suspend fun reconcile(context: Context, repository: PulseRepository, isCurrent: suspend () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true // No supported active-notification query on API 21/22.
        val owner = repository.viewerId ?: return false
        val epoch = repository.attachmentSessionKey() ?: return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // A failed system-service query is not evidence of an empty tray.
        // Keep the durable worker pending just as for an unknown server proof.
        val alerts = runCatching { manager.activeNotifications.toList() }.getOrElse { return false }
        var complete = true
        val clearedConversations = mutableSetOf<String>()
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
                clearedConversations.add(identity.conversationId)
            }
        }
        // R77 - the per-conversation group summaries die once their LAST unread
        // member is cancelled - never before (an orphan summary that still hides
        // unread children would be a silent attention loss).
        if (clearedConversations.isNotEmpty()) {
            val remaining = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
                .mapNotNull { MessageNotificationIdentity.parse(it.tag) }
                .filter { it.ownerId == owner }
                .map { it.conversationId }
                .toSet()
            for (conversationId in clearedConversations) {
                if (conversationId !in remaining) {
                    runCatching { manager.cancel(SUMMARY_TAG_PREFIX + conversationId, 0) }
                }
            }
        }
        return complete
    }
}

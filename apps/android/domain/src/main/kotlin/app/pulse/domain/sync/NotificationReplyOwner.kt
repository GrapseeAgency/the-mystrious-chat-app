package app.pulse.domain.sync

/** A notification is not authority to send as whichever account is current.
 * Capture the live session at action time, then recheck at the durable write. */
data class NotificationReplyOwner(val userId: String, val sessionKey: String) {
    fun matches(viewerId: String?, currentSessionKey: String?): Boolean =
        userId.isNotBlank() && sessionKey.isNotBlank() && userId == viewerId && sessionKey == currentSessionKey

    companion object {
        fun capture(recipientUserId: String?, viewerId: String?, sessionKey: String?): NotificationReplyOwner? {
            if (recipientUserId.isNullOrBlank() || recipientUserId != viewerId || sessionKey.isNullOrBlank()) return null
            return NotificationReplyOwner(recipientUserId, sessionKey)
        }
    }
}

package app.pulse.domain.sync

/** Exact identities, not hash codes or arrival times. Shared FCM/native tag wire format. */
data class MessageNotificationIdentity(val ownerId: String, val conversationId: String, val messageId: String) {
    val tag: String get() = PREFIX + listOf(ownerId, conversationId, messageId).joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
    companion object {
        private const val PREFIX = "pulse-msg-v1/"
        fun parse(tag: String?): MessageNotificationIdentity? {
            if (tag == null || !tag.startsWith(PREFIX)) return null
            val parts = tag.removePrefix(PREFIX).split('/')
            if (parts.size != 3) return null
            val decoded = runCatching { parts.map { java.net.URLDecoder.decode(it, "UTF-8") } }.getOrNull() ?: return null
            if (decoded.any { it.isBlank() || it.length > 128 }) return null
            return MessageNotificationIdentity(decoded[0], decoded[1], decoded[2])
        }
    }
}

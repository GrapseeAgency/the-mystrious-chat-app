package app.pulse.core.link

/**
 * Wave 6 deep links (F-DL) — the `pulse://` scheme the app registers in the
 * manifest. Mirrors the web hash routes and the iOS PulseDeepLink parser:
 *   pulse://invite/{code}   → JoinGroupSheet parity (web ?join=CODE)
 *   pulse://user/{id}       → the full user page (web #/user/:id)
 *   pulse://room/{id}       → open the conversation (web #/chat/:id)
 * Both `pulse://room/abc` and `pulse:room/abc` (no "//") parse — Android
 * hands over either shape depending on how the link was built. Percent-
 * encoded path segments decode so hand-built NFC/QR links behave exactly
 * like in-app taps. Pure + total: anything unparseable is null (the caller
 * ignores it — no crash, no half-state).
 */
sealed interface PulseDeepLink {
    data class Invite(val code: String) : PulseDeepLink
    data class User(val userId: String) : PulseDeepLink

    /**
     * R7 item 4 — [jumpMessageId] optionally carries a reminder's anchored
     * message: the notification deep-link becomes `pulse://room/<id>?jump=<mid>`
     * and the room auto-jumps + flashes on open (web REMINDER_JUMP_EVENT parity).
     */
    data class Room(val conversationId: String, val jumpMessageId: String? = null) : PulseDeepLink

    companion object {
        const val SCHEME = "pulse"

        /**
         * Pure route builder for room deep links — `pulse://room/<id>` with an
         * optional `?jump=<messageId>` payload (ReminderNotifier uses it).
         */
        fun roomUri(conversationId: String, jumpMessageId: String? = null): String =
            buildString {
                append(SCHEME)
                append("://room/")
                append(conversationId)
                if (!jumpMessageId.isNullOrBlank()) {
                    append("?jump=")
                    append(jumpMessageId)
                }
            }

        /** Parse a URI handed over by the system (onCreate/onNewIntent). */
        fun parse(rawUri: String?): PulseDeepLink? {
            if (rawUri.isNullOrBlank()) return null
            val uri = runCatching { java.net.URI(rawUri.trim()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != SCHEME) return null

            fun decode(segment: String): String? =
                runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }

            // R7 item 4 — the `jump` query param rides along (first one wins).
            // NOTE: java.net.URI marks the bare-ssp shape (pulse:chat/c9?jump=m9,
            // no "//") OPAQUE — rawQuery is null there and the query lives inside
            // the scheme-specific part, so fall back to the substring after '?'.
            val query = uri.rawQuery
                ?: uri.rawSchemeSpecificPart?.takeIf { it.contains('?') }?.substringAfter('?')
            val jump = query
                ?.split('&')
                ?.mapNotNull { segment ->
                    val eq = segment.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    val key = decode(segment.substring(0, eq)) ?: return@mapNotNull null
                    val value = decode(segment.substring(eq + 1)) ?: return@mapNotNull null
                    key.lowercase() to value
                }
                ?.firstOrNull { it.first == "jump" }
                ?.second

            // pulse://invite/abc → authority "invite" + path "/abc";
            // pulse:invite/abc   → no authority, the ssp IS "invite/abc".
            val parts = buildList {
                uri.host?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                val tail = (if (uri.host.isNullOrBlank()) uri.rawSchemeSpecificPart else uri.rawPath)
                    ?.substringBefore('?')
                tail?.split('/')?.forEach { segment -> decode(segment)?.let(::add) }
            }
            if (parts.size < 2) return null
            val key = parts[1].trim()
            if (key.isEmpty()) return null
            return when (parts[0]) {
                "invite", "join", "group" -> Invite(code = key)
                "user", "u" -> User(userId = key)
                "room", "chat", "conversation" -> Room(conversationId = key, jumpMessageId = jump)
                else -> null
            }
        }
    }
}

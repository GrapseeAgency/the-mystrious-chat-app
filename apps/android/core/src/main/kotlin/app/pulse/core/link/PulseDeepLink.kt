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
    data class Room(val conversationId: String) : PulseDeepLink

    companion object {
        const val SCHEME = "pulse"

        /** Parse a URI handed over by the system (onCreate/onNewIntent). */
        fun parse(rawUri: String?): PulseDeepLink? {
            if (rawUri.isNullOrBlank()) return null
            val uri = runCatching { java.net.URI(rawUri.trim()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != SCHEME) return null

            fun decode(segment: String): String? =
                runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }

            // pulse://invite/abc → authority "invite" + path "/abc";
            // pulse:invite/abc   → no authority, the ssp IS "invite/abc".
            val parts = buildList {
                uri.host?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                val tail = if (uri.host.isNullOrBlank()) uri.rawSchemeSpecificPart else uri.rawPath
                tail?.split('/')?.forEach { segment -> decode(segment)?.let(::add) }
            }
            if (parts.size < 2) return null
            val key = parts[1].trim()
            if (key.isEmpty()) return null
            return when (parts[0]) {
                "invite", "join", "group" -> Invite(code = key)
                "user", "u" -> User(userId = key)
                "room", "chat", "conversation" -> Room(conversationId = key)
                else -> null
            }
        }
    }
}

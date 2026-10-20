package app.pulse.data.local

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** No raw account identifiers in filenames, and no legacy unowned-cache adoption. */
object AccountStorageKey {
    fun origin(raw: String): String {
        if (raw.isBlank()) return ""
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Gateway must use HTTP(S)" }
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) {
            "Gateway must be an origin/path without credentials, query or fragment"
        }
        val port = when {
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port,
            uri.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
    }

    fun namespace(gateway: String, viewerId: String): String {
        require(viewerId.isNotBlank()) { "An account namespace needs an owner" }
        val origin = origin(gateway)
        val identity = "${origin.toByteArray(Charsets.UTF_8).size}:$origin${viewerId.toByteArray(Charsets.UTF_8).size}:$viewerId"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun databaseName(gateway: String, viewerId: String) = "pulse-account-${namespace(gateway, viewerId)}.db"
}

package app.pulse.core

/**
 * Deployment endpoints — the ONE place a Pulse client learns where the cloud
 * lives. DEFAULT IS EMPTY = honest offline-first: there is NO baked host that
 * pretends to serve REST (a static CDN cannot execute /api/* — pointing REST
 * at raw.githubusercontent made every request a guaranteed 404). The gateway
 * origin is set at runtime: (a) from the user's persisted Server field in
 * Profile → Connection, or (b) release builds may bake one via
 * gradle -PpulseGateway=… (BuildConfig). Never localhost, never 10.0.2.2.
 *
 * The SAME origin drives realtime: the socket client dials <base> with the
 * sandbox-gateway convention `XTransformPort=3003` on every request (the
 * `/socket.io/` path rule on the edge 308-redirects and breaks WS upgrades —
 * verified empirically — while the query-param route connects, handshakes and
 * upgrades cleanly).
 */
object PulseEndpoints {

    /** HTTP base for every REST route (Next.js API through the gateway). */
    @Volatile var gatewayHttpUrl: String = ""

    /** Socket.IO relay base — blank = realtime disabled (offline-first client). */
    @Volatile var socketUrl: String = ""

    /** True once a real origin is configured — drives honest connection UI. */
    val isConfigured: Boolean get() = gatewayHttpUrl.isNotBlank()

    /**
     * Point both REST and realtime at one origin. Null/blank/non-http input
     * clears back to offline-first. Accepts "https://host[:port][/path]".
     */
    fun applyBase(base: String?) {
        val clean = base?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        gatewayHttpUrl = clean ?: ""
        socketUrl = clean ?: ""
    }

    fun http(path: String): String = gatewayHttpUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
}

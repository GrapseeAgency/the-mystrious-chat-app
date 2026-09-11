package app.pulse.core

/**
 * Deployment endpoints — the ONE place a Pulse client learns where the cloud
 * lives. DEFAULT IS EMPTY = honest offline-first: there is NO baked host that
 * pretends to serve REST (a static CDN cannot execute the API routes —
 * pointing REST at raw.githubusercontent made every request a guaranteed 404).
 * The gateway origin is set at runtime: (a) from the user's persisted Server
 * field in Profile → Connection, or (b) release builds may bake one via
 * gradle -PpulseGateway=… (BuildConfig). Never localhost, never 10.0.2.2.
 *
 * The SAME origin drives realtime: the socket client dials <base> with the
 * sandbox-gateway convention `XTransformPort=3003` on every request (the
 * `/socket.io/` path rule on the edge 308-redirects and breaks WS upgrades —
 * verified empirically — while the query-param route connects, handshakes and
 * upgrades cleanly).
 *
 * Wave 0: a deployment manifest (update-manifest.json `gateway`/`socket`
 * fields) can retarget the client at runtime via [applyOverride] — overrides
 * persist in the secure session vault and re-apply at every cold start.
 */
object PulseEndpoints {

    /** HTTP base for every REST route (Next.js API through the gateway). */
    @Volatile var gatewayHttpUrl: String = ""

    /** Socket.IO relay base — blank = realtime disabled (offline-first client). */
    @Volatile var socketUrl: String = ""

    /**
     * Optional TURN/STUN override (Wave 3-HW) — raw JSON array of
     * `{urls: string|string[], username?, credential?}` exactly as it appeared
     * in the deployment manifest `ice` field. Blank = the engine keeps its
     * built-in Google STUN defaults. Parsed at engine init (org.webrtc),
     * kept as a string here so `core` stays dependency-free.
     */
    @Volatile var iceServersJson: String = ""

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

    /**
     * Manifest-driven retarget — non-blank values replace the baked bases.
     * Safe to call repeatedly (idempotent per value); null/blank = keep current.
     */
    fun applyOverride(gateway: String?, socket: String?) {
        if (!gateway.isNullOrBlank()) gatewayHttpUrl = gateway.trim()
        if (!socket.isNullOrBlank()) socketUrl = socket.trim()
    }

    /**
     * Manifest-driven ICE override — a non-blank JSON array replaces the
     * built-in STUN set the next time a peer connection is created. Blank or
     * invalid JSON never clobbers a working value (same rule as the bases).
     */
    fun applyIceOverride(json: String?) {
        if (!json.isNullOrBlank()) iceServersJson = json.trim()
    }
}

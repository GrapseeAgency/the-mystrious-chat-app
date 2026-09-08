package app.pulse.core

/**
 * Deployment endpoints — the ONE place a Pulse client learns where the cloud
 * lives. Sandbox/dev defaults target the local gateway; release builds
 * override via gradle -PpulseGateway=… (wired into BuildConfig in a later wave).
 */
object PulseEndpoints {
    /** HTTP base for every REST route (Next.js API through the gateway). */
    @Volatile var gatewayHttpUrl: String = "http://localhost:81"

    /** Socket.IO relay base (pulse-socket service; web uses ?XTransformPort=3003). */
    @Volatile var socketUrl: String = "http://localhost:3003"

    fun http(path: String): String = gatewayHttpUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
}

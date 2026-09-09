package app.pulse.core

/**
 * Deployment endpoints — the ONE place a Pulse client learns where the cloud
 * lives. The default is the repo's public HTTPS CDN: reachable from any real
 * device (the old localhost / 10.0.2.2 defaults only ever worked on a desktop
 * dev machine, and plain http triggered Android's CLEARTEXT block on phones).
 * Release builds bake a different base via gradle -PpulseGateway=… (BuildConfig).
 */
object PulseEndpoints {
    /** HTTP base for every REST route (Next.js API through the gateway). */
    @Volatile var gatewayHttpUrl: String = "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main"

    /** Socket.IO relay base — blank = realtime disabled (offline-first client). */
    @Volatile var socketUrl: String = ""

    fun http(path: String): String = gatewayHttpUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
}

package app.pulse.android.push

/**
 * Section 6 - cross-gateway push provenance (pure kernel).
 *
 * A push payload stamped with a foreign gateway's origin must not render or
 * act on this device, even when conversation/user ids collide across
 * deployments. The comparison is origin-normalized (trim, strip a trailing
 * slash, lowercase) and fail-open for legacy payloads that carry NO origin
 * stamp (pre-section-6 registrations).
 */
object PushOriginGate {

    fun normalize(origin: String?): String? =
        origin?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** True when the stamped origin is present and does not match the
     * configured gateway (including the unconfigured case). */
    fun isForeign(stampedOrigin: String?, configuredOrigin: String?): Boolean {
        val stamped = normalize(stampedOrigin) ?: return false
        if (configuredOrigin == null) return true
        val configured = normalize(configuredOrigin) ?: return true
        return stamped != configured
    }
}

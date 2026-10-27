package app.pulse.domain.sync

import kotlinx.serialization.json.*

/** Display provenance, not an identity-verification badge. Older payload-only
 * markers were client-writable and must not be presented as verified origin. */
object MessageOrigin {
    private val reserved = setOf("bot", "webhook", "webhookName", "webhookColor")
    private fun objectOf(raw: String?): JsonObject? = raw?.takeIf { it.length <= 2048 }?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
    private fun flag(row: JsonObject?, key: String): Boolean =
        (row?.get(key) as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    fun label(raw: String?, automated: Boolean, deleted: Boolean = false): String? {
        if (deleted) return null
        val row = objectOf(raw)
        val webhook = flag(row, "webhook")
        val bot = flag(row, "bot")
        if (!automated) return if (webhook || bot) "Unverified integration" else null
        if (webhook) {
            val name = (row?.get("webhookName") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
            val safe = name.codePoints().filter { Character.getType(it) != Character.FORMAT.toInt() && !Character.isISOControl(it) }
                .limit(40).toArray().joinToString("") { String(Character.toChars(it)) }.trim()
            return if (safe.isEmpty()) "Webhook" else "Webhook · $safe"
        }
        return if (bot) "Bot" else "Automation"
    }

    /** A forward is authored by the forwarding person, not the source service.
     * Keep unchanged payload bytes when no reserved metadata was present. */
    fun forwardPayload(raw: String?): String? {
        if (raw == null) return null
        val row = objectOf(raw) ?: throw IllegalArgumentException("Forward metadata is invalid.")
        if (row.keys.none { it in reserved }) return raw
        val clean = row.filterKeys { it !in reserved }
        return if (clean.isEmpty()) null else JsonObject(clean).toString()
    }
}

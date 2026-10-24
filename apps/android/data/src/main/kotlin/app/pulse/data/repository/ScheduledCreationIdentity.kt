package app.pulse.data.repository

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** DataStore supplies the gateway/account namespace; equivalent UTC spellings share one intent. */
internal fun scheduledCreationIntent(owner: String, conversationId: String, content: String, whenIso: String): String =
    JsonArray(listOf(owner, "scheduled-send", conversationId, content.trim(),
        java.time.Instant.parse(whenIso).toEpochMilli().toString()).map { JsonPrimitive(it) }).toString()

package app.pulse.data.repository

import androidx.room.withTransaction
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** One durable Section 12 integration intent (automation create/delete,
 * webhook create/rotate/delete). Serialized to the account-scoped wave7 KV
 * journal (Section8IntentJournal precedent) so an uncertain acknowledgement
 * survives process loss and replays with the SAME clientId - the server
 * answers with the same row/verdict (replayed=true) instead of duplicating
 * the rule or rotating the capability a second time. */
@kotlinx.serialization.Serializable
data class Section12Intent(
    val kind: String, // "automation-create" | "automation-delete" | "webhook-create" | "webhook-delete" | "webhook-rotate"
    val clientId: String,
    val intentAtMs: Long,
    /** automation-create/webhook-create: conversationId · automation-delete: rule id · webhook-rotate/delete: capability token. */
    val targetId: String,
    /** Wire-body fields minus clientId (the body builders append the intent's own). */
    val payload: String,
)

/**
 * Section 12 items 2/4/6/7/8 - durable offline intent journal for the
 * integration mutations.
 *
 * Writes are journaled BEFORE the network call; the row is removed only after
 * a confirmed receipt (2xx + parsed envelope). A replay always reuses the same
 * clientId and the original intentAt, so a lost-ACK retry is the SAME server
 * mutation (replay receipt, never a duplicate row or a second rotation).
 * The account database and the owner-scoped key prefix isolate devices.
 */
internal class Section12IntegrationJournal(
    private val store: JournalStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    /** The wave7 KV surface the journal rides; narrow so a JVM test can fake it. */
    internal interface JournalStore {
        suspend fun upsert(row: Wave7CacheEntity)
        suspend fun readJournal(prefix: String): List<Wave7CacheEntity>
        suspend fun delete(key: String)
    }

    /** Production store - the account-scoped wave7 KV table (Section 8 precedent);
     * the enqueue upsert rides a Room transaction like Section8IntentJournal. */
    private class RoomJournalStore(private val db: PulseDatabase) : JournalStore {
        override suspend fun upsert(row: Wave7CacheEntity) {
            db.withTransaction { db.wave7Dao().upsert(row) }
        }
        override suspend fun readJournal(prefix: String): List<Wave7CacheEntity> = db.wave7Dao().readJournal(prefix)
        override suspend fun delete(key: String) = db.wave7Dao().delete(key)
    }

    internal constructor(db: PulseDatabase) : this(RoomJournalStore(db))

    private fun prefix(owner: String) = "section12-intent:v1|$owner|"

    suspend fun enqueue(owner: String, kind: String, targetId: String, payload: JsonObject): Section12Intent {
        val intent = Section12Intent(
            kind = kind,
            clientId = ids(),
            intentAtMs = clock(),
            targetId = targetId,
            payload = payload.toString(),
        )
        val row = Wave7CacheEntity(
            key = prefix(owner) + intent.clientId,
            json = Json.encodeToString(Section12Intent.serializer(), intent),
            updatedAt = intent.intentAtMs,
        )
        store.upsert(row)
        return intent
    }

    /** Confirmed receipts are the ONLY way a row leaves the journal. */
    suspend fun confirm(owner: String, clientId: String) {
        store.delete(prefix(owner) + clientId)
    }

    /** Restart-time flush source: the owner's pending intents, oldest first. */
    suspend fun pending(owner: String): List<Section12Intent> =
        store.readJournal(prefix(owner)).mapNotNull { row ->
            runCatching { Json.decodeFromString(Section12Intent.serializer(), row.json) }.getOrNull()
        }

    private fun payloadOf(intent: Section12Intent): JsonObject =
        Json.parseToJsonElement(intent.payload).let { it as JsonObject }

    private fun field(intent: Section12Intent, name: String): String =
        (payloadOf(intent)[name] as JsonPrimitive).content

    /** POST /api/conversations/{id}/automations - targetId carries the conversation. */
    fun toAutomationCreateBody(intent: Section12Intent): JsonObject = buildJsonObject {
        put("userId", field(intent, "userId"))
        put("trigger", field(intent, "trigger"))
        put("reply", field(intent, "reply"))
        put("clientId", intent.clientId)
    }

    /** DELETE /api/automations/{id} - targetId carries the rule id (JSON body form). */
    fun toAutomationDeleteBody(intent: Section12Intent): JsonObject = buildJsonObject {
        put("userId", field(intent, "userId"))
        put("clientId", intent.clientId)
    }

    /** POST /api/webhooks - targetId carries the conversation. */
    fun toWebhookCreateBody(intent: Section12Intent): JsonObject = buildJsonObject {
        put("conversationId", intent.targetId)
        put("name", field(intent, "name"))
        put("requesterId", field(intent, "requesterId"))
        put("clientId", intent.clientId)
    }

    /** PATCH /api/webhooks/{token} - targetId carries the OLD capability token. */
    fun toWebhookRotateBody(intent: Section12Intent): JsonObject = buildJsonObject {
        put("requesterId", field(intent, "requesterId"))
        put("clientId", intent.clientId)
    }

    /** DELETE /api/webhooks/{token} - targetId carries the token (JSON body form). */
    fun toWebhookDeleteBody(intent: Section12Intent): JsonObject = buildJsonObject {
        put("requesterId", field(intent, "requesterId"))
        put("clientId", intent.clientId)
    }
}

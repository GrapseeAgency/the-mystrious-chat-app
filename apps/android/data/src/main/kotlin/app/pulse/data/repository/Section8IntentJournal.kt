package app.pulse.data.repository

import androidx.room.withTransaction
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** One durable section-8 intent (block/unblock, report, folder/phrase write).
 * Serialized to the account-scoped wave7 KV journal so an uncertain
 * acknowledgement survives process loss and replays with the SAME identity.
 * Later sections reused the same journal (Section 9 stories, Section 10
 * events/tasks/kanban/reminders/packets, Section 11 market-create and
 * app-connect/app-disconnect, Section 13 profile patches). */
@kotlinx.serialization.Serializable
data class Section8Intent(
    val kind: String, // "block" | "unblock" | "report" | "folder-create" | "phrase-create" | "phrase-delete" | … | "profile-patch"
    val clientId: String,
    val intentAtMs: Long,
    val targetId: String,
    val payload: String, // JSON body fields the API call needs (reason, details, text, name, ...)
)

/**
 * Section 8 items 6/7/8/10 - durable offline intent journal.
 *
 * Writes are journaled BEFORE the network call; the row is removed only after
 * a confirmed server receipt. A replay always reuses the same clientId and the
 * original intentAt, so the server's total order (LWW over intentAt, replay
 * receipts for folders/phrases/reports) converges instead of duplicating.
 * The account database and the owner-scoped key prefix isolate devices.
 */
internal class Section8IntentJournal(
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

    /** Production store - the account-scoped wave7 KV table; the enqueue
     * upsert rides a Room transaction like the other section journals. */
    private class RoomJournalStore(private val db: PulseDatabase) : JournalStore {
        override suspend fun upsert(row: Wave7CacheEntity) {
            db.withTransaction { db.wave7Dao().upsert(row) }
        }
        override suspend fun readJournal(prefix: String): List<Wave7CacheEntity> = db.wave7Dao().readJournal(prefix)
        override suspend fun delete(key: String) = db.wave7Dao().delete(key)
    }

    internal constructor(db: PulseDatabase) : this(RoomJournalStore(db))

    private fun prefix(owner: String) = "section8-intent:v1|$owner|"

    suspend fun enqueue(owner: String, kind: String, targetId: String, payload: JsonObject): Section8Intent {
        val intent = Section8Intent(
            kind = kind,
            clientId = ids(),
            intentAtMs = clock(),
            targetId = targetId,
            payload = payload.toString(),
        )
        val row = Wave7CacheEntity(
            key = prefix(owner) + intent.clientId,
            json = Json.encodeToString(Section8Intent.serializer(), intent),
            updatedAt = intent.intentAtMs,
        )
        store.upsert(row)
        return intent
    }

    /** Confirmed receipts are the ONLY way a row leaves the journal. */
    suspend fun confirm(owner: String, clientId: String) {
        store.delete(prefix(owner) + clientId)
    }

    /** Restore-time flush source: the owner's pending intents, oldest first. */
    suspend fun pending(owner: String): List<Section8Intent> =
        store.readJournal(prefix(owner)).mapNotNull { row ->
            runCatching { Json.decodeFromString(Section8Intent.serializer(), row.json) }.getOrNull()
        }

    fun toBlockBody(intent: Section8Intent): JsonObject = buildJsonObject {
        put("userId", intent.targetId)
        put("clientId", intent.clientId)
        put("intentAt", java.time.Instant.ofEpochMilli(intent.intentAtMs).toString())
    }

    /**
     * Section 13 item 9 - PATCH /api/users/{id} body for a journaled profile
     * patch. The payload carries the EXACT profile fields the user originally
     * sent (name/about/color/avatar/coverImage/statusEmoji/statusText/
     * username, already client-normalized); the builder appends the intent's
     * own clientId so a restart replay reuses the SAME durable identity the
     * first attempt used - the server then answers with its stored receipt
     * (replayed=true) instead of applying a second, different write.
     */
    fun toProfilePatchBody(intent: Section8Intent): JsonObject {
        val payload = Json.parseToJsonElement(intent.payload).let { it as JsonObject }
        return buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("clientId", intent.clientId)
        }
    }
}

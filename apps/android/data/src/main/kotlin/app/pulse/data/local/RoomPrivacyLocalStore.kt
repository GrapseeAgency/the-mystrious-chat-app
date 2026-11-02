package app.pulse.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.domain.model.PersonalScreenPrivacy
import app.pulse.protocol.PulseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The containing DataStore is already gateway/account scoped. Each value,
 * revision and unconfirmed write identity commits in the same transaction. */
internal class RoomPrivacyLocalStore(private val storage: DataStore<Preferences>) {
    /** PulseJson keeps encodeDefaults=false (wire-sized bodies); the journal
     * needs the opposite: a row written by stage/acknowledge MUST carry its
     * `origin` field on disk, otherwise the next read would re-classify a fresh
     * explicit intent as migration-cache. */
    private val entryJson = Json(PulseJson) { encodeDefaults = true }

    @Serializable
    private data class Entry(
        val on: Boolean,
        val revision: String,
        val pending: String? = null,
        /** Section 13 item 6 - which explicit user intent produced this row
         * ("intent") or whether it is a migrated cache row that cannot prove
         * one ("migration-cache"). Default keeps old JSON parsing. */
        val origin: String = ORIGIN_INTENT,
    )
    data class Write(val room: String, val id: String, val on: Boolean)
    data class Read(val room: String, val revision: String?, val origin: String? = null)
    private fun key(room: String) = stringPreferencesKey("room.privacy.v1." +
        java.security.MessageDigest.getInstance("SHA-256").digest(room.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) })
    private fun read(prefs: Preferences, room: String): Entry? = prefs[key(room)]?.let {
        val raw = PulseJson.parseToJsonElement(it) as? JsonObject ?: error("Unreadable screen-security choice")
        val on = raw["on"] as? JsonPrimitive ?: error("Unreadable screen-security choice")
        check(!on.isString && on.content in setOf("true", "false")) { "Unreadable screen-security choice" }
        PulseJson.decodeFromString(Entry.serializer(), it).also { entry ->
            java.util.UUID.fromString(entry.revision)
            entry.pending?.let(java.util.UUID::fromString)
        }.let { entry ->
            // Section 13 item 6 - a row persisted before origin tracking cannot
            // prove which explicit user intent produced it, so it classifies as
            // migration-cache provenance ([migrateProvenance] rewrites it once).
            if (raw.containsKey("origin")) entry else entry.copy(origin = ORIGIN_MIGRATION_CACHE)
        }
    }
    private fun id() = java.util.UUID.randomUUID().toString()

    /**
     * Section 13 item 6 - one-time rewrite of pre-provenance rows: an entry
     * stored WITHOUT an `origin` field is rewritten with the honest
     * "migration-cache" mark (it is cache the device loaded, not a provable
     * explicit intent). Never invents a remote write for such rows; corrupt
     * journals are skipped here and surfaced by the read paths instead.
     */
    suspend fun migrateProvenance(room: String) {
        storage.edit { prefs ->
            if (prefs[key(room)] == null) return@edit
            val raw = runCatching { PulseJson.parseToJsonElement(prefs[key(room)]!!) }.getOrNull()
            if (raw !is JsonObject || raw.containsKey("origin")) return@edit
            val entry = runCatching { read(prefs, room) }.getOrNull() ?: return@edit
            prefs[key(room)] = entryJson.encodeToString(
                Entry.serializer(),
                entry.copy(origin = ORIGIN_MIGRATION_CACHE),
            )
        }
    }

    fun observe(room: String) = storage.observeResilient(
        initial = PersonalScreenPrivacy(),
        unavailable = { PersonalScreenPrivacy(on = true, pending = true, unreadable = true) },
    ) { prefs ->
        try {
            read(prefs, room).let {
                // Section 13 item 6 - provenance rides the read state additively.
                PersonalScreenPrivacy(it?.on, it?.pending != null, origin = it?.origin)
            }
        }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { PersonalScreenPrivacy(on = true, pending = true, unreadable = true) }
    }
    suspend fun readTicket(room: String): Read {
        migrateProvenance(room)
        val entry = read(storage.data.first(), room)
        return Read(room, entry?.revision, entry?.origin)
    }
    suspend fun pending(room: String): Write? = read(storage.data.first(), room)?.let { entry ->
        entry.pending?.let { Write(room, it, entry.on) }
    }
    suspend fun current(write: Write): Boolean = read(storage.data.first(), write.room)?.pending == write.id
    suspend fun stage(room: String, on: Boolean, owns: () -> Boolean): Write {
        val token = id()
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            read(prefs, room) // do not silently replace a corrupt journal
            prefs[key(room)] = entryJson.encodeToString(Entry.serializer(), Entry(on, token, token, ORIGIN_INTENT))
        }
        return Write(room, token, on)
    }
    suspend fun acknowledge(write: Write, confirmed: Boolean, owns: () -> Boolean) {
        check(confirmed == write.on) { "Personal screen security was not confirmed" }
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            if (read(prefs, write.room)?.pending == write.id) {
                prefs[key(write.room)] = entryJson.encodeToString(Entry.serializer(), Entry(confirmed, id(), origin = ORIGIN_INTENT))
            }
        }
    }
    suspend fun mergeSnapshot(ticket: Read, on: Boolean, owns: () -> Boolean) {
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            val current = read(prefs, ticket.room)
            if (current?.revision == ticket.revision && current?.pending == null) {
                // A server snapshot row is cache of a REMOTE decision, not this
                // device's intent - it keeps honest migration-cache provenance.
                prefs[key(ticket.room)] = entryJson.encodeToString(
                    Entry.serializer(),
                    Entry(on, id(), origin = current?.origin ?: ORIGIN_MIGRATION_CACHE),
                )
            }
        }
    }

    companion object {
        /** The row was produced by THIS device's explicit stage/acknowledge. */
        const val ORIGIN_INTENT = "intent"

        /** The row predates origin tracking - cache loaded from storage that
         * cannot prove which explicit user intent produced it. */
        const val ORIGIN_MIGRATION_CACHE = "migration-cache"
    }
}

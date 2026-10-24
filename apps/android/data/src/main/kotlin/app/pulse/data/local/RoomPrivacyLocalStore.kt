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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The containing DataStore is already gateway/account scoped. Each value,
 * revision and unconfirmed write identity commits in the same transaction. */
internal class RoomPrivacyLocalStore(private val storage: DataStore<Preferences>) {
    @Serializable private data class Entry(val on: Boolean, val revision: String, val pending: String? = null)
    data class Write(val room: String, val id: String, val on: Boolean)
    data class Read(val room: String, val revision: String?)
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
        }
    }
    private fun id() = java.util.UUID.randomUUID().toString()
    fun observe(room: String) = storage.observeResilient(
        initial = PersonalScreenPrivacy(),
        unavailable = { PersonalScreenPrivacy(on = true, pending = true, unreadable = true) },
    ) { prefs ->
        try { read(prefs, room).let { PersonalScreenPrivacy(it?.on, it?.pending != null) } }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { PersonalScreenPrivacy(on = true, pending = true, unreadable = true) }
    }
    suspend fun readTicket(room: String) = Read(room, read(storage.data.first(), room)?.revision)
    suspend fun pending(room: String): Write? = read(storage.data.first(), room)?.let { entry ->
        entry.pending?.let { Write(room, it, entry.on) }
    }
    suspend fun current(write: Write): Boolean = read(storage.data.first(), write.room)?.pending == write.id
    suspend fun stage(room: String, on: Boolean, owns: () -> Boolean): Write {
        val token = id()
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            read(prefs, room) // do not silently replace a corrupt journal
            prefs[key(room)] = PulseJson.encodeToString(Entry.serializer(), Entry(on, token, token))
        }
        return Write(room, token, on)
    }
    suspend fun acknowledge(write: Write, confirmed: Boolean, owns: () -> Boolean) {
        check(confirmed == write.on) { "Personal screen security was not confirmed" }
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            if (read(prefs, write.room)?.pending == write.id) {
                prefs[key(write.room)] = PulseJson.encodeToString(Entry.serializer(), Entry(confirmed, id()))
            }
        }
    }
    suspend fun mergeSnapshot(ticket: Read, on: Boolean, owns: () -> Boolean) {
        storage.edit { prefs ->
            if (!owns()) throw CancellationException("Screen-security session ended")
            val current = read(prefs, ticket.room)
            if (current?.revision == ticket.revision && current?.pending == null) {
                prefs[key(ticket.room)] = PulseJson.encodeToString(Entry.serializer(), Entry(on, id()))
            }
        }
    }
}

package app.pulse.data.repository

import app.pulse.data.local.Wave7CacheEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the repository's unwrapping of journaled body fields. */
private fun JsonObject.textField(name: String): String = (this[name] as JsonPrimitive).content

/**
 * Section 13 item 9 - the durable profile-patch intent. PATCH /api/users/{id}
 * is journaled BEFORE the wire; a lost ACK replays with the SAME clientId (the
 * server answers with its stored receipt, replayed=true), and only a confirmed
 * 2xx removes the journal row.
 */
class Section8ProfileJournalTest {

    /** In-memory stand-in for the account-scoped wave7 KV store. */
    private class FakeStore : Section8IntentJournal.JournalStore {
        val rows = LinkedHashMap<String, Wave7CacheEntity>()
        override suspend fun upsert(row: Wave7CacheEntity) { rows[row.key] = row }
        override suspend fun readJournal(prefix: String): List<Wave7CacheEntity> =
            rows.entries.filter { it.key.startsWith(prefix) }.map { it.value }
        override suspend fun delete(key: String) { rows.remove(key) }
    }

    private fun journal(store: FakeStore = FakeStore(), ids: () -> String = { "clientid0123456789" }) =
        Section8IntentJournal(store, clock = { 1_700_000_000_000 }, ids = ids)

    @Test
    fun enqueuePersistsBeforeTheWireAndPendingRestoresItWithTheSameIdentity() = runTest {
        val store = FakeStore()
        val j = journal(store)
        val intent = j.enqueue("owner", "profile-patch", "owner",
            buildJsonObject { put("name", "Cara"); put("about", "Rocket fuel") })
        // The row is durable the moment enqueue returns (before any wire call).
        assertTrue(store.rows.keys.single().startsWith("section8-intent:v1|owner|"))
        val restored = j.pending("owner")
        assertEquals(listOf(intent.clientId), restored.map { it.clientId })
        assertEquals("profile-patch", restored.single().kind)
        assertEquals("owner", restored.single().targetId)
        assertEquals(1_700_000_000_000, restored.single().intentAtMs)
    }

    @Test
    fun profilePatchBodyCarriesTheOriginalFieldsPlusTheSameClientId() = runTest {
        val j = journal()
        val intent = j.enqueue("owner", "profile-patch", "owner",
            buildJsonObject {
                put("name", "Cara"); put("about", "Rocket fuel"); put("color", "emerald")
                put("statusEmoji", "🚀"); put("username", "cara")
            })
        // The journaled payload never carries a stale identity...
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(intent.payload) as JsonObject
        assertNull(payload["clientId"])
        // ...the wire builder appends the intent's own clientId.
        val body = j.toProfilePatchBody(intent)
        assertEquals("Cara", body.textField("name"))
        assertEquals("Rocket fuel", body.textField("about"))
        assertEquals("emerald", body.textField("color"))
        assertEquals("🚀", body.textField("statusEmoji"))
        assertEquals("cara", body.textField("username"))
        assertEquals(intent.clientId, body.textField("clientId"))
    }

    @Test
    fun aLostAcknowledgementKeepsTheRowAndConfirmRemovesOnlyTheWinner() = runTest {
        var seq = 0
        val j = journal(ids = { "client%016d".format(seq++) })
        val first = j.enqueue("owner", "profile-patch", "owner", buildJsonObject { put("name", "A") })
        val second = j.enqueue("owner", "profile-patch", "owner", buildJsonObject { put("name", "B") })
        // First attempt's ACK is lost: NOTHING is confirmed, both stay pending in
        // FIFO order - distinct clientIds mean distinct journal rows.
        assertEquals(listOf(first.clientId, second.clientId), j.pending("owner").map { it.clientId })
        // The retry replays the SAME clientId; only its receipt confirm removes it.
        j.confirm("owner", first.clientId)
        assertEquals(listOf(second.clientId), j.pending("owner").map { it.clientId })
        j.confirm("owner", second.clientId)
        assertTrue(j.pending("owner").isEmpty())
        // Confirmation is identity-scoped: an unknown clientId removes nothing.
        j.enqueue("owner", "profile-patch", "owner", buildJsonObject { put("name", "C") })
        j.confirm("owner", "not-the-clientId")
        assertEquals(1, j.pending("owner").size)
    }

    @Test
    fun pendingIsolatesOwnersAndSkipsCorruptRows() = runTest {
        val store = FakeStore()
        val j = journal(store)
        j.enqueue("owner-a", "profile-patch", "owner-a", buildJsonObject { put("name", "A") })
        j.enqueue("owner-b", "profile-patch", "owner-b", buildJsonObject { put("name", "B") })
        store.rows["section8-intent:v1|owner-a|corrupt"] = Wave7CacheEntity(
            key = "section8-intent:v1|owner-a|corrupt", json = "{not json", updatedAt = 1L)
        assertEquals(1, j.pending("owner-a").size)
        assertEquals(1, j.pending("owner-b").size)
        assertEquals(0, j.pending("owner-c").size)
    }
}

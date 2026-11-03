package app.pulse.data.repository

import app.pulse.data.local.Wave7CacheEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 14 item 9 - storage fault behavior on the durable intent journal:
 * enqueue failures are LOUD (nothing is silently dropped), prior rows are
 * retained on every failure, corrupt rows are skipped but never deleted,
 * and an ack (confirm) is the only row-removal authority - so a crash
 * between enqueue and ack reconciles as exactly one replay with the SAME
 * identity.
 */
class StorageFaultAcceptanceTest {

    /** In-memory store with fault injection + row retention tracking. */
    private class FaultingStore : Section8IntentJournal.JournalStore {
        val rows = mutableMapOf<String, Wave7CacheEntity>()
        var upsertThrows = false
        var deleteThrows = false
        var deletedKeys = mutableListOf<String>()

        override suspend fun upsert(row: Wave7CacheEntity) {
            if (upsertThrows) throw java.io.IOException("disk full")
            rows[row.key] = row
        }

        override suspend fun readJournal(prefix: String): List<Wave7CacheEntity> =
            rows.keys.filter { it.startsWith(prefix) }.sorted().map { rows.getValue(it) }

        override suspend fun delete(key: String) {
            if (deleteThrows) throw java.io.IOException("disk full")
            deletedKeys.add(key)
            rows.remove(key)
        }
    }

    private fun journal(store: FaultingStore) = Section8IntentJournal(
        store,
        clock = { 1_000L },
        ids = { "client-fixed-1" },
    )

    @Test
    fun `an enqueue failure propagates loudly - the caller knows nothing was journaled`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        val first = gate.enqueue("viewer-a", "block", "user-b", buildJsonObject { put("reason", "spam") })
        store.upsertThrows = true
        val outcome = runCatching { gate.enqueue("viewer-a", "report", "user-b", buildJsonObject { put("details", "x") }) }
        assertTrue(outcome.isFailure)
        // the pre-failure row survived: the journal never traded it away
        assertEquals(listOf("section8-intent:v1|viewer-a|${first.clientId}"), store.rows.keys.toList())
    }

    @Test
    fun `a corrupt row is skipped by pending reads but is never deleted - recovery stays possible`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        val good = gate.enqueue("viewer-a", "block", "user-b", buildJsonObject { put("reason", "spam") })
        val corruptKey = "section8-intent:v1|viewer-a|corrupt-row"
        store.rows[corruptKey] = Wave7CacheEntity(key = corruptKey, json = "{not json", updatedAt = 5)

        val pending = gate.pending("viewer-a")

        assertEquals(listOf(good.clientId), pending.map { it.clientId })
        assertTrue(store.rows.containsKey(corruptKey))
    }

    @Test
    fun `a confirm failure retains the row - the ack never loses a debt`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        val intent = gate.enqueue("viewer-a", "block", "user-b", buildJsonObject { put("reason", "spam") })
        store.deleteThrows = true

        runCatching { gate.confirm("viewer-a", intent.clientId) }

        assertEquals(1, gate.pending("viewer-a").size)
        store.deleteThrows = false
        gate.confirm("viewer-a", intent.clientId)
        assertEquals(0, gate.pending("viewer-a").size)
    }

    @Test
    fun `a crash between enqueue and ack reconciles as ONE replay with the same identity`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        val intent = gate.enqueue("viewer-a", "block", "user-b", buildJsonObject { put("reason", "spam") })
        // process dies here; the restore-time flush sees the row
        val replayed = gate.pending("viewer-a")
        assertEquals(1, replayed.size)
        assertEquals(intent.clientId, replayed[0].clientId)
        assertEquals(intent.intentAtMs, replayed[0].intentAtMs)
        // the server receipt confirm removes exactly that row, nothing else
        gate.confirm("viewer-a", intent.clientId)
        assertEquals(0, gate.pending("viewer-a").size)
        assertEquals(listOf("section8-intent:v1|viewer-a|${intent.clientId}"), store.deletedKeys)
    }

    @Test
    fun `rows are owner-scoped - another account's journal is invisible`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        gate.enqueue("viewer-a", "block", "user-b", buildJsonObject { put("reason", "spam") })
        gate.enqueue("viewer-b", "block", "user-c", buildJsonObject { put("reason", "spam") })
        val pendingForA = gate.pending("viewer-a")
        assertEquals(1, pendingForA.size)
        assertEquals("user-b", pendingForA[0].targetId)
        // confirm on the foreign owner cannot touch the other owner's rows
        gate.confirm("viewer-a", "does-not-exist")
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `the profile-patch body re-attaches the journaled identity for replay`() = runTest {
        val store = FaultingStore()
        val gate = journal(store)
        val intent = gate.enqueue("viewer-a", "profile-patch", "viewer-a", buildJsonObject {
            put("name", "New Name")
            put("bio", "New bio")
        })
        val body = gate.toProfilePatchBody(intent)
        assertEquals(intent.clientId, body["clientId"]?.contentOrNull())
        assertEquals("New Name", body["name"]?.contentOrNull())
    }

    private fun kotlinx.serialization.json.JsonElement?.contentOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content
}

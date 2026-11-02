package app.pulse.data.repository

import app.pulse.data.local.Wave7CacheEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the repository's unwrapping of journaled body fields. */
private fun JsonObject.textField(name: String): String = (this[name] as JsonPrimitive).content

/**
 * Section 12 - the durable integration intent journal (automation
 * create/delete, webhook create/rotate/delete). The journal is the lost-ACK
 * contract: an intent is written BEFORE the wire, replays with the SAME
 * clientId on restart, and leaves ONLY on a confirmed receipt.
 */
class Section12IntegrationJournalTest {

    /** In-memory stand-in for the account-scoped wave7 KV store. */
    private class FakeStore : Section12IntegrationJournal.JournalStore {
        val rows = LinkedHashMap<String, Wave7CacheEntity>()
        override suspend fun upsert(row: Wave7CacheEntity) { rows[row.key] = row }
        override suspend fun readJournal(prefix: String): List<Wave7CacheEntity> =
            rows.entries.filter { it.key.startsWith(prefix) }.map { it.value }
        override suspend fun delete(key: String) { rows.remove(key) }
    }

    private fun journal(store: FakeStore = FakeStore(), ids: () -> String = { "clientid0123456789" }) =
        Section12IntegrationJournal(store, clock = { 1_700_000_000_000 }, ids = ids)

    @Test
    fun enqueuePersistsBeforeTheWireAndPendingRestoresItWithTheSameIdentity() = runTest {
        val store = FakeStore()
        val j = journal(store)
        val intent = j.enqueue("owner", "automation-create", "room",
            buildJsonObject { put("userId", "owner"); put("trigger", "Lantern"); put("reply", "Lit") })
        // The row is durable the moment enqueue returns (before any wire call).
        assertTrue(store.rows.keys.single().startsWith("section12-intent:v1|owner|"))
        val restored = j.pending("owner")
        assertEquals(listOf(intent.clientId), restored.map { it.clientId })
        assertEquals("automation-create", restored.single().kind)
        assertEquals("room", restored.single().targetId)
    }

    @Test
    fun bodyBuildersCarryTheOriginalInstructionsPlusTheSameClientId() = runTest {
        val j = journal()
        val create = j.enqueue("owner", "automation-create", "room",
            buildJsonObject { put("userId", "owner"); put("trigger", "Lantern"); put("reply", "Lit") })
        val body = j.toAutomationCreateBody(create)
        assertEquals("owner", body.textField("userId"))
        assertEquals("Lantern", body.textField("trigger"))
        assertEquals("Lit", body.textField("reply"))
        assertEquals(create.clientId, body.textField("clientId"))
        val rotate = j.enqueue("owner", "webhook-rotate", "token-1",
            buildJsonObject { put("requesterId", "owner") })
        val rotateBody = j.toWebhookRotateBody(rotate)
        assertEquals("owner", rotateBody.textField("requesterId"))
        assertEquals(rotate.clientId, rotateBody.textField("clientId"))
    }

    @Test
    fun aLostAcknowledgementKeepsTheRowAndConfirmRemovesOnlyTheWinner() = runTest {
        var seq = 0
        val j = journal(ids = { "client%016d".format(seq++) })
        val first = j.enqueue("owner", "automation-delete", "rule-1",
            buildJsonObject { put("userId", "owner") })
        val second = j.enqueue("owner", "webhook-create", "room",
            buildJsonObject { put("name", "CI bot"); put("requesterId", "owner") })
        // First intent's ACK is lost: nothing is confirmed, both stay pending.
        // Pending rows keep FIFO order and the SECOND intent must not overwrite
        // the first - distinct clientIds mean distinct journal rows.
        assertEquals(listOf(first.clientId, second.clientId), j.pending("owner").map { it.clientId })
        // The retry replays the SAME clientId (the receipt confirm removes it).
        j.confirm("owner", first.clientId)
        assertEquals(listOf(second.clientId), j.pending("owner").map { it.clientId })
        j.confirm("owner", second.clientId)
        assertTrue(j.pending("owner").isEmpty())
        // Confirmation is identity-scoped: an unknown clientId removes nothing.
        j.enqueue("owner", "webhook-rotate", "token-2", buildJsonObject { put("requesterId", "owner") })
        j.confirm("owner", "not-the-clientId")
        assertEquals(1, j.pending("owner").size)
    }

    @Test
    fun pendingIsolatesOwnersAndSkipsCorruptRows() = runTest {
        val store = FakeStore()
        val j = journal(store)
        j.enqueue("owner-a", "webhook-delete", "token-1", buildJsonObject { put("requesterId", "owner-a") })
        j.enqueue("owner-b", "automation-create", "room", buildJsonObject { put("userId", "owner-b") })
        store.rows["section12-intent:v1|owner-a|corrupt"] = Wave7CacheEntity(
            key = "section12-intent:v1|owner-a|corrupt", json = "{not json", updatedAt = 1L)
        assertEquals(1, j.pending("owner-a").size)
        assertEquals(1, j.pending("owner-b").size)
        assertEquals(0, j.pending("owner-c").size)
    }
}

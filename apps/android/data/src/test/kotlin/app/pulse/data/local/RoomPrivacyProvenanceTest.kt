package app.pulse.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 13 item 6 - provenance of the per-room screen-security journal. A row
 * written by an explicit stage/acknowledge carries origin "intent"; a row that
 * predates origin tracking cannot prove which explicit user intent produced
 * it, so it is honestly marked "migration-cache" and rewritten ONCE - and a
 * pure server-snapshot row is cache of a remote decision, never local intent.
 */
class RoomPrivacyProvenanceTest {
    private class Storage {
        val file = File.createTempFile("room-privacy-provenance", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        var data = open()
        var store = RoomPrivacyLocalStore(data)
        suspend fun reopen() { job.cancelAndJoin(); job = SupervisorJob(); data = open(); store = RoomPrivacyLocalStore(data) }
        suspend fun close() { job.cancelAndJoin(); file.delete() }
    }

    private fun key(room: String) = stringPreferencesKey("room.privacy.v1." +
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(room.toByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun `a legacy row without origin reads as migration-cache and is rewritten once`() = runBlocking {
        val db = Storage()
        try {
            val legacy = """{"on":true,"revision":"${UUID.randomUUID()}","pending":"${UUID.randomUUID()}"}"""
            db.data.edit { it[key("room")] = legacy }
            // The read state exposes the honest provenance BEFORE any rewrite...
            val observed = db.store.observe("room").first()
            assertEquals(true, observed.on)
            assertTrue(observed.pending)
            assertEquals(RoomPrivacyLocalStore.ORIGIN_MIGRATION_CACHE, observed.origin)
            // ...readTicket performs the one-time rewrite (an additive origin
            // field on the SAME value - the on/pending verdict is untouched).
            val ticket = db.store.readTicket("room")
            assertEquals(RoomPrivacyLocalStore.ORIGIN_MIGRATION_CACHE, ticket.origin)
            assertEquals(true, ticket.revision != null)
            val rewritten = db.data.data.first()[key("room")]!!
            assertTrue(rewritten.contains("\"origin\":\"migration-cache\""))
            // A second pass is a no-op (the field is present now) and never
            // flips a migrated row into a claimed intent.
            db.store.readTicket("room")
            assertEquals(rewritten, db.data.data.first()[key("room")])
            assertEquals(RoomPrivacyLocalStore.ORIGIN_MIGRATION_CACHE, db.store.observe("room").first().origin)
            db.reopen()
            assertEquals(RoomPrivacyLocalStore.ORIGIN_MIGRATION_CACHE, db.store.readTicket("room").origin)
        } finally { db.close() }
    }

    @Test fun `a fresh explicit intent keeps intent provenance`() = runBlocking {
        val db = Storage()
        try {
            val intent = db.store.stage("room", true) { true }
            assertEquals(RoomPrivacyLocalStore.ORIGIN_INTENT, db.store.observe("room").first().origin)
            db.store.acknowledge(intent, true) { true }
            val after = db.store.readTicket("room")
            assertEquals(RoomPrivacyLocalStore.ORIGIN_INTENT, after.origin)
            // A later snapshot that matches the read revision PRESERVES the
            // provenance of the row it replaces.
            db.store.mergeSnapshot(after, true) { true }
            assertEquals(RoomPrivacyLocalStore.ORIGIN_INTENT, db.store.observe("room").first().origin)
        } finally { db.close() }
    }

    @Test fun `a server snapshot for a fresh room is cache not intent`() = runBlocking {
        val db = Storage()
        try {
            val before = db.store.readTicket("remote-room") // no local row yet
            assertNull(before.origin)
            db.store.mergeSnapshot(before, true) { true }
            val state = db.store.observe("remote-room").first()
            assertEquals(true, state.on)
            assertFalse(state.pending)
            assertEquals(RoomPrivacyLocalStore.ORIGIN_MIGRATION_CACHE, state.origin)
            // A room never touched at all carries no provenance either.
            assertNull(db.store.observe("empty-room").first().origin)
        } finally { db.close() }
    }
}

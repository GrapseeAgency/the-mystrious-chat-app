package app.pulse.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomPrivacyLocalStoreTest {
    private class Storage {
        val file = File.createTempFile("room-privacy", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        var data = open()
        var store = RoomPrivacyLocalStore(data)
        suspend fun reopen() { job.cancelAndJoin(); job = SupervisorJob(); data = open(); store = RoomPrivacyLocalStore(data) }
        suspend fun close() { job.cancelAndJoin(); file.delete() }
    }
    @Test fun `late snapshots cannot undo a pending choice or its completed acknowledgement`() = runBlocking {
        val db = Storage()
        try {
            val before = db.store.readTicket("room")
            val intent = db.store.stage("room", true) { true }
            db.store.mergeSnapshot(before, false) { true }
            assertEquals(true, db.store.observe("room").first().on)
            val during = db.store.readTicket("room")
            db.store.acknowledge(intent, true) { true }
            db.store.mergeSnapshot(during, false) { true }
            assertEquals(true, db.store.observe("room").first().on)
            assertFalse(db.store.observe("room").first().pending)
            val fresh = db.store.readTicket("room")
            db.store.mergeSnapshot(fresh, false) { true }
            assertEquals(false, db.store.observe("room").first().on)
        } finally { db.close() }
    }
    @Test fun `ABA receipts and stale retries cannot confirm or restage newer intent`() = runBlocking {
        val db = Storage()
        try {
            val old = db.store.stage("room", true) { true }
            db.store.stage("room", false) { true }
            val latest = db.store.stage("room", true) { true }
            db.store.acknowledge(old, true) { true }
            assertFalse(db.store.current(old)); assertEquals(latest, db.store.pending("room"))
            db.reopen()
            assertEquals(latest, db.store.pending("room"))
            db.store.acknowledge(latest, true) { true }
            assertNull(db.store.pending("room"))
        } finally { db.close() }
    }
    @Test fun `rooms accounts and retired writers stay isolated`() = runBlocking {
        val first = Storage(); val second = Storage()
        try {
            val ticket = first.store.stage("room", true) { true }
            val otherRoom = first.store.stage("different", false) { true }
            assertNull(second.store.observe("room").first().on)
            first.store.acknowledge(otherRoom, false) { true }
            assertTrue(first.store.observe("room").first().pending)
            assertTrue(runCatching { first.store.acknowledge(ticket, true) { false } }.isFailure)
            assertTrue(runCatching { first.store.acknowledge(ticket, false) { true } }.isFailure)
            assertEquals(ticket, first.store.pending("room"))
        } finally { first.close(); second.close() }
    }
    @Test fun `a malformed local boolean is protection on not a confirmed off`() = runBlocking {
        val db = Storage()
        try {
            val key = stringPreferencesKey("room.privacy.v1." + java.security.MessageDigest.getInstance("SHA-256")
                .digest("room".toByteArray()).joinToString("") { "%02x".format(it) })
            val raw = """{"on":"false","revision":"11111111-1111-1111-1111-111111111111"}"""
            db.data.edit { it[key] = raw }
            val state = db.store.observe("room").first()
            assertEquals(true, state.on); assertTrue(state.pending)
            assertTrue(runCatching { db.store.pending("room") }.isFailure)
            assertTrue(runCatching { db.store.stage("room", false) { true } }.isFailure)
            assertEquals(raw, db.data.data.first()[key])
        } finally { db.close() }
    }
}

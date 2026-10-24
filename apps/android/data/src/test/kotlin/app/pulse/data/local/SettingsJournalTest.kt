package app.pulse.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.settingsFields
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsJournalTest {
    private class Storage {
        val file = File.createTempFile("settings-intent", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        var store = open()
        fun open() = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
        suspend fun reopen() { job.cancelAndJoin(); job = SupervisorJob(); store = open() }
        suspend fun close() { job.cancelAndJoin(); file.delete() }
    }
    @Test fun `old receipt cannot clear ABA intent or overwrite unrelated fields`() = runBlocking {
        val db = Storage()
        try {
            db.store.applyLocal(WirePulsePrefs(density = "cozy"))
            val old = db.store.stageSettings(WirePulsePrefs(readReceipts = false)) { true }
            db.store.stageSettings(WirePulsePrefs(readReceipts = true)) { true }
            val latest = db.store.stageSettings(WirePulsePrefs(readReceipts = false)) { true }
            db.store.acknowledgeSettings(old, old.patch, WirePulsePrefs(readReceipts = false, density = "compact")) { true }
            assertTrue(db.store.hasPendingSettings.first())
            assertEquals(false, db.store.prefs.first().readReceipts)
            assertEquals("cozy", db.store.prefs.first().density)
            assertTrue(db.store.currentSettingsPatch(old).settingsFields().isEmpty())
            db.store.acknowledgeSettings(latest, latest.patch, WirePulsePrefs(readReceipts = false, density = "compact")) { true }
            assertFalse(db.store.hasPendingSettings.first())
            assertEquals("cozy", db.store.prefs.first().density)
        } finally { db.close() }
    }
    @Test fun `unrelated success and server fetch preserve failed intent across reopen`() = runBlocking {
        val db = Storage()
        try {
            db.store.stageSettings(WirePulsePrefs(readReceipts = false)) { true }
            val density = db.store.stageSettings(WirePulsePrefs(density = "compact")) { true }
            db.store.acknowledgeSettings(density, density.patch, WirePulsePrefs(density = "compact", readReceipts = true)) { true }
            db.reopen()
            assertTrue(db.store.hasPendingSettings.first())
            db.store.replaceFromServer(WirePulsePrefs(readReceipts = true, density = "cozy"))
            assertEquals(false, db.store.prefs.first().readReceipts)
            assertEquals("cozy", db.store.prefs.first().density)
            assertEquals(WirePulsePrefs(readReceipts = false), db.store.pendingSettingsPatch())
        } finally { db.close() }
    }
    @Test fun `read begun before acknowledgement cannot roll back a now confirmed choice`() = runBlocking {
        val db = Storage()
        try {
            val intent = db.store.stageSettings(WirePulsePrefs(readReceipts = false)) { true }
            val read = db.store.settingsReadTicket()
            val retry = db.store.pendingSettingsTicket()
            db.store.acknowledgeSettings(intent, intent.patch, WirePulsePrefs(readReceipts = false)) { true }
            db.store.replaceFromServer(WirePulsePrefs(readReceipts = true), read)
            assertFalse(db.store.hasPendingSettings.first())
            assertEquals(false, db.store.prefs.first().readReceipts)
            // Retry captured old tokens; a later user choice is not restaged from
            // the stale retry snapshot, even if values repeat.
            db.store.stageSettings(WirePulsePrefs(readReceipts = true)) { true }
            assertTrue(db.store.currentSettingsPatch(retry).settingsFields().isEmpty())
            assertEquals(true, db.store.pendingSettingsPatch().readReceipts)
        } finally { db.close() }
    }
    @Test fun `missing fields and a retired owner cannot acknowledge intent`() = runBlocking {
        val db = Storage()
        try {
            val ticket = db.store.stageSettings(WirePulsePrefs(typingVisible = false)) { true }
            assertTrue(runCatching { db.store.acknowledgeSettings(ticket, ticket.patch, WirePulsePrefs()) { true } }.isFailure)
            assertTrue(db.store.hasPendingSettings.first())
            assertTrue(runCatching { db.store.acknowledgeSettings(ticket, ticket.patch, WirePulsePrefs(typingVisible = false)) { false } }.isFailure)
            assertTrue(db.store.hasPendingSettings.first())
            assertTrue(runCatching { db.store.stageSettings(WirePulsePrefs(typingVisible = true)) { false } }.isFailure)
            assertEquals(false, db.store.prefs.first().typingVisible)
        } finally { db.close() }
    }
}

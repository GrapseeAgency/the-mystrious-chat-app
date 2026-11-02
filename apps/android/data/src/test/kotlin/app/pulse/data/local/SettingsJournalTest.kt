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
    @Test fun `unloaded and independent account stores do not inherit privacy opt ins`() = runBlocking {
        val first = Storage(); val second = Storage()
        try {
            assertEquals(false, first.store.prefs.first().readReceipts)
            first.store.replaceFromServer(WirePulsePrefs(readReceipts = true, notifPreviews = true))
            assertEquals(true, first.store.prefs.first().readReceipts)
            assertEquals(false, second.store.prefs.first().readReceipts)
            second.store.stageSettings(WirePulsePrefs(density = "compact")) { true }
            second.reopen()
            assertEquals(false, second.store.prefs.first().notifPreviews)
            assertEquals("compact", second.store.prefs.first().density)
            first.reopen()
            assertEquals(true, first.store.prefs.first().readReceipts)
        } finally { first.close(); second.close() }
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

    // ── Section 13 item 1 - the durable receipt contract ──

    @Test fun `superseded receipt adopts the server winner and retires the intent`() = runBlocking {
        val db = Storage()
        try {
            val ticket = db.store.stageSettings(WirePulsePrefs(readReceipts = false, density = "compact")) { true }
            // This device LOST the last-write-wins comparison for readReceipts;
            // the response preferences carry the WINNING value for it.
            val receipt = app.pulse.protocol.SettingsEnvelopeDto(
                preferences = WirePulsePrefs(readReceipts = true, density = "compact"),
                revision = 11,
                applied = listOf("density"),
                superseded = listOf("readReceipts"),
            )
            db.store.acknowledgeSettings(ticket, ticket.patch, receipt.preferences!!, receipt) { true }
            // The losing local intent ADOPTED server truth and cannot resurface.
            assertEquals(true, db.store.prefs.first().readReceipts)
            assertEquals("compact", db.store.prefs.first().density)
            assertFalse(db.store.hasPendingSettings.first())
            assertEquals(WirePulsePrefs(), db.store.pendingSettingsPatch())
            assertEquals(11L, db.store.storedServerRevision())
            db.reopen()
            assertEquals(true, db.store.prefs.first().readReceipts)
            assertFalse(db.store.hasPendingSettings.first())
            // The retirement is the guarantee: the losing intent never resurfaces
            // as a pending write (a later authoritative snapshot may of course
            // carry a newer server value - that is ordinary server truth, and it
            // arrives through the same replaceFromServer path, not the intent).
            db.store.replaceFromServer(WirePulsePrefs(readReceipts = false))
            assertEquals(WirePulsePrefs(), db.store.pendingSettingsPatch())
            assertEquals(false, db.store.prefs.first().readReceipts)
        } finally { db.close() }
    }

    @Test fun `replayed receipt confirms like a fresh apply`() = runBlocking {
        val db = Storage()
        try {
            val ticket = db.store.stageSettings(WirePulsePrefs(typingVisible = false)) { true }
            val receipt = app.pulse.protocol.SettingsEnvelopeDto(
                preferences = WirePulsePrefs(typingVisible = false),
                revision = 3,
                applied = listOf("typingVisible"),
                replayed = true,
            )
            db.store.acknowledgeSettings(ticket, ticket.patch, receipt.preferences!!, receipt) { true }
            assertFalse(db.store.hasPendingSettings.first())
            assertEquals(false, db.store.prefs.first().typingVisible)
            assertEquals(3L, db.store.storedServerRevision())
        } finally { db.close() }
    }

    @Test fun `receipt identity replays the same clientId for the same instructions`() = runBlocking {
        val db = Storage()
        try {
            val ticket = db.store.stageSettings(WirePulsePrefs(typingVisible = false)) { true }
            val first = db.store.settingsReceiptIdentity(ticket.fields.keys)
            // The stage minted the durable identity: the SAME clientId and the
            // stage moment ride the retry (a lost ACK then gets the server's
            // stored result with replayed=true, never a mismatch 409).
            assertEquals(ticket.clientId, first.clientId)
            assertEquals(ticket.stagedAtMs, first.intentAtMs)
            assertTrue(first.clientId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            assertEquals(ticket.fields.keys, first.fieldTimesMs.keys)
            assertEquals(ticket.stagedAtMs, first.fieldTimesMs.values.single())
            val again = db.store.settingsReceiptIdentity(ticket.fields.keys)
            assertEquals(first.clientId, again.clientId)
            assertEquals(first.intentAtMs, again.intentAtMs)
            // DIFFERENT instructions mint a FRESH identity, so a reused clientId
            // can never carry different instructions (mismatch 409 unreachable).
            val other = db.store.settingsReceiptIdentity(setOf("density"))
            assertTrue(other.clientId != first.clientId)
            assertTrue(other.clientId != ticket.clientId)
            assertTrue(other.fieldTimesMs.keys.single() == "density")
            // An empty send never touches the stored identity.
            val emptyOne = db.store.settingsReceiptIdentity(emptySet())
            val emptyTwo = db.store.settingsReceiptIdentity(emptySet())
            assertTrue(emptyOne.clientId != emptyTwo.clientId)
        } finally { db.close() }
    }
}

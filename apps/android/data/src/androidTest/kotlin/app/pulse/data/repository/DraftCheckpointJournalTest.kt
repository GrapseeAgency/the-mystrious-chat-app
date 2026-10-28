package app.pulse.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftCheckpointJournalTest {
    @Test fun failedSQLiteDraftSurvivesNewRepositoryAndQueueConsumptionCannotReviveIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldGateway = PulseEndpoints.gatewayHttpUrl; val oldSocket = PulseEndpoints.socketUrl
        val http = HttpClient()
        val persistence = object : SessionTokenPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
            override suspend fun clear() {}
        }
        val cipher = object : SessionTokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decryptOrNull(stored: String) = stored
        }
        PulseEndpoints.applyBase(null)
        val owner = "checkpoint-${UUID.randomUUID()}"
        val key = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), owner)
        val directory = File(context.noBackupFilesDir, "draft-checkpoints/pulse-account-$key.db")
        fun repository() = AccountRepositories(context, http, SessionTokenStore(persistence, cipher)).repository.also { it.start(owner) }
        val first = repository()
        var connection: android.database.sqlite.SQLiteDatabase? = null
        try {
            withTimeout(30_000) {
                first.saveDraft("room", "older disk text")
                connection = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("pulse-account-$key.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                connection!!.execSQL("CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'SQLite cannot commit'); END")
                assertTrue(runCatching { first.saveDraft("room", "last keystrokes") }.isFailure)
                val checkpoint = checkNotNull(DraftCheckpointJournal(directory).read("room"))
                assertEquals("last keystrokes", checkpoint.text)
                first.stop()
                val next = repository()
                try {
                    assertEquals("last keystrokes", next.observeDraft("room").first())
                    assertTrue(next.refreshComposerDraft("room", null).isSuccess)
                    connection!!.execSQL("DROP TRIGGER fail_draft")
                    val token = checkNotNull(next.captureDraftToken("room"))
                    assertTrue(next.sendMessage("room", "last keystrokes", draftToken=token).isSuccess)
                    // Clear committed with the UUID receipt, but file still exists.
                    assertEquals(checkpoint.id, DraftCheckpointJournal(directory).read("room")?.id)
                    val afterSend = repository()
                    try {
                        assertEquals("", afterSend.observeDraft("room").first())
                        DraftCheckpointJournal(directory).write("file-only", "recover in manager")
                        assertEquals("recover in manager", afterSend.observeDrafts().first()["file-only"])
                        afterSend.clearAllDrafts()
                        assertEquals("", afterSend.observeDraft("file-only").first())
                        assertNull(DraftCheckpointJournal(directory).read("file-only"))
                    }
                    finally { afterSend.stop() }
                } finally { next.stop() }
            }
        } finally {
            first.stop(); connection?.close(); http.close(); directory.deleteRecursively()
            PulseEndpoints.gatewayHttpUrl = oldGateway; PulseEndpoints.socketUrl = oldSocket
        }
    }

    @Test fun obsoleteCheckpointRemovalCannotDeleteNewerTextOrAnExplicitClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "checkpoint-${UUID.randomUUID()}")
        try {
            val journal = DraftCheckpointJournal(directory)
            val old = checkNotNull(journal.write("room", "old"))
            val clear = checkNotNull(journal.write("room", ""))
            journal.remove("room", old.id)
            assertEquals("", DraftCheckpointJournal(directory).read("room")?.text)
            journal.remove("room", clear.id)
            assertNull(journal.read("room"))
        } finally { directory.deleteRecursively() }
    }
}

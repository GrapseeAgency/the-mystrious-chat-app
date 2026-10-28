package app.pulse.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftCheckpointJournalTest {
    /** Fresh Room handles model lost process buffers; a single fixture-owned
     * DataStore avoids illegally constructing two writers for one preferences file. */
    private class Repositories(val context: Context, val http: HttpClient, val owner: String, val tokens: SessionTokenStore) {
        private val preferencesScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        private val preferencesFile = File(context.filesDir, "checkpoint-test-${UUID.randomUUID()}.preferences_pb")
        private val preferences = PulsePrefsLocalStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=preferencesScope) { preferencesFile })
        private val handles = mutableListOf<PulseDatabase>()
        private val repositories = mutableListOf<PulseRepositoryImpl>()
        fun open(): PulseRepositoryImpl {
            val key = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), owner)
            val db = androidx.room.Room.databaseBuilder(context, PulseDatabase::class.java, "pulse-account-$key.db").build()
            handles += db
            return PulseRepositoryImpl(app.pulse.data.remote.PulseApi(http, bearerToken={ null }, gateway={ "" }),
                db,db.conversationDao(),db.messageDao(),db.outboxDao(),db.draftDao(),db.topicDao(),db.savedDao(),
                db.callLogDao(),db.storyDao(),db.wave7Dao(),
                app.pulse.data.remote.PulseSocketClient("", tokenProvider={ null }, allowEndpointFallback=false),
                preferences,tokens,context,reminderOwner=app.pulse.domain.notify.ReminderOwner(owner,AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl)))
                .also { repositories += it; it.start(owner) }
        }
        fun close() {
            repositories.forEach { it.stop() }
            preferencesScope.cancel()
            handles.forEach { it.close() }
        }
    }
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
        val fixtures = Repositories(context,http,owner,SessionTokenStore(persistence,cipher))
        fun repository() = fixtures.open()
        val first = repository()
        var connection: android.database.sqlite.SQLiteDatabase? = null
        try {
            withTimeout(30_000) {
                first.saveDraft("manager", "same text")
                val staleManagerToken = checkNotNull(first.captureDraftToken("manager"))
                first.saveDraft("manager", "different text")
                first.saveDraft("manager", "same text")
                assertFalse(first.clearDraftIfUnchanged(staleManagerToken, "same text"))
                val currentManagerToken = checkNotNull(first.captureDraftToken("manager"))
                assertFalse(first.clearDraftIfUnchanged(currentManagerToken.copy(ownerId="another-owner"), "same text"))
                assertFalse(first.clearDraftIfUnchanged(currentManagerToken, "stale displayed text"))
                assertEquals("same text", first.observeDraft("manager").first())
                assertTrue(first.clearDraftIfUnchanged(currentManagerToken, "same text"))
                assertEquals("", first.observeDraft("manager").first())
                first.saveDraft("room", "older disk text")
                connection = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("pulse-account-$key.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                connection!!.execSQL("CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'SQLite cannot commit'); END")
                assertTrue(runCatching { first.saveDraft("room", "last keystrokes") }.isFailure)
                val checkpoint = checkNotNull(DraftCheckpointJournal(directory).read("room"))
                assertEquals("last keystrokes", checkpoint.text)
                val failedClearToken = checkNotNull(first.captureDraftToken("room"))
                assertTrue(runCatching { first.clearDraftIfUnchanged(failedClearToken, "last keystrokes") }.isFailure)
                assertEquals("last keystrokes", first.observeDraft("room").first())
                assertEquals(failedClearToken, first.captureDraftToken("room"))
                first.stop()
                val next = repository()
                try {
                    assertEquals("last keystrokes", next.observeDraft("room").first())
                    val restored = next.observeDraftSnapshot("room").first()
                    assertEquals("last keystrokes", restored.text)
                    assertEquals(next.captureDraftToken("room")?.revision, restored.revision)
                    assertTrue(next.refreshComposerDraft("room", null).isSuccess)
                    connection!!.execSQL("DROP TRIGGER fail_draft")
                    val token = checkNotNull(next.captureDraftToken("room"))
                    assertTrue(next.sendMessage("room", "last keystrokes", draftToken=token).isSuccess)
                    // Simulate a crash leaving the old file even if background cleanup ran.
                    val name = java.security.MessageDigest.getInstance("SHA-256").digest("room".toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
                    File(directory,name).writeText(app.pulse.protocol.PulseJson.encodeToString(DraftCheckpointJournal.Snapshot.serializer(),checkpoint))
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
            first.stop(); connection?.close(); fixtures.close(); http.close(); directory.deleteRecursively()
            PulseEndpoints.gatewayHttpUrl = oldGateway; PulseEndpoints.socketUrl = oldSocket
        }
    }

    @Test fun editCheckpointKeepsOriginalBaselineAfterLosingTheProcessBuffer() = runBlocking {
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
        val owner = "edit-checkpoint-${UUID.randomUUID()}"
        val key = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), owner)
        val name = "pulse-account-$key.db"
        val directory = File(context.noBackupFilesDir, "draft-checkpoints/$name")
        val fixtures = Repositories(context,http,owner,SessionTokenStore(persistence,cipher))
        fun repository() = fixtures.open()
        val first = repository()
        val database = androidx.room.Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        try {
            withTimeout(30_000) {
                val message = app.pulse.domain.model.Message(id="editable", conversationId="room", authorId=owner,
                    authorName="Sender", kind=app.pulse.domain.model.Message.Kind.TEXT, body="original", createdAt="2026-10-28T00:00:00Z")
                database.messageDao().upsertAll(listOf(MessageEntity.from(message)))
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_edit BEFORE INSERT ON wave7_cache WHEN NEW.key LIKE 'composer-local-edit:%' BEGIN SELECT RAISE(ABORT, 'SQLite cannot commit'); END")
                val failed = async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    first.events().first { it is app.pulse.domain.repository.PulseEvent.ComposerSaveFailed && it.messageId == message.id }
                }
                first.stageEditDraft(message,"my retained edit")
                failed.await()
                assertNotNull(DraftCheckpointJournal(directory).read("composer-local-edit:v1|$owner|editable"))
                first.stop()
                val reopened = repository()
                try {
                    assertEquals("my retained edit", reopened.recoveredEditDraft(message.id))
                    val remote = message.copy(body="new remote text")
                    database.messageDao().upsertAll(listOf(MessageEntity.from(remote)))
                    val failedAgain = async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        reopened.events().first { it is app.pulse.domain.repository.PulseEvent.ComposerSaveFailed && it.messageId == message.id }
                    }
                    reopened.stageEditDraft(remote,"my next edit")
                    failedAgain.await()
                    database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit")
                    val recoveredRow = reopened.composerRecoveryPage(null).getOrThrow().items.single { it.kind == "Unsubmitted edit" }
                    assertEquals("my next edit", recoveredRow.text)
                    assertTrue(database.wave7Dao().readJournal("composer-intent:v1|$owner|").isEmpty())
                    // Offline edit reports unconfirmed but must retain the original baseline,
                    // including the live buffer after another failed write on remote text.
                    assertTrue(reopened.editMessage(message.id,"my next edit").isFailure)
                    val rows = database.wave7Dao().readJournal("composer-intent:v1|$owner|")
                    val intent = rows.map { app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.ComposerIntentDto.serializer(),it.json) }.single { it.kind == "edit" }
                    assertEquals("original", intent.baseText)
                    assertEquals("my next edit", intent.text)
                    assertEquals("my next edit", reopened.recoveredEditDraft(message.id))
                } finally { reopened.stop() }
            }
        } finally {
            first.stop(); database.close(); fixtures.close(); http.close(); directory.deleteRecursively()
            PulseEndpoints.gatewayHttpUrl = oldGateway; PulseEndpoints.socketUrl = oldSocket
        }
    }

    @Test fun cleanupPagesAreBoundedAndRetainCorruptAndUnconsumedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "checkpoint-${UUID.randomUUID()}")
        try {
            val journal = DraftCheckpointJournal(directory)
            repeat(105) { journal.write("room-$it", "retained") }
            File(directory,"corrupt.json").writeText("unreadable recovery")
            val first = journal.page("")
            assertTrue(first.snapshots.size <= 100)
            assertTrue(first.next.isNotEmpty())
            val second = DraftCheckpointJournal(directory).page(first.next)
            assertEquals(105, first.snapshots.size + second.snapshots.size)
            assertEquals("", second.next)
            assertEquals(106, directory.listFiles()!!.size)
            assertEquals("unreadable recovery", File(directory,"corrupt.json").readText())
        } finally { directory.deleteRecursively() }
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

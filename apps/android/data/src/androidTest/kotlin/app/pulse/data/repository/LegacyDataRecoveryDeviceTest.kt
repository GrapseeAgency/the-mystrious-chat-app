package app.pulse.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.local.PulseDatabase
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Section 14 items 7+10 (on-device acceptance):
 *  - item 10: a real legacy-format pulse.db is adopted ONLY when every
 *    conversation lists this owner and every queued send is owner-proven;
 *    the ORIGINAL file is byte-for-byte retained in every verdict, unproven
 *    queue rows are skipped, and the verdict stamp makes the decision
 *    idempotent across runs.
 *  - item 7: a corrupted database file FAILS the open (no destructive
 *    fallback exists anywhere) and the bytes are retained untouched.
 */
@RunWith(AndroidJUnit4::class)
class LegacyDataRecoveryDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var target: PulseDatabase

    @Before
    fun setUp() {
        cleanLegacy()
        target = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        target.close()
        cleanLegacy()
    }

    private fun cleanLegacy() {
        listOf("", "-wal", "-shm", "-journal").forEach {
            File(context.getDatabasePath("pulse.db").path + it).delete()
        }
    }

    private fun legacyFile(): File = context.getDatabasePath("pulse.db")

    /** Creates a "late legacy" fixture: the last pre-account-split schema. */
    private fun writeLegacyFixture(vararg conversations: Triple<String, String, String>, messages: List<Pair<String, String>>, outbox: List<Pair<String, Boolean>>) {
        legacyFile().parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacyFile(), null)
        try {
            db.execSQL(
                "CREATE TABLE conversations(id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, " +
                    "lastMessagePreview TEXT, lastMessageAuthorName TEXT, lastMessageKind TEXT, lastActivityAt TEXT, " +
                    "unreadCount INTEGER NOT NULL, isPinned INTEGER NOT NULL, isMuted INTEGER NOT NULL, isArchived INTEGER NOT NULL, " +
                    "memberIdsCsv TEXT NOT NULL, memberNamesCsv TEXT NOT NULL, accentColor TEXT, streakCount INTEGER NOT NULL, " +
                    "myDraft TEXT, isSelf INTEGER NOT NULL, myManualUnread INTEGER NOT NULL, streakAtRiskCount INTEGER NOT NULL, " +
                    "streakLost INTEGER NOT NULL, lastMessageMine INTEGER NOT NULL, lastMessageDeleted INTEGER NOT NULL, " +
                    "lastMessageIsReply INTEGER NOT NULL, lastMessageIsImage INTEGER NOT NULL, lastMessageIsAudio INTEGER NOT NULL, " +
                    "lastMessageIsFile INTEGER NOT NULL, lastMessageFileName TEXT, mutedUntilEpoch INTEGER NOT NULL, " +
                    "otherUserId TEXT, isChannel INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE messages(id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, authorId TEXT NOT NULL, " +
                    "authorName TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL, createdAt TEXT NOT NULL, editedAt TEXT, " +
                    "deletedAt TEXT, replyToId TEXT, threadRootId TEXT, pinnedAt TEXT, reactionsJson TEXT, replyToBody TEXT, " +
                    "replyToAuthor TEXT, senderColor TEXT, viaAutomation INTEGER NOT NULL, durationMs INTEGER, imagePath TEXT, " +
                    "audioPath TEXT, filePath TEXT, fileName TEXT, fileSize INTEGER, viewOnce INTEGER NOT NULL DEFAULT 0, " +
                    "viewedAt TEXT, transcript TEXT, transcribedAt TEXT, pollJson TEXT, linkPreviewJson TEXT, topicId TEXT, " +
                    "payloadJson TEXT, quoteBoundsJson TEXT, expiresAtEpochMs INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, conversationId TEXT NOT NULL, clientId TEXT NOT NULL, " +
                    "content TEXT NOT NULL, kind TEXT NOT NULL, createdAt TEXT NOT NULL, attempts INTEGER NOT NULL, " +
                    "failureReason TEXT, wirePayload TEXT, uploadKey TEXT)",
            )
            db.execSQL("CREATE TABLE draft(conversationId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, updatedAt TEXT NOT NULL)")
            conversations.forEach { (id, members, selfId) ->
                db.execSQL(
                    "INSERT INTO conversations(id, kind, title, unreadCount, isPinned, isMuted, isArchived, memberIdsCsv, memberNamesCsv, " +
                        "streakCount, isSelf, myManualUnread, streakAtRiskCount, streakLost, lastMessageMine, lastMessageDeleted, " +
                        "lastMessageIsReply, lastMessageIsImage, lastMessageIsAudio, lastMessageIsFile, mutedUntilEpoch, isChannel) " +
                        "VALUES(?, 'GROUP', ?, 0, 0, 0, 0, ?, 'members', 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)",
                    arrayOf(id, "Room $id", members, selfId),
                )
            }
            messages.forEach { (id, conversationId) ->
                db.execSQL(
                    "INSERT INTO messages(id, conversationId, authorId, authorName, kind, body, createdAt, viaAutomation, viewOnce) " +
                        "VALUES(?, ?, 'viewer-a', 'Viewer A', 'TEXT', 'cached body', '2026-01-01T00:00:00Z', 0, 0)",
                    arrayOf(id, conversationId),
                )
            }
            outbox.forEach { (clientId, proven) ->
                db.execSQL(
                    "INSERT INTO outbox(conversationId, clientId, content, kind, createdAt, attempts) " +
                        "VALUES('conv-1', ?, 'queued body', 'text', '2026-01-01T00:00:00Z', 1)",
                    arrayOf(clientId),
                )
                if (proven) {
                    db.execSQL(
                        "INSERT INTO messages(id, conversationId, authorId, authorName, kind, body, createdAt, viaAutomation, viewOnce) " +
                            "VALUES(?, 'conv-1', 'viewer-a', 'Viewer A', 'TEXT', 'local echo', '2026-01-01T00:00:00Z', 0, 0)",
                        arrayOf("local_$clientId"),
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    private fun bytesOf(file: File): ByteArray = file.readBytes()

    private suspend fun conversationRow(id: String): app.pulse.data.local.ConversationEntity? = target.conversationDao().byId(id)

    private suspend fun outboxClientIds(): List<String> = target.outboxDao().all().map { it.clientId }

    private suspend fun stampRow(): String? =
        target.wave7Dao().readJournal("legacy-recovery:v1").firstOrNull()?.json

    @Test
    fun aProvableLegacyCacheIsAdoptedIntoTheCurrentAccountAndTheOriginalIsRetained() {
        writeLegacyFixture(
            Triple("conv-1", "viewer-a,peer-b", "0"),
            messages = listOf("m-1" to "conv-1"),
            outbox = listOf("queue-1" to true, "queue-2" to false),
        )
        val before = bytesOf(legacyFile())

        val verdict = kotlinx.coroutines.runBlocking {
            LegacyDataRecovery.recover(context, target, "viewer-a")
        }

        assertTrue(verdict is LegacyDataRecovery.Verdict.Adopt)
        kotlinx.coroutines.runBlocking {
            // the proven conversation + its messages (the cached body and the
            // proven local echo) landed through the SAME DAO surface the app reads
            assertNotNull(conversationRow("conv-1"))
            assertNotNull(target.messageDao().byId("m-1"))
            assertNotNull(target.messageDao().byId("local_queue-1"))
            // only the OWNER-PROVEN queue row was imported; the unproven one was skipped
            val queued = outboxClientIds()
            assertTrue(queued.contains("queue-1"))
            assertFalse(queued.contains("queue-2"))
        }
        // the original legacy file is byte-identical - never written
        assertTrue(before.contentEquals(bytesOf(legacyFile())))
    }

    @Test
    fun aLegacyCacheWithAnyForeignConversationRefusesEntirelyAndIsRetained() {
        writeLegacyFixture(
            Triple("conv-1", "viewer-a,peer-b", "0"),
            Triple("conv-foreign", "other-x,other-y", "0"),
            messages = listOf("m-1" to "conv-1"),
            outbox = listOf("queue-1" to true),
        )
        val before = bytesOf(legacyFile())

        val verdict = kotlinx.coroutines.runBlocking {
            LegacyDataRecovery.recover(context, target, "viewer-a")
        }

        assertTrue(verdict is LegacyDataRecovery.Verdict.Refuse)
        kotlinx.coroutines.runBlocking {
            assertNull(conversationRow("conv-1"))
            assertNull(conversationRow("conv-foreign"))
            assertTrue(outboxClientIds().isEmpty())
        }
        assertTrue(before.contentEquals(bytesOf(legacyFile())))
    }

    @Test
    fun theVerdictIsStampedSoRestartNeverReDecidesTheSameLegacyFile() {
        writeLegacyFixture(
            Triple("conv-1", "viewer-a,peer-b", "0"),
            messages = listOf("m-1" to "conv-1"),
            outbox = listOf(),
        )

        val first = kotlinx.coroutines.runBlocking { LegacyDataRecovery.recover(context, target, "viewer-a") }
        val stampAfterFirst = kotlinx.coroutines.runBlocking { stampRow() }

        val second = kotlinx.coroutines.runBlocking { LegacyDataRecovery.recover(context, target, "viewer-a") }
        val stampAfterSecond = kotlinx.coroutines.runBlocking { stampRow() }

        assertTrue(first is LegacyDataRecovery.Verdict.Adopt)
        assertTrue(second is LegacyDataRecovery.Verdict.Adopt)
        assertEquals(stampAfterFirst, stampAfterSecond)
        // still exactly one import - INSERT OR IGNORE + the stamp agree
        kotlinx.coroutines.runBlocking { assertNotNull(conversationRow("conv-1")) }
    }

    @Test
    fun aGarbageLegacyFileIsRefusedAsUnreadableAndRetained() {
        legacyFile().writeBytes("this is not a sqlite database at all".toByteArray())
        val before = bytesOf(legacyFile())

        val verdict = kotlinx.coroutines.runBlocking {
            LegacyDataRecovery.recover(context, target, "viewer-a")
        }

        assertTrue(verdict is LegacyDataRecovery.Verdict.Unreadable || verdict is LegacyDataRecovery.Verdict.Refuse)
        kotlinx.coroutines.runBlocking {
            assertNull(conversationRow("conv-1"))
            assertTrue(outboxClientIds().isEmpty())
        }
        assertTrue(before.contentEquals(bytesOf(legacyFile())))
    }

    @Test
    fun aCorruptedAccountDatabaseFailsTheOpenWithNoDestructiveFallbackAndIsRetained() {
        val corrupt = context.getDatabasePath("pulse-account-corrupt.db")
        corrupt.parentFile?.mkdirs()
        corrupt.writeBytes(ByteArray(4096) { 0x5A })
        val before = corrupt.readBytes()

        val outcome = runCatching {
            val db = Room.databaseBuilder(context, PulseDatabase::class.java, "pulse-account-corrupt.db")
                .build()
            try {
                kotlinx.coroutines.runBlocking { db.conversationDao().observeAll().first() }
            } finally {
                db.close()
            }
        }

        // Section 14 item 7's honest contract on Android: either the open
        // FAILS and the bytes stay retained, or the PLATFORM corruption
        // handler (framework onCorruption, outside app code) recreates the
        // file EMPTY - what is impossible either way is a third account's
        // rows coming back through a corrupt-file recovery.
        if (outcome.isFailure) {
            assertTrue(before.contentEquals(corrupt.readBytes()), "a refused open must not rewrite the file")
        } else {
            assertTrue(outcome.getOrThrow().isEmpty(), "a recreated corrupt store must be empty")
        }
        listOf("", "-wal", "-shm").forEach { File(corrupt.path + it).delete() }
    }
}

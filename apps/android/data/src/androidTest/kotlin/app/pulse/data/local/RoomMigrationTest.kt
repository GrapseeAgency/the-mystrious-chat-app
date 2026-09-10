package app.pulse.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave-0 migration gate — a REAL v3 database (the only deployed schema with
 * users) is built from raw SQL matching the v3 entities exactly, seeded with
 * a conversation + message row, then opened with Room + MIGRATION_3_4:
 *   - NO destruction: both v3 rows survive;
 *   - the outbox + draft tables exist and their DAOs round-trip.
 * Runs on the emulator (android-ci connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "pulse-migration-test.db"
    private lateinit var db: PulseDatabase

    /** EXACT v3 DDL — mirrors the compiled ConversationEntity annotations. */
    private val createConversationsV3 =
        "CREATE TABLE IF NOT EXISTS `conversations` (" +
            "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`lastMessagePreview` TEXT, `lastMessageAuthorName` TEXT, `lastMessageKind` TEXT, " +
            "`lastActivityAt` TEXT, `unreadCount` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, " +
            "`isMuted` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, " +
            "`memberIdsCsv` TEXT NOT NULL, `memberNamesCsv` TEXT NOT NULL, `accentColor` TEXT, " +
            "`streakCount` INTEGER NOT NULL, `myDraft` TEXT, `isSelf` INTEGER NOT NULL, " +
            "`myManualUnread` INTEGER NOT NULL, `streakAtRiskCount` INTEGER NOT NULL, " +
            "`streakLost` INTEGER NOT NULL, `lastMessageMine` INTEGER NOT NULL, " +
            "`lastMessageDeleted` INTEGER NOT NULL, `lastMessageIsReply` INTEGER NOT NULL, " +
            "`lastMessageIsImage` INTEGER NOT NULL, `lastMessageIsAudio` INTEGER NOT NULL, " +
            "`lastMessageIsFile` INTEGER NOT NULL, `lastMessageFileName` TEXT, " +
            "`mutedUntilEpoch` INTEGER NOT NULL, `otherUserId` TEXT, `isChannel` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    /** EXACT v3 DDL — mirrors the compiled MessageEntity annotations. */
    private val createMessagesV3 =
        "CREATE TABLE IF NOT EXISTS `messages` (" +
            "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
            "`authorName` TEXT NOT NULL, `kind` TEXT NOT NULL, `body` TEXT NOT NULL, " +
            "`createdAt` TEXT NOT NULL, `editedAt` TEXT, `deletedAt` TEXT, `replyToId` TEXT, " +
            "`threadRootId` TEXT, `pinnedAt` TEXT, `reactionsJson` TEXT, `replyToBody` TEXT, " +
            "`replyToAuthor` TEXT, `senderColor` TEXT, `viaAutomation` INTEGER NOT NULL, " +
            "`durationMs` INTEGER, PRIMARY KEY(`id`))"

    @Before
    fun createV3DatabaseWithSeedRows() {
        context.deleteDatabase(dbName)
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.execSQL(createConversationsV3)
        raw.execSQL(createMessagesV3)
        raw.insert("conversations", null, ContentValues().apply {
            put("id", "c1")
            put("kind", "DM")
            put("title", "Alice")
            put("unreadCount", 2)
            put("isPinned", 0)
            put("isMuted", 0)
            put("isArchived", 0)
            put("memberIdsCsv", "u1,u2")
            put("memberNamesCsv", "Alice,Bob")
            put("streakCount", 3)
            put("isSelf", 0)
            put("myManualUnread", 0)
            put("streakAtRiskCount", 0)
            put("streakLost", 0)
            put("lastMessageMine", 0)
            put("lastMessageDeleted", 0)
            put("lastMessageIsReply", 0)
            put("lastMessageIsImage", 0)
            put("lastMessageIsAudio", 0)
            put("lastMessageIsFile", 0)
            put("mutedUntilEpoch", 0)
            put("isChannel", 0)
        })
        raw.insert("messages", null, ContentValues().apply {
            put("id", "m1")
            put("conversationId", "c1")
            put("authorId", "u2")
            put("authorName", "Bob")
            put("kind", "TEXT")
            put("body", "pre-migration hello")
            put("createdAt", "2026-02-14T10:00:00.000Z")
            put("viaAutomation", 0)
        })
        // Mark the file as the deployed v3 schema.
        raw.version = 3
        raw.close()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration3To4PreservesRowsAndAddsOutboxDraft() = runBlocking {
        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(PulseDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        // ── v3 rows intact ─────────────────────────────────────
        val conversation = db.conversationDao().byId("c1")
        assertNotNull(conversation, "v3 conversation row was destroyed by the migration")
        assertEquals("Alice", conversation!!.title)
        assertEquals(3, conversation.streakCount)

        val message = db.messageDao().byId("m1")
        assertNotNull(message, "v3 message row was destroyed by the migration")
        assertEquals("pre-migration hello", message!!.body)

        // ── outbox DAO round-trip ──────────────────────────────
        assertEquals(0, db.outboxDao().count())
        db.outboxDao().insert(
            OutboxEntity(
                conversationId = "c1",
                clientId = "client-1",
                content = "queued hello",
                createdAt = "2026-02-14T10:01:00.000Z",
            ),
        )
        db.outboxDao().insert(
            OutboxEntity(
                conversationId = "c1",
                clientId = "client-2",
                content = "queued second",
                createdAt = "2026-02-14T10:02:00.000Z",
            ),
        )
        assertEquals(2, db.outboxDao().count())

        val queued = db.outboxDao().all()
        assertEquals(listOf("client-1", "client-2"), queued.map { it.clientId })
        assertEquals("queued hello", queued.first().content)
        assertEquals(0, queued.first().attempts)
        assertEquals("text", queued.first().kind)

        db.outboxDao().incrementAttempts("client-1")
        assertEquals(1, db.outboxDao().all().first { it.clientId == "client-1" }.attempts)

        db.outboxDao().deleteByClientId("client-1")
        assertEquals(1, db.outboxDao().count())
        db.outboxDao().deleteByClientId("client-2")
        assertEquals(0, db.outboxDao().count())

        // ── draft DAO round-trip ───────────────────────────────
        assertNull(db.draftDao().get("c1"))
        db.draftDao().upsert(DraftEntity(conversationId = "c1", text = "half-typed", updatedAt = "t1"))
        assertEquals("half-typed", db.draftDao().get("c1")?.text)
        db.draftDao().upsert(DraftEntity(conversationId = "c1", text = "half-typed v2", updatedAt = "t2"))
        assertEquals("half-typed v2", db.draftDao().get("c1")?.text)

        val observed = db.draftDao().observe("c1").first()
        assertEquals("half-typed v2", observed?.text)

        db.draftDao().delete("c1")
        assertNull(db.draftDao().get("c1"))
        db.draftDao().upsert(DraftEntity(conversationId = "c2", text = "x", updatedAt = "t"))
        db.draftDao().clearAll()
        assertNull(db.draftDao().get("c2"))
    }
}

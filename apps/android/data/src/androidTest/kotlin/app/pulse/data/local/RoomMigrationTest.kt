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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave-0 + Wave-1 migration gate — REAL databases are built from raw SQL
 * matching the deployed schema exactly, seeded, then opened with Room:
 *   - v3 → MIGRATION_3_4: rows survive; outbox + draft DAOs round-trip;
 *   - v4 → MIGRATION_4_5: rows survive; message media columns + membersJson
 *     are usable (DAO round-trip) and old rows backfill with defaults.
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
    fun resetDbFile() {
        context.deleteDatabase(dbName)
    }

    private fun createV3DatabaseWithSeedRows() {
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

    /** EXACT v4 DDL — v4 = v3 tables + the Wave-0 outbox/draft tables. */
    private fun createV4DatabaseWithSeedRows() {
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.execSQL(createConversationsV3)
        raw.execSQL(createMessagesV3)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`conversationId` TEXT NOT NULL, `clientId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `attempts` INTEGER NOT NULL)",
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_conversationId` ON `outbox` (`conversationId`)")
        raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_clientId` ON `outbox` (`clientId`)")
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `draft` (`conversationId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`updatedAt` TEXT NOT NULL, PRIMARY KEY(`conversationId`))",
        )
        raw.insert("conversations", null, ContentValues().apply {
            put("id", "c1")
            put("kind", "GROUP")
            put("title", "Wave Crew")
            put("unreadCount", 1)
            put("isPinned", 0)
            put("isMuted", 0)
            put("isArchived", 0)
            put("memberIdsCsv", "u1,u2")
            put("memberNamesCsv", "Alice,Bob")
            put("streakCount", 1)
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
            put("id", "m-old")
            put("conversationId", "c1")
            put("authorId", "u2")
            put("authorName", "Bob")
            put("kind", "TEXT")
            put("body", "pre-v5 text row")
            put("createdAt", "2026-02-14T10:00:00.000Z")
            put("viaAutomation", 0)
        })
        raw.insert("outbox", null, ContentValues().apply {
            put("conversationId", "c1")
            put("clientId", "client-old")
            put("content", "queued pre-v5")
            put("kind", "text")
            put("createdAt", "2026-02-14T10:01:00.000Z")
            put("attempts", 0)
        })
        raw.insert("draft", null, ContentValues().apply {
            put("conversationId", "c1")
            put("text", "half-typed pre-v5")
            put("updatedAt", "t1")
        })
        // Mark the file as the deployed v4 schema.
        raw.version = 4
        raw.close()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration3To4PreservesRowsAndAddsOutboxDraft() = runBlocking {
        createV3DatabaseWithSeedRows()
        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(PulseDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        // ── v3 rows intact ─────────────────────────────────────
        val conversation = db.conversationDao().byId("c1")
        assertNotNull("v3 conversation row was destroyed by the migration", conversation)
        assertEquals("Alice", conversation!!.title)
        assertEquals(3, conversation.streakCount)

        val message = db.messageDao().byId("m1")
        assertNotNull("v3 message row was destroyed by the migration", message)
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

    /**
     * Wave-1 gate: a REAL v4 database (conversations/messages/outbox/draft)
     * migrates to v5 without destruction — old rows survive with the new
     * columns backfilled (viewOnce=false, members="[]"), and the new media
     * columns + membersJson are round-trippable through the DAOs.
     */
    @Test
    fun migration4To5PreservesRowsAndAddsMediaAndMembers() = runBlocking {
        createV4DatabaseWithSeedRows()
        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(PulseDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        // ── v4 rows intact + defaults backfilled ───────────────
        val conversation = db.conversationDao().byId("c1")
        assertNotNull("v4 conversation row was destroyed by the migration", conversation)
        assertEquals("Wave Crew", conversation!!.title)
        assertEquals("[]", conversation.membersJson) // ADD COLUMN DEFAULT '[]'

        val oldMessage = db.messageDao().byId("m-old")
        assertNotNull("v4 message row was destroyed by the migration", oldMessage)
        assertEquals("pre-v5 text row", oldMessage!!.body)
        assertNull(oldMessage.imagePath) // nullable media columns start empty
        assertEquals(false, oldMessage.viewOnce) // NOT NULL DEFAULT 0 backfill

        // Wave-0 surfaces still work after the v5 migration.
        assertEquals(1, db.outboxDao().count())
        assertEquals("queued pre-v5", db.outboxDao().all().first().content)
        assertEquals("half-typed pre-v5", db.draftDao().get("c1")?.text)

        // ── media columns round-trip through the DAO ───────────
        val media = MessageEntity(
            id = "m-media",
            conversationId = "c1",
            authorId = "u1",
            authorName = "Alice",
            kind = "IMAGE",
            body = "captions ride content",
            createdAt = "2026-02-14T10:02:00.000Z",
            editedAt = null,
            deletedAt = null,
            replyToId = null,
            threadRootId = "m-old",
            pinnedAt = null,
            reactionsJson = null,
            replyToBody = null,
            replyToAuthor = null,
            senderColor = "emerald",
            viaAutomation = false,
            durationMs = 1500L,
            imagePath = "9c1f...e7.jpg",
            audioPath = "voice-1.m4a",
            filePath = "/api/uploads/report.pdf",
            fileName = "report.pdf",
            fileSize = 123456L,
            viewOnce = true,
        )
        db.messageDao().upsertAll(listOf(media))
        val roundTripped = db.messageDao().byId("m-media")
        assertNotNull("media row missing after insert", roundTripped)
        assertEquals("9c1f...e7.jpg", roundTripped!!.imagePath)
        assertEquals("voice-1.m4a", roundTripped.audioPath)
        assertEquals("/api/uploads/report.pdf", roundTripped.filePath)
        assertEquals("report.pdf", roundTripped.fileName)
        assertEquals(123456L, roundTripped.fileSize)
        assertEquals(true, roundTripped.viewOnce)
        assertEquals(1500L, roundTripped.durationMs)

        // ── thread queries (v5 DAO surface) ────────────────────
        assertEquals(1, db.messageDao().countByThread("m-old"))
        val threadRows = db.messageDao().observeThread("m-old").first()
        assertEquals(listOf("m-media"), threadRows.map { it.id })

        // ── membersJson round-trip (entity ⇄ domain) ───────────
        val withMembers = conversation.copy(
            membersJson = """[{"id":"u1","name":"Alice","color":"emerald","lastReadAt":1739524860000,"role":"admin"}]""",
        )
        db.conversationDao().upsertAll(listOf(withMembers))
        val reloaded = db.conversationDao().byId("c1")
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.toDomain().members.size)
        val member = reloaded.toDomain().members.first()
        assertEquals("u1", member.id)
        assertEquals("Alice", member.name)
        assertEquals("emerald", member.color)
        assertEquals(1739524860000L, member.lastReadAt)
        assertEquals("admin", member.role)

        // Entity.from re-serializes the domain members back to JSON.
        val reSerialized = ConversationEntity.from(reloaded.toDomain().copy(members = listOf(member)))
        assertTrue(reSerialized.membersJson.contains(""""id":"u1""""))
    }
}

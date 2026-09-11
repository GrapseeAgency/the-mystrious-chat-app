package app.pulse.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.domain.model.Message
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
 * Wave-0 + Wave-1 + Wave-2 migration gate — REAL databases are built from raw SQL
 * matching the deployed schema exactly, seeded, then opened with Room:
 *   - v3 → MIGRATION_3_4: rows survive; outbox + draft DAOs round-trip;
 *   - v4 → MIGRATION_4_5: rows survive; message media columns + membersJson
 *     are usable (DAO round-trip) and old rows backfill with defaults;
 *   - v5 → MIGRATION_5_6: rows survive; Wave-2 depth columns (viewedAt/
 *     transcript/transcribedAt/pollJson/linkPreviewJson/topicId) + the new
 *     topics/savedMessages tables are usable (DAO round-trips).
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

    /**
     * EXACT v5 DDL — v5 = v4 tables + Wave-1 media columns + membersJson.
     * Built from the v3 DDL strings by splicing the extra columns in before
     * the PRIMARY KEY clause (the deployed schema is exactly this).
     */
    private val createConversationsV5 = createConversationsV3
        .removeSuffix("PRIMARY KEY(`id`))") +
        "`membersJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`id`))"

    private val createMessagesV5 = createMessagesV3
        .removeSuffix("PRIMARY KEY(`id`))") +
        "`imagePath` TEXT, `audioPath` TEXT, `filePath` TEXT, `fileName` TEXT, " +
        "`fileSize` INTEGER, `viewOnce` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"

    private fun createV5DatabaseWithSeedRows() {
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        raw.execSQL(createConversationsV5)
        raw.execSQL(createMessagesV5)
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
            put("streakCount", 2)
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
            put("id", "m-v5")
            put("conversationId", "c1")
            put("authorId", "u2")
            put("authorName", "Bob")
            put("kind", "TEXT")
            put("body", "pre-v6 text row")
            put("createdAt", "2026-02-20T10:00:00.000Z")
            put("viaAutomation", 0)
        })
        raw.insert("outbox", null, ContentValues().apply {
            put("conversationId", "c1")
            put("clientId", "client-v5")
            put("content", "queued pre-v6")
            put("kind", "text")
            put("createdAt", "2026-02-20T10:01:00.000Z")
            put("attempts", 0)
        })
        raw.insert("draft", null, ContentValues().apply {
            put("conversationId", "c1")
            put("text", "half-typed pre-v6")
            put("updatedAt", "t1")
        })
        // Mark the file as the deployed v5 schema.
        raw.version = 5
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
            // Wave 1: the compiled schema is now v5, so the REAL open path is
            // 3 → 4 → 5 — both migrations must be present (v5 columns are
            // additive; every assertion below still holds on the v5 state).
            .addMigrations(PulseDatabase.MIGRATION_3_4, PulseDatabase.MIGRATION_4_5, PulseDatabase.MIGRATION_5_6, PulseDatabase.MIGRATION_6_7)
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
            .addMigrations(PulseDatabase.MIGRATION_4_5, PulseDatabase.MIGRATION_5_6, PulseDatabase.MIGRATION_6_7)
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

    /**
     * Wave-2 gate: a REAL v5 database (v4 + media columns + membersJson)
     * migrates to v6 without destruction — old rows survive with the six new
     * nullable columns empty, the NEW topics/savedMessages tables are usable
     * through their DAOs, and Wave-2 message data (pollJson/viewedAt/topicId)
     * round-trips through the message DAO.
     */
    @Test
    fun migration5To6PreservesRowsAndAddsDepthColumnsAndTables() = runBlocking {
        createV5DatabaseWithSeedRows()
        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(PulseDatabase.MIGRATION_5_6, PulseDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        // ── v5 rows intact + new columns empty ─────────────────
        val oldMessage = db.messageDao().byId("m-v5")
        assertNotNull("v5 message row was destroyed by the migration", oldMessage)
        assertEquals("pre-v6 text row", oldMessage!!.body)
        assertNull(oldMessage.viewedAt) // nullable depth columns start empty
        assertNull(oldMessage.transcript)
        assertNull(oldMessage.transcribedAt)
        assertNull(oldMessage.pollJson)
        assertNull(oldMessage.linkPreviewJson)
        assertNull(oldMessage.topicId)

        // Wave-0/1 surfaces still work after the v6 migration.
        assertEquals(1, db.outboxDao().count())
        assertEquals("half-typed pre-v6", db.draftDao().get("c1")?.text)
        val conversation = db.conversationDao().byId("c1")
        assertNotNull(conversation)
        assertEquals("[]", conversation!!.membersJson)

        // ── topics DAO round-trip (General is NOT a row) ────────
        assertEquals(0, db.topicDao().count())
        db.topicDao().upsertAll(
            listOf(
                TopicEntity(
                    id = "t1",
                    conversationId = "c1",
                    name = "Design",
                    emoji = "🎨",
                    lastMessageAt = "2026-02-20T14:00:00.000Z",
                    messageCount = 7,
                ),
                TopicEntity(
                    id = "t2",
                    conversationId = "c1",
                    name = "Launch",
                    lastMessageAt = "2026-02-20T13:00:00.000Z",
                    // defaults: emoji '💬', messageCount 0
                ),
            ),
        )
        assertEquals(2, db.topicDao().count())
        // Rail order: lastMessageAt DESC.
        val rail = db.topicDao().observeFor("c1").first()
        assertEquals(listOf("t1", "t2"), rail.map { it.id })
        assertEquals("Design", rail.first().name)
        assertEquals(7, rail.first().messageCount)
        assertEquals("💬", rail[1].emoji) // backfilled by the column default
        // Domain mapping: ISO → epoch, count carried.
        val domainTopic = rail.first().toDomain()
        assertEquals("Design", domainTopic.name)
        val domainTopicLastMessageAt = domainTopic.lastMessageAt
        assertTrue(domainTopicLastMessageAt != null && domainTopicLastMessageAt > 0L)
        // Other conversations see nothing; scoped delete works.
        assertTrue(db.topicDao().all("cX").isEmpty())
        db.topicDao().deleteById("t2")
        assertEquals(1, db.topicDao().count())
        db.topicDao().deleteByConversation("c1")
        assertEquals(0, db.topicDao().count())

        // ── savedMessages DAO round-trip ────────────────────────
        assertEquals(0, db.savedDao().count())
        db.savedDao().upsertAll(
            listOf(
                SavedMessageEntity(
                    messageId = "m-v6",
                    conversationId = "c1",
                    savedAt = "2026-02-20T12:00:00.000Z",
                ),
                SavedMessageEntity(
                    messageId = "m-v5",
                    conversationId = "c1",
                    savedAt = "2026-02-20T11:00:00.000Z",
                ),
            ),
        )
        assertEquals(2, db.savedDao().count())
        // Library order: savedAt DESC (newest first — wire parity).
        val savedRows = db.savedDao().observeAll().first()
        assertEquals(listOf("m-v6", "m-v5"), savedRows.map { it.messageId })

        // ── Wave-2 message columns round-trip through the DAO ──
        val pollJson = """
            {"id":"p1","question":"Lunch?","closed":false,
             "options":[{"id":"optA","text":"Ramen","position":0,"voteCount":2,
             "votedBy":["u2","u3"]}],"totalVotes":2,"myOptionId":"optA"}
        """.trimIndent().replace("\n", "")
        val depth = MessageEntity(
            id = "m-v6",
            conversationId = "c1",
            authorId = "u1",
            authorName = "Alice",
            kind = "POLL",
            body = "",
            createdAt = "2026-02-20T10:00:00.000Z",
            editedAt = null,
            deletedAt = null,
            replyToId = null,
            threadRootId = null,
            pinnedAt = null,
            reactionsJson = null,
            replyToBody = null,
            replyToAuthor = null,
            senderColor = "emerald",
            viaAutomation = false,
            durationMs = null,
            imagePath = null,
            audioPath = "voice-1.m4a",
            filePath = null,
            fileName = null,
            fileSize = null,
            viewOnce = true,
            viewedAt = "2026-02-20T10:05:00.000Z",
            transcript = "hey team, shipping the demo",
            transcribedAt = "2026-02-20T10:06:00.000Z",
            pollJson = pollJson,
            linkPreviewJson = """{"url":"https://example.com","title":"Example Domain"}""",
            topicId = "t1",
        )
        db.messageDao().upsertAll(listOf(depth))
        val roundTripped = db.messageDao().byId("m-v6")
        assertNotNull("depth row missing after insert", roundTripped)
        assertEquals("2026-02-20T10:05:00.000Z", roundTripped!!.viewedAt)
        assertEquals("hey team, shipping the demo", roundTripped.transcript)
        assertEquals("2026-02-20T10:06:00.000Z", roundTripped.transcribedAt)
        assertTrue(roundTripped.pollJson!!.contains(""""votedBy":["u2","u3"]"""))
        assertTrue(roundTripped.linkPreviewJson!!.contains("Example Domain"))
        assertEquals("t1", roundTripped.topicId)

        // Entity ⇄ domain: poll JSON decodes back into PollInfo, ISO → epoch.
        val domain = roundTripped.toDomain()
        assertEquals(Message.Kind.POLL, domain.kind)
        val poll = domain.poll
        assertNotNull("pollJson must decode back into PollInfo", poll)
        assertEquals("p1", poll!!.id)
        assertEquals("Lunch?", poll.question)
        assertEquals(1, poll.options.size)
        assertEquals(listOf("u2", "u3"), poll.options.first().votedBy)
        assertEquals("optA", poll.pickFor("u2")) // votedBy-derived pick
        // ISO stored in the column parses back to the same epoch instant.
        assertEquals(
            java.time.Instant.parse("2026-02-20T10:05:00.000Z"),
            java.time.Instant.ofEpochMilli(domain.viewedAt!!),
        )
        val domainTranscribedAt = domain.transcribedAt
        assertTrue(domainTranscribedAt != null && domainTranscribedAt > 0L)
        assertEquals("hey team, shipping the demo", domain.transcript)
        assertEquals("t1", domain.topicId)
        val preview = domain.linkPreview
        assertTrue(preview != null && preview.url == "https://example.com")

        // Targeted ASR patch (repo path) touches ONLY the transcript columns.
        db.messageDao().updateTranscription("m-v6", "patched transcript", "2026-02-20T10:07:00.000Z")
        val patched = db.messageDao().byId("m-v6")!!
        assertEquals("patched transcript", patched.transcript)
        assertEquals("2026-02-20T10:07:00.000Z", patched.transcribedAt)
        assertEquals("t1", patched.topicId) // untouched
        assertTrue(patched.pollJson!!.contains("optA")) // untouched

        // Unsave drops exactly one saved row (repo unsave path).
        db.savedDao().deleteById("m-v5")
        assertEquals(listOf("m-v6"), db.savedDao().all().map { it.messageId })
        db.savedDao().deleteByIds(listOf("m-v6"))
        assertEquals(0, db.savedDao().count())
    }

    /**
     * Wave-3 gate: a REAL v6 database migrates to v7 without destruction —
     * every Wave-0/1/2 surface still works, the NEW callLogCache table is
     * usable through its DAO (cache row + history order), and the call-log
     * offline queue dedupes on payloadJson (UNIQUE) exactly like the outbox.
     */
    @Test
    fun migration6To7PreservesRowsAndAddsCallLogTables() = runBlocking {
        createV5DatabaseWithSeedRows()
        // Build the DB up to v6 first (the pre-Wave-3 truth), close, then
        // migrate through v7 — the real deployed device path.
        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(PulseDatabase.MIGRATION_3_4, PulseDatabase.MIGRATION_4_5, PulseDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        assertEquals("pre-v6 text row", db.messageDao().byId("m-v5")!!.body)
        db.close()

        db = Room.databaseBuilder(context, PulseDatabase::class.java, dbName)
            .addMigrations(
                PulseDatabase.MIGRATION_3_4,
                PulseDatabase.MIGRATION_4_5,
                PulseDatabase.MIGRATION_5_6,
                PulseDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()

        // ── every earlier surface survives the v7 hop ──────────
        assertEquals("pre-v6 text row", db.messageDao().byId("m-v5")!!.body)
        assertEquals(1, db.outboxDao().count())
        assertEquals("half-typed pre-v6", db.draftDao().get("c1")?.text)
        assertEquals(0, db.topicDao().count())
        assertEquals(0, db.savedDao().count())

        // ── callLogCache round-trip (Wave-3 v7) ─────────────────
        assertEquals(0, db.callLogDao().count())
        val callerRow = CallLogCacheEntity(
            id = "call-1", conversationId = "c1", callerId = "me", calleeId = "peer",
            kind = "voice", status = "completed", durationSec = 95,
            startedAt = "2026-02-01T10:00:00.000Z", outgoing = true,
            peerId = "peer", peerName = "Ada Lovelace", peerUsername = null,
            peerColor = "emerald", peerAvatar = null,
        )
        val calleeRow = CallLogCacheEntity(
            id = "call-2", conversationId = "c1", callerId = "me", calleeId = "peer",
            kind = "voice", status = "missed", durationSec = 0,
            startedAt = "2026-02-02T10:00:00.000Z", outgoing = false,
            peerId = "me", peerName = "Me", peerUsername = null,
            peerColor = null, peerAvatar = null,
        )
        db.callLogDao().upsertAll(listOf(callerRow, calleeRow))
        // History reads newest-first (startedAt DESC).
        assertEquals(listOf("call-2", "call-1"), db.callLogDao().all().map { it.id })
        val domain = db.callLogDao().all().first().toDomain()
        assertEquals("peer", domain.peer?.id)
        assertEquals("Ada Lovelace", domain.peer?.name)

        // ── callLogQueue dedupe (UNIQUE payloadJson, outbox parity) ──
        val payload = CallLogQueueEntity(
            payloadJson = "\"{\"userId\":\"me\",\"status\":\"missed\"}\"",
            createdAt = "2026-02-02T10:01:00.000Z",
        )
        assertTrue("first enqueue must succeed", db.callLogDao().enqueue(payload) != -1L)
        assertEquals("duplicate payloadJson must IGNORE", -1L, db.callLogDao().enqueue(payload))
        assertEquals(1, db.callLogDao().queueCount())
        val queued = db.callLogDao().queued().first()
        db.callLogDao().bumpAttempts(queued.id)
        assertEquals(1, db.callLogDao().queued().first().attempts)
        db.callLogDao().dequeueById(queued.id)
        assertEquals(0, db.callLogDao().queueCount())
    }
}

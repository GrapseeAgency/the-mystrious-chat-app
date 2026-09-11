package app.pulse.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Room cache — offline-first inbox. v2 added the UI-era columns: members,
 * accent color, streaks, drafts, reactions and reply denormalization.
 * v3 (N10) adds the home-page-era columns: manual unread, streak at-risk/lost,
 * last-message shape flags, mute window epoch, other-user id and channel flag.
 * v4 (Wave 0) adds the offline core WITHOUT touching v3 rows: the FIFO
 * `outbox` (queued sends) and the per-conversation `draft` table — migrated
 * non-destructively (MIGRATION_3_4).
 * v5 (Wave 1) adds the messaging-surface columns: message media
 * (imagePath/audioPath/filePath/fileName/fileSize/viewOnce) and the
 * conversation `membersJson` (id/name/color/lastReadAt/role per member —
 * powers read ticks + the info sheet) — all additive (MIGRATION_4_5).
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageAuthorName: String?,
    val lastMessageKind: String?,
    val lastActivityAt: String?,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
    val memberIdsCsv: String,
    val memberNamesCsv: String,
    val accentColor: String?,
    val streakCount: Int,
    val myDraft: String?,
    val isSelf: Boolean,
    val myManualUnread: Boolean,
    val streakAtRiskCount: Int,
    val streakLost: Boolean,
    val lastMessageMine: Boolean,
    val lastMessageDeleted: Boolean,
    val lastMessageIsReply: Boolean,
    val lastMessageIsImage: Boolean,
    val lastMessageIsAudio: Boolean,
    val lastMessageIsFile: Boolean,
    val lastMessageFileName: String?,
    val mutedUntilEpoch: Long,
    val otherUserId: String?,
    val isChannel: Boolean,
    @ColumnInfo(defaultValue = "[]") val membersJson: String = "[]",
) {
    fun toDomain() = Conversation(
        id = id,
        kind = Conversation.Kind.valueOf(kind),
        title = title,
        lastMessagePreview = lastMessagePreview,
        lastMessageAuthorName = lastMessageAuthorName,
        lastMessageKind = lastMessageKind,
        lastActivityAt = lastActivityAt,
        unreadCount = unreadCount,
        isPinned = isPinned,
        isMuted = isMuted,
        isArchived = isArchived,
        memberIds = memberIdsCsv.split(',').filter { it.isNotBlank() },
        memberNames = memberNamesCsv.split(',').filter { it.isNotBlank() },
        members = membersOf(membersJson),
        accentColor = accentColor,
        streakCount = streakCount,
        myDraft = myDraft,
        isSelf = isSelf,
        myManualUnread = myManualUnread,
        streakAtRiskCount = streakAtRiskCount,
        streakLost = streakLost,
        lastMessageMine = lastMessageMine,
        lastMessageDeleted = lastMessageDeleted,
        lastMessageIsReply = lastMessageIsReply,
        lastMessageIsImage = lastMessageIsImage,
        lastMessageIsAudio = lastMessageIsAudio,
        lastMessageIsFile = lastMessageIsFile,
        lastMessageFileName = lastMessageFileName,
        mutedUntilEpoch = mutedUntilEpoch,
        otherUserId = otherUserId,
        isChannel = isChannel,
    )

    companion object {
        fun from(m: Conversation) = ConversationEntity(
            id = m.id, kind = m.kind.name, title = m.title,
            lastMessagePreview = m.lastMessagePreview,
            lastMessageAuthorName = m.lastMessageAuthorName,
            lastMessageKind = m.lastMessageKind,
            lastActivityAt = m.lastActivityAt,
            unreadCount = m.unreadCount, isPinned = m.isPinned,
            isMuted = m.isMuted, isArchived = m.isArchived,
            memberIdsCsv = m.memberIds.joinToString(","),
            memberNamesCsv = m.memberNames.joinToString(","),
            accentColor = m.accentColor,
            streakCount = m.streakCount,
            myDraft = m.myDraft,
            isSelf = m.isSelf,
            myManualUnread = m.myManualUnread,
            streakAtRiskCount = m.streakAtRiskCount,
            streakLost = m.streakLost,
            lastMessageMine = m.lastMessageMine,
            lastMessageDeleted = m.lastMessageDeleted,
            lastMessageIsReply = m.lastMessageIsReply,
            lastMessageIsImage = m.lastMessageIsImage,
            lastMessageIsAudio = m.lastMessageIsAudio,
            lastMessageIsFile = m.lastMessageIsFile,
            lastMessageFileName = m.lastMessageFileName,
            mutedUntilEpoch = m.mutedUntilEpoch,
            otherUserId = m.otherUserId,
            isChannel = m.isChannel,
            membersJson = app.pulse.protocol.PulseJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ConversationMember.serializer()),
                m.members,
            ),
        )
    }
}

/** membersJson ⇄ domain members (tolerant: corrupt JSON degrades to empty). */
private fun membersOf(json: String): List<ConversationMember> = runCatching {
    app.pulse.protocol.PulseJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(ConversationMember.serializer()),
        json,
    )
}.getOrDefault(emptyList())

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val authorId: String,
    val authorName: String,
    val kind: String,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
    val deletedAt: String?,
    val replyToId: String?,
    val threadRootId: String?,
    val pinnedAt: String?,
    val reactionsJson: String?,
    val replyToBody: String?,
    val replyToAuthor: String?,
    val senderColor: String?,
    val viaAutomation: Boolean,
    val durationMs: Long?,
    // Wave 1 media columns (v5) — nullable TEXT / INTEGER, viewOnce render gate.
    val imagePath: String?,
    val audioPath: String?,
    val filePath: String?,
    val fileName: String?,
    val fileSize: Long?,
    @ColumnInfo(defaultValue = "0") val viewOnce: Boolean = false,
) {
    fun toDomain(): Message {
        val reactionDtos = reactionsJson?.let { json ->
            runCatching {
                app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.ReactionListSerializer, json)
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val reactions = reactionDtos.mapNotNull { dto ->
            val emoji = dto.emoji ?: return@mapNotNull null
            val userId = dto.userId ?: return@mapNotNull null
            app.pulse.domain.model.Reaction(emoji = emoji, userId = userId)
        }
        return Message(
            id = id, conversationId = conversationId, authorId = authorId,
            authorName = authorName, kind = Message.Kind.valueOf(kind), body = body,
            createdAt = createdAt, editedAt = editedAt, deletedAt = deletedAt,
            replyToId = replyToId, threadRootId = threadRootId, pinnedAt = pinnedAt,
            reactions = reactions, replyToBody = replyToBody, replyToAuthor = replyToAuthor,
            senderColor = senderColor, viaAutomation = viaAutomation, durationMs = durationMs,
            imagePath = imagePath, audioPath = audioPath, filePath = filePath,
            fileName = fileName, fileSize = fileSize, viewOnce = viewOnce,
        )
    }

    companion object {
        fun from(m: Message, reactionsJson: String? = null) = MessageEntity(
            id = m.id, conversationId = m.conversationId, authorId = m.authorId,
            authorName = m.authorName, kind = m.kind.name, body = m.body,
            createdAt = m.createdAt, editedAt = m.editedAt, deletedAt = m.deletedAt,
            replyToId = m.replyToId, threadRootId = m.threadRootId, pinnedAt = m.pinnedAt,
            reactionsJson = reactionsJson, replyToBody = m.replyToBody,
            replyToAuthor = m.replyToAuthor, senderColor = m.senderColor,
            viaAutomation = m.viaAutomation, durationMs = m.durationMs,
            imagePath = m.imagePath, audioPath = m.audioPath, filePath = m.filePath,
            fileName = m.fileName, fileSize = m.fileSize, viewOnce = m.viewOnce,
        )
    }
}

/**
 * One queued outgoing message (Wave 0 offline core — web pulse-outbox parity).
 * `clientId` is UNIQUE: it links the optimistic `local_<clientId>` message row
 * to the queue row through enqueue → flush → resolve/drop.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["clientId"], unique = true),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val clientId: String,
    val content: String,
    val kind: String = "text",
    val createdAt: String,
    val attempts: Int = 0,
) {
    fun toEntry() = app.pulse.domain.model.OutboxEntry(
        id = id,
        conversationId = conversationId,
        clientId = clientId,
        content = content,
        kind = kind,
        createdAt = createdAt,
        attempts = attempts,
    )

    companion object {
        fun from(entry: app.pulse.domain.model.OutboxEntry) = OutboxEntity(
            id = entry.id,
            conversationId = entry.conversationId,
            clientId = entry.clientId,
            content = entry.content,
            kind = entry.kind,
            createdAt = entry.createdAt,
            attempts = entry.attempts,
        )
    }
}

/** Per-conversation composer draft (Wave 0 — web pulse-drafts parity). */
@Entity(tableName = "draft")
data class DraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: String,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastActivityAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeFor(conversationId: String): Flow<List<MessageEntity>>

    /** Thread screen rehydration — replies asc from the Room cache. */
    @Query("SELECT * FROM messages WHERE threadRootId = :rootId ORDER BY createdAt ASC")
    fun observeThread(rootId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE threadRootId = :rootId")
    suspend fun countByThread(rootId: String): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface OutboxDao {
    /** Insert/replace by autoGenerate id — clientId uniqueness is enforced by the index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT 50")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT 50")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun deleteByClientId(clientId: String)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE clientId = :clientId")
    suspend fun incrementAttempts(clientId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    /** FIFO cap — keep only the newest [max] rows (web MAX_QUEUE = 50). */
    @Query("DELETE FROM outbox WHERE id NOT IN (SELECT id FROM outbox ORDER BY id DESC LIMIT :max)")
    suspend fun trimBeyond(max: Int)
}

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM draft WHERE conversationId = :conversationId")
    fun observe(conversationId: String): Flow<DraftEntity?>

    @Query("SELECT * FROM draft WHERE conversationId = :conversationId")
    suspend fun get(conversationId: String): DraftEntity?

    @Query("DELETE FROM draft WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM draft")
    suspend fun clearAll()
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val NAME = "pulse.db"

        /**
         * v3 → v4 (Wave 0): add the outbox + draft tables. Non-destructive —
         * every deployed v3 conversation/message row survives untouched.
         * DDL mirrors Room's generated schema exactly (see schemas/4.json).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, `clientId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `attempts` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_conversationId` ON `outbox` (`conversationId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_clientId` ON `outbox` (`clientId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `draft` (`conversationId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`updatedAt` TEXT NOT NULL, PRIMARY KEY(`conversationId`))",
                )
            }
        }

        /**
         * v4 → v5 (Wave 1): message media columns + conversation membersJson.
         * All ADD COLUMNs — every v4 row survives; NOT NULL columns carry
         * defaults so old rows backfill (viewOnce=0, members='[]').
         * DDL mirrors Room's generated schema exactly (see schemas/5.json).
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `imagePath` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `audioPath` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `filePath` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `fileName` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `fileSize` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `viewOnce` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `membersJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}

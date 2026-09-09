package app.pulse.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Room cache — offline-first inbox. v2 added the UI-era columns: members,
 * accent color, streaks, drafts, reactions and reply denormalization.
 * v3 (N10) adds the home-page-era columns: manual unread, streak at-risk/lost,
 * last-message shape flags, mute window epoch, other-user id and channel flag.
 * (fallbackToDestructiveMigration is on — rows rebuild from the gateway.)
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
        )
    }
}

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
        )
    }
}

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

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "pulse.db"
    }
}

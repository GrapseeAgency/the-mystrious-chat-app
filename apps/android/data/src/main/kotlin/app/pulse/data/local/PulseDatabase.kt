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

/** Room cache — offline-first inbox (Room wraps SQLite; FTS5 tables follow in N2). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastActivityAt: String?,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
) {
    fun toDomain() = Conversation(
        id = id,
        kind = Conversation.Kind.valueOf(kind),
        title = title,
        lastMessagePreview = lastMessagePreview,
        lastActivityAt = lastActivityAt,
        unreadCount = unreadCount,
        isPinned = isPinned,
        isMuted = isMuted,
        isArchived = isArchived,
    )

    companion object {
        fun from(m: Conversation) = ConversationEntity(
            id = m.id, kind = m.kind.name, title = m.title,
            lastMessagePreview = m.lastMessagePreview, lastActivityAt = m.lastActivityAt,
            unreadCount = m.unreadCount, isPinned = m.isPinned,
            isMuted = m.isMuted, isArchived = m.isArchived,
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
    val replyToId: String?,
    val threadRootId: String?,
    val pinnedAt: String?,
) {
    fun toDomain() = Message(
        id = id, conversationId = conversationId, authorId = authorId,
        authorName = authorName, kind = Message.Kind.valueOf(kind), body = body,
        createdAt = createdAt, replyToId = replyToId, threadRootId = threadRootId,
        pinnedAt = pinnedAt,
    )

    companion object {
        fun from(m: Message) = MessageEntity(
            id = m.id, conversationId = m.conversationId, authorId = m.authorId,
            authorName = m.authorName, kind = m.kind.name, body = m.body,
            createdAt = m.createdAt, replyToId = m.replyToId,
            threadRootId = m.threadRootId, pinnedAt = m.pinnedAt,
        )
    }
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastActivityAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeFor(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "pulse.db"
    }
}

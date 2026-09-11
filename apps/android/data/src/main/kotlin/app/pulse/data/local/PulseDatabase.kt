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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.LinkPreviewInfo
import app.pulse.domain.model.Message
import app.pulse.domain.model.PollInfo
import app.pulse.domain.model.PollOptionInfo
import app.pulse.protocol.LinkPreviewDto
import app.pulse.protocol.PollDto
import app.pulse.protocol.PollOptionDto
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
 * v6 (Wave 2) adds the messaging-depth columns: viewedAt/transcript/
 * transcribedAt/pollJson/linkPreviewJson/topicId on messages, plus the NEW
 * `topics` rail and `savedMessages` library tables — all additive
 * (MIGRATION_5_6).
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
    // Wave 2 depth columns (v6) — nullable TEXT, ISO timestamps verbatim.
    val viewedAt: String? = null,
    val transcript: String? = null,
    val transcribedAt: String? = null,
    val pollJson: String? = null,
    val linkPreviewJson: String? = null,
    val topicId: String? = null,
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
            viewedAt = epochOrNull(viewedAt),
            transcript = transcript,
            transcribedAt = epochOrNull(transcribedAt),
            poll = pollJson?.let { json ->
                runCatching {
                    app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.PollDto.serializer(), json).toInfo()
                }.getOrNull()
            },
            linkPreview = linkPreviewJson?.let { json ->
                runCatching {
                    app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.LinkPreviewDto.serializer(), json).toInfo()
                }.getOrNull()
            },
            topicId = topicId,
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
            viewedAt = m.viewedAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
            transcript = m.transcript,
            transcribedAt = m.transcribedAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
            // Wire-shape JSON (PollDto/LinkPreviewDto) — same compact form the
            // gateway emits, so the column decodes identically no matter which
            // upsert path wrote it (REST row, socket relay, optimistic copy).
            pollJson = m.poll?.let { poll ->
                app.pulse.protocol.PulseJson.encodeToString(app.pulse.protocol.PollDto.serializer(), poll.toDto())
            },
            linkPreviewJson = m.linkPreview?.let { preview ->
                app.pulse.protocol.PulseJson.encodeToString(app.pulse.protocol.LinkPreviewDto.serializer(), preview.toDto())
            },
            topicId = m.topicId,
        )

        /** ISO wire timestamp → epoch ms (null when absent/unparseable). */
        private fun epochOrNull(iso: String?): Long? =
            app.pulse.core.time.PulseTime.epochMs(iso).takeIf { it > 0L }
    }
}

// ── Wave 2 wire-shape ⇄ domain converters (poll + link preview) ─────

fun PollDto.toInfo(): PollInfo = PollInfo(
    id = id,
    question = question,
    closed = closed,
    options = options.map { o ->
        PollOptionInfo(
            id = o.id,
            text = o.text,
            position = o.position,
            voteCount = o.voteCount,
            votedBy = o.votedBy,
        )
    },
    totalVotes = totalVotes,
    myOptionId = myOptionId,
)

fun PollInfo.toDto(): PollDto = PollDto(
    id = id,
    question = question,
    closed = closed,
    options = options.map { o ->
        PollOptionDto(
            id = o.id,
            text = o.text,
            position = o.position,
            voteCount = o.voteCount,
            votedBy = o.votedBy,
        )
    },
    totalVotes = totalVotes,
    myOptionId = myOptionId,
)

fun LinkPreviewDto.toInfo(): LinkPreviewInfo = LinkPreviewInfo(
    url = url,
    title = title,
    description = description,
    imageUrl = imageUrl,
    siteName = siteName,
)

fun LinkPreviewInfo.toDto(): LinkPreviewDto = LinkPreviewDto(
    url = url,
    title = title,
    description = description,
    imageUrl = imageUrl,
    siteName = siteName,
)

/**
 * One Zulip-style topic chip cached for the rail (Wave 2 v6). "General" is
 * NOT a row — it is the implicit whole room (messages with topicId = null).
 */
@Entity(
    tableName = "topics",
    indices = [Index(value = ["conversationId"])],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val name: String,
    @ColumnInfo(defaultValue = "💬") val emoji: String = "💬",
    val lastMessageAt: String?,
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
) {
    fun toDomain(): app.pulse.domain.model.Topic = app.pulse.domain.model.Topic(
        id = id,
        name = name,
        emoji = emoji,
        lastMessageAt = app.pulse.core.time.PulseTime.epochMs(lastMessageAt).takeIf { it > 0L },
        messageCount = messageCount,
    )

    companion object {
        fun from(conversationId: String, t: app.pulse.domain.model.Topic) = TopicEntity(
            id = t.id,
            conversationId = conversationId,
            name = t.name,
            emoji = t.emoji,
            lastMessageAt = t.lastMessageAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
            messageCount = t.messageCount,
        )
    }
}

/** One saved-library row (Wave 2 v6) — the message itself lives in `messages`. */
@Entity(
    tableName = "savedMessages",
    indices = [Index(value = ["conversationId"])],
)
data class SavedMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val savedAt: String,
)

/**
 * One cached call-history row (Wave 3 v7) — the offline mirror of
 * GET /api/calls (server cap 50, newest first). Peer identity is
 * denormalized exactly like the server resolves it: the OTHER party
 * relative to the viewer, so the cache renders offline precisely what the
 * endpoint renders online. Columns match the iOS callLogCache 1:1.
 */
@Entity(
    tableName = "callLogCache",
    indices = [Index(value = ["startedAt"])],
)
data class CallLogCacheEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val callerId: String,
    val calleeId: String,
    @ColumnInfo(defaultValue = "voice") val kind: String = "voice",
    val status: String,
    @ColumnInfo(defaultValue = "0") val durationSec: Long = 0,
    val startedAt: String,
    @ColumnInfo(defaultValue = "0") val outgoing: Boolean = false,
    @ColumnInfo(defaultValue = "") val peerId: String = "",
    @ColumnInfo(defaultValue = "Unknown") val peerName: String = "Unknown",
    val peerUsername: String?,
    val peerColor: String?,
    val peerAvatar: String?,
) {
    fun toDomain(): app.pulse.domain.model.CallLogEntry = app.pulse.domain.model.CallLogEntry(
        id = id,
        conversationId = conversationId,
        callerId = callerId,
        calleeId = calleeId,
        kind = app.pulse.domain.model.CallKind.of(kind),
        status = app.pulse.domain.model.CallStatus.of(status),
        durationSec = durationSec,
        startedAt = startedAt,
        outgoing = outgoing,
        peer = app.pulse.domain.model.CallPeer(id = peerId, name = peerName, color = peerColor, avatar = peerAvatar)
            .takeIf { peerId.isNotBlank() },
    )

    companion object {
        fun from(e: app.pulse.domain.model.CallLogEntry) = CallLogCacheEntity(
            id = e.id,
            conversationId = e.conversationId,
            callerId = e.callerId,
            calleeId = e.calleeId,
            kind = e.kind.wire,
            status = e.status.wire,
            durationSec = e.durationSec,
            startedAt = e.startedAt,
            outgoing = e.outgoing,
            peerId = e.peer?.id ?: "",
            peerName = e.peer?.name ?: "Unknown",
            peerUsername = null,
            peerColor = e.peer?.color,
            peerAvatar = e.peer?.avatar,
        )
    }
}

/**
 * One queued single-writer POST /api/calls row (Wave 3 v7) — a call that
 * TERMINATED while the gateway was unreachable. `payloadJson` is UNIQUE:
 * it dedupes double-enqueues exactly like outbox.clientId. Flushed FIFO on
 * app start / socket reconnect (mirrors the outbox trigger style).
 */
@Entity(
    tableName = "callLogQueue",
    indices = [Index(value = ["payloadJson"], unique = true)],
)
data class CallLogQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payloadJson: String,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val createdAt: String,
)


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

    /**
     * One batched "N replies ↳" lookup for the river — Room expands the IN
     * list; empty root list returns an empty sheet (Room requires non-empty).
     */
    @Query(
        "SELECT threadRootId AS rootId, COUNT(*) AS cnt FROM messages " +
            "WHERE threadRootId IN (:rootIds) GROUP BY threadRootId",
    )
    suspend fun countsByThread(rootIds: List<String>): List<ThreadCountRow>

    /** Optimistic-dedupe candidates: MY queued echoes with the same body (never the keeper). */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND authorId = :authorId " +
            "AND body = :body AND id LIKE 'local_%' AND id != :keepId",
    )
    suspend fun tempEchoes(conversationId: String, authorId: String, body: String, keepId: String): List<MessageEntity>

    /**
     * Targeted ASR patch (Wave 2) — transcribe() writes ONLY the transcript
     * columns so reactions/media/poll JSON on the row are never rewritten.
     */
    @Query("UPDATE messages SET transcript = :transcript, transcribedAt = :transcribedAt WHERE id = :id")
    suspend fun updateTranscription(id: String, transcript: String, transcribedAt: String?)
}

/** Projection row for the batched thread-count query (river reply chips). */
data class ThreadCountRow(val rootId: String, val cnt: Int)

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

    /** All drafts at once — the chats-list "Draft:" preview merge (local wins). */
    @Query("SELECT * FROM draft")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM draft WHERE conversationId = :conversationId")
    suspend fun get(conversationId: String): DraftEntity?

    @Query("DELETE FROM draft WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM draft")
    suspend fun clearAll()
}

/** Live topic rail for one conversation (Wave 2 — General is NOT a row). */
@Dao
interface TopicDao {
    @Upsert
    suspend fun upsertAll(items: List<TopicEntity>)

    /** Rail order = lastMessageAt DESC (the wire's own ordering). */
    @Query("SELECT * FROM topics WHERE conversationId = :conversationId ORDER BY lastMessageAt DESC")
    fun observeFor(conversationId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE conversationId = :conversationId ORDER BY lastMessageAt DESC")
    suspend fun all(conversationId: String): List<TopicEntity>

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM topics WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM topics")
    suspend fun count(): Int
}

/**
 * Call-history store (Wave 3 v7) — cache mirror of GET /api/calls plus the
 * offline queue for the single-writer POST /api/calls.
 */
@Dao
interface CallLogDao {
    @Upsert
    suspend fun upsertAll(items: List<CallLogCacheEntity>)

    /** History order = startedAt DESC (the wire's own ordering, server cap 50). */
    @Query("SELECT * FROM callLogCache ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallLogCacheEntity>>

    @Query("SELECT * FROM callLogCache ORDER BY startedAt DESC")
    suspend fun all(): List<CallLogCacheEntity>

    @Query("SELECT * FROM callLogCache WHERE id = :callId")
    suspend fun get(callId: String): CallLogCacheEntity?

    /** Prune after refresh — rows the server no longer lists. */
    @Query("DELETE FROM callLogCache WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    /** Upsert a locally-written terminal row (caller side) so the list is
     *  instant even before the next refresh. */
    @Upsert
    suspend fun upsert(item: CallLogCacheEntity)

    @Query("SELECT COUNT(*) FROM callLogCache")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(row: CallLogQueueEntity): Long

    @Query("SELECT * FROM callLogQueue ORDER BY id ASC LIMIT :max")
    suspend fun queued(max: Int = 50): List<CallLogQueueEntity>

    @Query("DELETE FROM callLogQueue WHERE id = :id")
    suspend fun dequeueById(id: Long)

    @Query("UPDATE callLogQueue SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM callLogQueue")
    suspend fun queueCount(): Int
}

/** Saved-library index (Wave 2) — server cap 100, newest first, no pagination. */
@Dao
interface SavedDao {
    @Upsert
    suspend fun upsertAll(items: List<SavedMessageEntity>)

    @Query("SELECT * FROM savedMessages ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedMessageEntity>>

    @Query("SELECT * FROM savedMessages ORDER BY savedAt DESC")
    suspend fun all(): List<SavedMessageEntity>

    @Query("DELETE FROM savedMessages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    /** Prune after refreshSavedLibrary — rows the server no longer lists. */
    @Query("DELETE FROM savedMessages WHERE messageId IN (:messageIds)")
    suspend fun deleteByIds(messageIds: List<String>)

    @Query("SELECT COUNT(*) FROM savedMessages")
    suspend fun count(): Int
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
        TopicEntity::class,
        SavedMessageEntity::class,
        CallLogCacheEntity::class,
        CallLogQueueEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun draftDao(): DraftDao
    abstract fun topicDao(): TopicDao
    abstract fun savedDao(): SavedDao
    abstract fun callLogDao(): CallLogDao

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

        /**
         * v5 → v6 (Wave 2): message depth columns (view-once burn stamp,
         * ASR transcript, poll/linkPreview JSON, topic filing) + the NEW
         * `topics` and `savedMessages` tables. All additive — every v5 row
         * survives; nullable columns need no defaults. DDL mirrors Room's
         * generated schema exactly (see schemas/6.json).
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `viewedAt` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `transcript` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `transcribedAt` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `pollJson` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `linkPreviewJson` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `topicId` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topics` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `emoji` TEXT NOT NULL DEFAULT '💬', `lastMessageAt` TEXT, " +
                        "`messageCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_conversationId` ON `topics` (`conversationId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `savedMessages` (`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`savedAt` TEXT NOT NULL, PRIMARY KEY(`messageId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_savedMessages_conversationId` ON `savedMessages` (`conversationId`)")
            }
        }

        /**
         * v6 → v7 (Wave 3): call history cache + offline call-log queue.
         * Two NEW tables — every v6 row survives untouched. DDL mirrors
         * Room's generated schema exactly (see schemas/7.json) and matches
         * the iOS callLogCache/callLogQueue columns 1:1.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `callLogCache` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`callerId` TEXT NOT NULL, `calleeId` TEXT NOT NULL, `kind` TEXT NOT NULL DEFAULT 'voice', " +
                        "`status` TEXT NOT NULL, `durationSec` INTEGER NOT NULL DEFAULT 0, `startedAt` TEXT NOT NULL, " +
                        "`outgoing` INTEGER NOT NULL DEFAULT 0, `peerId` TEXT NOT NULL DEFAULT '', " +
                        "`peerName` TEXT NOT NULL DEFAULT 'Unknown', `peerUsername` TEXT, `peerColor` TEXT, " +
                        "`peerAvatar` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_callLogCache_startedAt` ON `callLogCache` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `callLogQueue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, `attempts` INTEGER NOT NULL DEFAULT 0, `createdAt` TEXT NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_callLogQueue_payloadJson` ON `callLogQueue` (`payloadJson`)")
            }
        }
    }
}

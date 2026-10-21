package app.pulse.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.domain.model.Message
import app.pulse.domain.model.OutboxEntry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxOwnershipTest {
    @Test fun foreignAndUnknownOwnersNeverDrainAndCannotHideCurrentOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java,
        ).build()
        try {
            suspend fun enqueue(id: String, owner: String?) {
                db.outboxDao().insert(OutboxEntity.from(OutboxEntry(
                    conversationId = "room", clientId = id, content = id,
                    createdAt = "2026-10-19T00:00:00Z",
                )))
                if (owner != null) db.messageDao().upsertAll(listOf(MessageEntity.from(Message(
                    id = "local_$id", conversationId = "room", authorId = owner,
                    authorName = owner, kind = Message.Kind.TEXT, body = id,
                    createdAt = "2026-10-19T00:00:00Z",
                ))))
            }
            repeat(55) { enqueue("foreign-$it", "a") }
            enqueue("unknown", null)
            enqueue("mine", "b")
            assertEquals(listOf("mine"), db.outboxDao().allForOwner("b").map { it.clientId })
            assertEquals(50, db.outboxDao().allForOwner("a").size)
            assertEquals(57, db.outboxDao().count())
            db.outboxDao().markFailed("mine", "forbidden")
            assertEquals(emptyList<String>(), db.outboxDao().allForOwner("b").map { it.clientId })
            assertEquals(listOf("mine"), db.outboxDao().allOwned("b").map { it.clientId })
            assertEquals(listOf("mine"), db.outboxDao().observeOwned("b").first().map { it.clientId })
            assertEquals(0, db.outboxDao().retryFailed("mine", "a"))
            assertEquals(0, db.outboxDao().retryFailed("unknown", "b"))
            assertEquals(1, db.outboxDao().retryFailed("mine", "b"))
            assertEquals(0, db.outboxDao().retryFailed("mine", "b"))
            assertEquals("mine", db.outboxDao().allForOwner("b").single().content)
            assertEquals(55, db.outboxDao().allOwned("a").size)
        } finally { db.close() }
    }
    @Test fun heldRoomExclusionPrecedesLimitAndCountsKeepAllOwnedPendingIntent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            suspend fun enqueue(id: String, room: String) {
                db.outboxDao().insert(OutboxEntity.from(OutboxEntry(conversationId = room, clientId = id, content = id, createdAt = "2026-10-20T00:00:00Z")))
                db.messageDao().upsertAll(listOf(MessageEntity.from(Message(id = "local_$id", conversationId = room,
                    authorId = "owner", authorName = "Owner", kind = Message.Kind.TEXT, body = id, createdAt = "2026-10-20T00:00:00Z"))))
            }
            repeat(75) { enqueue("held-$it", "slow") }
            enqueue("ready", "direct")
            assertEquals(50, db.outboxDao().allForOwner("owner").size)
            assertEquals(listOf("ready"), db.outboxDao().allForOwner("owner", listOf("slow")).map { it.clientId })
            assertEquals(76, db.outboxDao().pendingCountForOwner("owner"))
            db.outboxDao().markFailed("ready", "forbidden")
            assertEquals(75, db.outboxDao().pendingCountForOwner("owner"))
            assertEquals(0, db.outboxDao().pendingCountForOwner("other"))
        } finally { db.close() }
    }

    @Test fun threadRecoverySeparatesOwnersRootsRoomsAndRejectsStaleDiscard() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            suspend fun enqueue(id: String, owner: String, room: String, root: String?) {
                db.outboxDao().insert(OutboxEntity.from(OutboxEntry(conversationId = room, clientId = id,
                    content = "original $id", wirePayload = "{\"original\":true}", attempts = 3, createdAt = "2026-10-20T00:00:00Z")))
                db.messageDao().upsertAll(listOf(MessageEntity.from(Message(id = "local_$id", conversationId = room,
                    authorId = owner, authorName = owner, kind = Message.Kind.TEXT, body = "original $id",
                    threadRootId = root, createdAt = "2026-10-20T00:00:00Z"))))
                db.outboxDao().markFailed(id, "rejected")
            }
            enqueue("mine", "me", "room", "root")
            enqueue("foreign", "other", "room", "root")
            enqueue("other-root", "me", "room", "elsewhere")
            enqueue("other-room", "me", "elsewhere", "root")
            enqueue("main", "me", "room", null)
            val failed = db.outboxDao().observeFailedThread("me", "room", "root").first()
            assertEquals(listOf("mine"), failed.map { it.clientId })
            assertEquals(0, db.outboxDao().discardFailedThread("other", "room", "root", "mine"))
            assertEquals(0, db.outboxDao().discardFailedThread("me", "room", "elsewhere", "mine"))
            assertEquals(0, db.outboxDao().discardFailedThread("me", "elsewhere", "root", "mine"))
            assertEquals(1, db.outboxDao().retryFailed("mine", "me"))
            assertEquals(0, db.outboxDao().discardFailedThread("me", "room", "root", "mine"))
            assertEquals(emptyList<String>(), db.outboxDao().observeFailedThread("me", "room", "root").first().map { it.clientId })
            val retried = db.outboxDao().pendingByClientId("mine")!!
            assertEquals(failed.single().id, retried.id)
            assertEquals(failed.single().wirePayload, retried.wirePayload)
            assertEquals("original mine", retried.content)
            assertEquals(0, retried.attempts)
            assertEquals("root", db.messageDao().byId("local_mine")!!.threadRootId)
            // No root row exists: the user's rejected text still permits explicit recovery/discard.
            assertEquals(1, db.outboxDao().discardFailedThread("me", "room", "elsewhere", "other-root"))
            assertEquals(1, db.outboxDao().count()) // count() is pending-only
            assertEquals(4, db.outboxDao().all().size)
        } finally { db.close() }
    }

    @Test fun replyChipCountsExcludeRetainedFailedEchoesAndReplayDoesNotInflate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            val root = Message(id = "root", conversationId = "room", authorId = "me", authorName = "Me",
                kind = Message.Kind.TEXT, body = "Root", createdAt = "2026-10-20T00:00:00Z")
            val reply = root.copy(id = "reply", threadRootId = "root")
            val failed = reply.copy(id = "local_failed")
            db.messageDao().upsertAll(listOf(root, reply, failed).map { MessageEntity.from(it) })
            db.outboxDao().insert(OutboxEntity.from(OutboxEntry(conversationId = "room", clientId = "failed",
                content = "Reply", createdAt = root.createdAt)))
            db.outboxDao().markFailed("failed", "rejected")
            db.messageDao().upsertAll(listOf(MessageEntity.from(reply))) // duplicate relay
            fun counts(rows: List<MessageEntity>) = app.pulse.domain.sync.cachedThreadReplyCounts(rows.map { it.toDomain() })
            assertEquals(mapOf("root" to 1), counts(db.messageDao().observeFor("room").first()))
            db.messageDao().upsertAll(listOf(MessageEntity.from(reply.copy(deletedAt = "2026-10-21T00:00:00Z"))))
            assertEquals(emptyMap<String, Int>(), counts(db.messageDao().observeFor("room").first()))
            assertEquals(1, db.outboxDao().all().size) // recovery intent was not deleted to fix the badge
        } finally { db.close() }
    }

    @Test fun delayedRowsCannotReopenDeletedOrConsumedMessages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            val live = Message(id = "m", conversationId = "room", authorId = "peer", authorName = "Peer",
                kind = Message.Kind.IMAGE, body = "secret", imagePath = "secret.jpg", viewOnce = true,
                expiresAtEpochMs = 9000L, createdAt = "2026-10-21T00:00:00Z")
            val consumed = live.copy(viewedAt = 1000L)
            db.messageDao().upsertAll(listOf(consumed, live.copy(viewOnce = false, expiresAtEpochMs = null)).map { MessageEntity.from(it) })
            val retained = db.messageDao().byId("m")!!.toDomain()
            assertEquals(1000L, retained.viewedAt)
            assertEquals(true, retained.viewOnce)
            assertEquals(9000L, retained.expiresAtEpochMs)
            db.messageDao().upsertAll(listOf(live.copy(deletedAt = "2026-10-21T00:01:00Z"), live).map { MessageEntity.from(it) })
            val rawDeleted = db.messageDao().byId("m")!!
            assertEquals("", rawDeleted.body)
            assertEquals(null, rawDeleted.imagePath)
            val deleted = rawDeleted.toDomain()
            assertEquals(true, deleted.isDeleted)
            assertEquals("", deleted.body)
            assertEquals(null, deleted.imagePath)
            db.messageDao().upsertAll(listOf(MessageEntity.from(live)))
            assertEquals(true, db.messageDao().byId("m")!!.toDomain().isDeleted)
        } finally { db.close() }
    }


    @Test fun deniedThreadStaysHiddenAfterReopenAndCacheClearWithoutDeletingIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "thread-access-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        val other = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        var db = open()
        try {
            val root = Message(id = "root", conversationId = "room", authorId = "me", authorName = "Me",
                kind = Message.Kind.TEXT, body = "Root", createdAt = "2026-10-21T00:00:00Z")
            val reply = root.copy(id = "reply", threadRootId = root.id)
            db.messageDao().upsertAll(listOf(root, reply).map { MessageEntity.from(it) })
            assertEquals(setOf("root", "reply"), db.messageDao().cachedThreadTargetRows("room", "root", "reply").map { it.id }.toSet())
            assertEquals(emptyList<MessageEntity>(), db.messageDao().cachedThreadTargetRows("other", "root", "reply"))
            val key = app.pulse.domain.sync.threadDenialKey(root.id)
            db.draftDao().upsert(DraftEntity("draft", "keep typing", root.createdAt))
            db.outboxDao().insert(OutboxEntity.from(OutboxEntry(conversationId = "room", clientId = "pending", content = "keep reply", createdAt = root.createdAt)))
            db.wave7Dao().upsert(Wave7CacheEntity(key, "denied", 1L))
            db.wave7Dao().upsert(Wave7CacheEntity("hub:market", "{}", 1L))
            db.wave7Dao().clearAll()
            assertEquals(null, db.wave7Dao().get("hub:market"))
            assertEquals(key, db.wave7Dao().observe(key).first()?.key)
            assertEquals(emptyList<MessageEntity>(), db.messageDao().observeThread("root").first())
            assertEquals(listOf("root"), db.messageDao().observeFor("room").first().map { it.id })
            db.close()
            db = open()
            assertEquals(key, db.wave7Dao().get(key)?.key)
            // Ordinary cache refresh cannot override an explicit access refusal.
            db.messageDao().upsertAll(listOf(MessageEntity.from(reply)))
            assertEquals(emptyList<MessageEntity>(), db.messageDao().observeThread("root").first())
            assertEquals(0, db.messageDao().countByThread("root"))
            assertEquals(emptyList<MessageEntity>(), db.messageDao().cachedThreadTargetRows("room", "root", "reply"))
            assertEquals(1, db.outboxDao().all().size)
            assertEquals("keep typing", db.draftDao().get("draft")?.text)
            assertEquals(null, other.wave7Dao().get(key))
            other.messageDao().upsertAll(listOf(MessageEntity.from(reply)))
            assertEquals(listOf("reply"), other.messageDao().observeThread("root").first().map { it.id })
            db.wave7Dao().delete(key) // repository does this only after validated fresh parent + cache commit
            assertEquals(listOf("reply"), db.messageDao().observeThread("root").first().map { it.id })
        } finally { db.close(); other.close(); context.deleteDatabase(name) }
    }
}

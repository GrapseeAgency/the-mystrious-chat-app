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

}

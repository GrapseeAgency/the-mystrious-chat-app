package app.pulse.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.domain.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedLibraryObservationTest {
    private fun message(id: String) = Message(id = id, conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.TEXT, body = "original", createdAt = "2026-10-20T00:00:00Z")

    @Test fun offlineSavedPagesCrossThousandsOfTiedMarkersAndSelectedObserversStayBounded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            repeat(24) { batch ->
                val messages = (0 until 100).map { message("m%05d".format(batch * 100 + it)) }
                db.withTransaction {
                    db.messageDao().upsertAll(messages.map(MessageEntity::from))
                    db.savedDao().upsertAll(messages.map { SavedMessageEntity(it.id, it.conversationId, it.createdAt) })
                }
            }
            var at: String? = null; var id: String? = null; var count = 0
            do {
                val rows = db.savedDao().offlinePage(at, id)
                val page = rows.take(100)
                assertTrue(page.size <= 100)
                assertEquals(page.size, db.savedDao().page(page.map { it.messageId }, false).size)
                count += page.size
                at = page.lastOrNull()?.savedAt; id = page.lastOrNull()?.messageId
            } while (rows.size > 100)
            assertEquals(2400, count)
            db.wave7Dao().seedSavedVersions("saved-version:v1|owner|")
            assertEquals(100, db.wave7Dao().savedVersionKeys("saved-version:v1|owner|", "").size)
        } finally { db.close() }
    }

    @Test fun savedRepliesRespectKnownAncestorsDenialAndPreviewDeadline() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            val reply = message("reply").copy(threadRootId = "root")
            db.messageDao().upsertAll(listOf(MessageEntity.from(reply)))
            db.savedDao().upsertAll(listOf(SavedMessageEntity(reply.id, "room", reply.createdAt)))
            assertEquals(1, db.savedDao().library().size) // absence offline is not a revocation
            val parent = message("root").copy(expiresAtEpochMs = 5000L)
            db.messageDao().upsertAll(listOf(MessageEntity.from(parent)))
            assertEquals(5000L, db.savedDao().library().single().toDomain().message.expiresAtEpochMs)
            assertNull(db.messageDao().byId(reply.id)?.toDomain()?.expiresAtEpochMs) // projection never rewrites canonical expiry
            db.wave7Dao().upsert(Wave7CacheEntity(app.pulse.domain.sync.threadDenialKey("root"), "denied", 0))
            assertTrue(db.savedDao().library().isEmpty())
            db.wave7Dao().delete(app.pulse.domain.sync.threadDenialKey("root"))
            db.messageDao().upsertAll(listOf(MessageEntity.from(parent.copy(deletedAt = "now"))))
            assertTrue(db.savedDao().library().isEmpty())
            val once = message("once").copy(viewOnce = true)
            db.messageDao().upsertAll(listOf(MessageEntity.from(once)))
            db.savedDao().upsertAll(listOf(SavedMessageEntity(once.id, "room", once.createdAt)))
            assertTrue(db.savedDao().library().isEmpty())
        } finally { db.close() }
    }

    @Test fun messageOnlyEditsAndDeletesInvalidateSavedJoinAndReplacementRollsBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        val updates = Channel<List<SavedLibraryRow>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) { db.savedDao().observeLibrary().collect { updates.send(it) } }
        suspend fun awaitRows(predicate: (List<SavedLibraryRow>) -> Boolean) = withTimeout(10_000) {
            var rows = updates.receive()
            while (!predicate(rows)) rows = updates.receive()
            rows
        }
        try {
            awaitRows { it.isEmpty() }
            val original = message("saved")
            db.withTransaction {
                db.messageDao().upsertAll(listOf(MessageEntity.from(original)))
                db.savedDao().upsertAll(listOf(SavedMessageEntity(original.id, "room", original.createdAt)))
            }
            awaitRows { it.singleOrNull()?.message?.body == "original" }
            db.messageDao().upsertAll(listOf(MessageEntity.from(original.copy(body = "edited"))))
            awaitRows { it.singleOrNull()?.message?.body == "edited" }
            db.messageDao().upsertAll(listOf(MessageEntity.from(original.copy(deletedAt = "now"))))
            awaitRows { it.isEmpty() }
            assertEquals(1, db.savedDao().count()) // marker never changed; message invalidation hid it
            try {
                db.withTransaction {
                    db.savedDao().clear()
                    db.messageDao().upsertAll(listOf(MessageEntity.from(message("replacement"))))
                    db.savedDao().upsertAll(listOf(SavedMessageEntity("replacement", "room", original.createdAt)))
                    error("simulated storage failure")
                }
                fail("transaction should throw")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf("saved"), db.savedDao().all().map { it.messageId })
            assertNull(db.messageDao().byId("replacement"))
            assertTrue(db.savedDao().library().isEmpty())
        } finally {
            observer.cancelAndJoin()
            updates.close()
            db.close()
        }
    }
}

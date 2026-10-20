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

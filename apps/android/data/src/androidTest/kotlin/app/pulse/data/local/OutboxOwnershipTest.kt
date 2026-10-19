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
}

package app.pulse.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.domain.model.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadWindowObservationTest {
    private fun row(id: String, root: String? = "root") = MessageEntity.from(Message(id = id,
        conversationId = "room", authorId = "peer", authorName = "Peer", kind = Message.Kind.TEXT,
        body = "reply", createdAt = "2026-10-22T00:00:00Z", threadRootId = root))

    @Test fun largeCacheObservationIsBoundedAndOldPagesRemainReachableOffline() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            val dao = db.messageDao()
            db.withTransaction {
                dao.upsertAll(listOf(row("root", null), row("local_queued")))
                (0 until 12000).chunked(200).forEach { ids -> dao.upsertAll(ids.map { row("m-${it.toString().padStart(6, '0')}") }) }
            }
            assertEquals(listOf("root"), dao.observeRiver("room", emptyList()).first().map { it.id })
            assertEquals(setOf("root", "m-000001"), dao.observeRiver("room", listOf("m-000001")).first().map { it.id }.toSet())
            assertEquals(12001, dao.liveThreadCounts("room", 0).single().cnt)
            assertEquals(61, dao.observeThreadWindow("root", emptyList(), true).first().size)
            val old = dao.observeThreadWindow("root", listOf("m-000001", "m-000002"), false).first()
            assertEquals(setOf("m-000001", "m-000002", "local_queued"), old.map { it.id }.toSet())
            assertEquals(listOf("local_queued"), dao.observeThreadWindow("root", emptyList(), false).first().map { it.id })
            assertEquals("root", dao.observeMessage("room", "root").first()?.id)
            assertNull(dao.observeMessage("foreign", "root").first())
            assertEquals(listOf("m-000000"), dao.cachedThreadSlice("room", "root", "2026-10-22T00:00:00Z", "m-000001", false, 60, 0).map { it.id })
            assertEquals(60, dao.cachedThreadSlice("room", "root", "2026-10-22T00:00:00Z", "m-000001", true, 60, 0).size)
            db.wave7Dao().upsert(Wave7CacheEntity(app.pulse.domain.sync.threadDenialKey("root"), "denied", 0))
            assertTrue(dao.observeThreadWindow("root", listOf("m-000001"), false).first().isEmpty())
            assertTrue(dao.liveThreadCounts("room", 0).isEmpty())
        } finally { db.close() }
    }
}

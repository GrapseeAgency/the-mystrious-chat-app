package app.pulse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.local.PulseDatabase
import app.pulse.domain.sync.MessageDispatchGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxRetryJournalTest {
    @Test fun restartRestoresOwnedRoomAndGlobalDeadlinesWithoutRestartingEveryScan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retry-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        var wall = 1_000_000L
        var monotonic = 1_000L
        try {
            val first = MessageDispatchGate { monotonic }
            OutboxRetryJournal(db, first) { wall }.hold("me", 60, "slow")
            db.close()
            wall += 10_000; monotonic = 0
            db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
            val fresh = MessageDispatchGate { monotonic }
            val restored = OutboxRetryJournal(db, fresh) { wall }
            restored.restore("me")
            assertEquals(50_000L, fresh.remainingDelayMillis("slow"))
            assertNull(fresh.tryImmediate({ true }, "slow") { "too early" })
            assertEquals("sent", fresh.tryImmediate({ true }, "healthy") { "sent" })
            monotonic += 10_000
            restored.restore("me")
            assertEquals(40_000L, fresh.remainingDelayMillis("slow"))
            restored.hold("me", 1, "slow")
            assertEquals(40_000L, fresh.remainingDelayMillis("slow"))
            restored.hold("me", 20, null)
            val third = MessageDispatchGate { monotonic }
            OutboxRetryJournal(db, third) { wall }.restore("me")
            assertNull(third.tryImmediate({ true }, "healthy") { "global hold" })
            val foreign = MessageDispatchGate { monotonic }
            OutboxRetryJournal(db, foreign) { wall }.restore("other")
            assertEquals(0L, foreign.remainingDelayMillis("slow"))
            wall += 86_400_001
            restored.restore("me")
            assertTrue(db.wave7Dao().readJournal("outbox-retry:v1|me|").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun failedRetryCommitKeepsMemoryHoldAndPersistsWhenStorageRecovers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retry-failure-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        try {
            val admission = ComposerRetryAdmission(db)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_retry BEFORE INSERT ON wave7_cache BEGIN SELECT RAISE(ABORT, 'disk unavailable'); END")
            assertTrue(runCatching { admission.hold("me", 60) }.isFailure)
            assertTrue(admission.isHeld("me"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_retry")
            // A fresh journal wrapper shares the pending deadline, not just the memory gate.
            assertFalse(ComposerRetryAdmission(db).canRun("me"))
            assertNotNull(db.wave7Dao().get("outbox-retry:v1|me|global"))
            db.close()
            db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
            assertFalse(ComposerRetryAdmission(db).canRun("me"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

}

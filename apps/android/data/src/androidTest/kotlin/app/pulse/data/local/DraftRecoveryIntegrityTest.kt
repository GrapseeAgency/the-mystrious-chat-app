package app.pulse.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.repository.DraftWriteCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRecoveryIntegrityTest {
    @Test fun roomAndThreadRecoveryKeepTheFullSnapshotAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "draft-recovery-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        var db = open()
        val text = "a".repeat(1999) + "🌿" + " tail".repeat(100)
        try {
            for (key in listOf("room", "thread-key"))
                db.draftDao().upsert(DraftEntity(key, DraftWriteCoordinator.localSnapshot(text), "time"))
            db.close(); db = open()
            assertEquals(text, db.draftDao().get("room")?.text)
            assertEquals(text, db.draftDao().get("thread-key")?.text)
            db.draftDao().upsert(DraftEntity("room", DraftWriteCoordinator.localSnapshot(""), "later"))
            assertEquals("", db.draftDao().get("room")?.text)
            assertEquals(text, db.draftDao().get("thread-key")?.text)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

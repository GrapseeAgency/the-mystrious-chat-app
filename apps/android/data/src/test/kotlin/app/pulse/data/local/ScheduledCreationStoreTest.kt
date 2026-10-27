package app.pulse.data.local

import app.pulse.data.repository.scheduledCreationIntent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduledCreationStoreTest {
    @Test fun `schedule retry identity survives restart and only its accepted receipt retires it`() = runBlocking {
        val file = File.createTempFile("scheduled-creation", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        var store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
        val intent = scheduledCreationIntent("me", "room", " Hello ", "2030-01-01T09:00:00Z")
        try {
            assertEquals(intent, scheduledCreationIntent("me", "room", "Hello", "2030-01-01T10:00:00.000+01:00"))
            val id = store.beginRoomCreation(intent)
            for (other in listOf(
                scheduledCreationIntent("other", "room", "Hello", "2030-01-01T09:00:00Z"),
                scheduledCreationIntent("me", "other-room", "Hello", "2030-01-01T09:00:00Z"),
                scheduledCreationIntent("me", "room", "Changed", "2030-01-01T09:00:00Z"),
                scheduledCreationIntent("me", "room", "Hello", "2030-01-01T09:00:01Z"),
            )) assertNotEquals(id, store.beginRoomCreation(other))
            job.cancelAndJoin(); job = SupervisorJob()
            store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
            assertEquals(id, store.beginRoomCreation(intent))
            store.finishRoomCreation(intent, "wrong-receipt")
            assertEquals(id, store.beginRoomCreation(intent))
            store.finishRoomCreation(intent, id)
            val next = store.beginRoomCreation(intent)
            assertNotEquals(id, next)
            store.finishRoomCreation(intent, id)
            assertEquals(next, store.beginRoomCreation(intent))
        } finally { job.cancelAndJoin(); file.delete() }
    }
}

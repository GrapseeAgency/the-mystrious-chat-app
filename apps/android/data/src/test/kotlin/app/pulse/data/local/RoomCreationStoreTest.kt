package app.pulse.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomCreationStoreTest {
    @Test fun `unknown creations survive restart while stale receipts cannot consume the next creation`() = runBlocking {
        val file = File.createTempFile("room-creations", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        var store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
        try {
            val first = store.beginRoomCreation("owner-game-room-opponent")
            assertEquals(first, store.beginRoomCreation("owner-game-room-opponent"))
            assertNotEquals(first, store.beginRoomCreation("owner-game-otherRoom-opponent"))
            assertNotEquals(first, store.beginWalletOperation("owner-game-room-opponent"))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
            assertEquals(first, store.beginRoomCreation("owner-game-room-opponent"))
            store.finishRoomCreation("owner-game-room-opponent", first)
            val next = store.beginRoomCreation("owner-game-room-opponent")
            assertNotEquals(first, next)
            store.finishRoomCreation("owner-game-room-opponent", first)
            assertEquals(next, store.beginRoomCreation("owner-game-room-opponent"))
        } finally { job.cancelAndJoin(); file.delete() }
    }
}

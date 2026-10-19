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

class WalletOperationStoreTest {
    @Test fun `pending intent survives storage reopen and old ack cannot retire new intent`() = runBlocking {
        val file = File.createTempFile("wallet-journal", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        var store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
        try {
            val first = store.beginWalletOperation("same-instruction")
            assertEquals(first, store.beginWalletOperation("same-instruction"))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
            assertEquals(first, store.beginWalletOperation("same-instruction"))
            store.finishWalletOperation("same-instruction", first)
            val next = store.beginWalletOperation("same-instruction")
            assertNotEquals(first, next)
            store.finishWalletOperation("same-instruction", first)
            assertEquals(next, store.beginWalletOperation("same-instruction"))
            assertNotEquals(next, store.beginWalletOperation("different-instruction"))
        } finally { job.cancelAndJoin(); file.delete() }
    }
}

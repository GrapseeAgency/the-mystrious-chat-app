package app.pulse.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.core.PulseEndpoints
import app.pulse.data.repository.AccountRepositories
import io.ktor.client.HttpClient
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDatabaseIsolationTest {
    @Test fun switchingAccountsNeverSharesDraftsAndReturningRestoresOwnDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldGateway = PulseEndpoints.gatewayHttpUrl
        val oldSocket = PulseEndpoints.socketUrl
        val http = HttpClient()
        val persistence = object : SessionTokenPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
            override suspend fun clear() {}
        }
        val cipher = object : SessionTokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decryptOrNull(stored: String) = stored
        }
        PulseEndpoints.applyBase(null) // no fixture or production network traffic
        val repo = AccountRepositories(context, http, SessionTokenStore(persistence, cipher)).repository
        val suffix = UUID.randomUUID().toString()
        try {
            withTimeout(15_000) {
                repo.start("account-a-$suffix")
                repo.saveDraft("same-room-id", "private draft A")
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                repo.start("account-b-$suffix")
                assertNull(repo.observeDraft("same-room-id").first())
                repo.saveDraft("same-room-id", "private draft B")
                repo.start("account-a-$suffix")
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                val accountA = repo.boundToCurrentAccount()
                repo.stop()
                accountA.stageDraft("same-room-id", "typed immediately before disposal")
                assertNull(repo.observeDraft("same-room-id").first())
                assertEquals("typed immediately before disposal", accountA.observeDraft("same-room-id")
                    .first { it == "typed immediately before disposal" })
                repo.start("account-b-$suffix")
                assertEquals("private draft B", repo.observeDraft("same-room-id").first())
            }
        } finally {
            repo.stop()
            http.close()
            PulseEndpoints.gatewayHttpUrl = oldGateway
            PulseEndpoints.socketUrl = oldSocket
        }
    }
}

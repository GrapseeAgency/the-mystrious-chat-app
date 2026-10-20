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
import org.junit.Assert.assertTrue
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
            withTimeout(30_000) {
                repo.start("account-a-$suffix")
                repo.saveDraft("same-room-id", "private draft A")
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                // A queued composer send consumes its draft in the SAME commit,
                // including a staged write/debounce that has not run yet.
                repo.stageDraft("handoff", "send once")
                val token = checkNotNull(repo.captureDraftToken("handoff"))
                assertTrue(repo.sendMessage("handoff", "send once", draftToken = token).isSuccess)
                repo.saveStagedDraft("handoff", "send once", token)
                assertEquals("", repo.observeDraft("handoff").first())
                assertEquals("", repo.observeDrafts().first()["handoff"])
                assertEquals(1, repo.observeOutbox().first().count { it.conversationId == "handoff" })

                repo.saveDraft("handoff", "first")
                val older = checkNotNull(repo.captureDraftToken("handoff"))
                repo.saveDraft("handoff", "newer typing")
                assertTrue(repo.sendRichMessage("handoff", "first", payload = "{\"effect\":\"echo\"}", draftToken = older).isSuccess)
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertTrue(repo.sendMessage("handoff", "thread", parentId = "root").isSuccess)
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertTrue(repo.sendMessage("handoff", "wrong owner", draftToken = older.copy(ownerId = "other")).isFailure)

                // Fail the final draft mutation, AFTER the bubble/outbox inserts.
                // SQLite must roll back all three, retaining the unsent composer.
                val owner = "account-a-$suffix"
                val key = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), owner)
                val connection = android.database.sqlite.SQLiteDatabase.openDatabase(
                    context.getDatabasePath("pulse-account-$key.db").path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                )
                try {
                    connection.execSQL("CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk write failed'); END")
                    val before = repo.observeOutbox().first().size
                    val current = checkNotNull(repo.captureDraftToken("handoff"))
                    val rejected = runCatching { repo.sendMessage("handoff", "newer typing", draftToken = current).getOrThrow() }
                    assertTrue(rejected.isFailure)
                    assertEquals(before, repo.observeOutbox().first().size)
                    assertEquals("newer typing", repo.observeDraft("handoff").first())
                    connection.rawQuery("SELECT COUNT(*) FROM messages WHERE body = 'newer typing'", null).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(0, cursor.getInt(0))
                    }
                } finally {
                    connection.execSQL("DROP TRIGGER fail_draft")
                    connection.close()
                }

                repo.start("account-b-$suffix")
                assertNull(repo.observeDraft("same-room-id").first())
                repo.saveDraft("same-room-id", "private draft B")
                repo.start("account-a-$suffix")
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertEquals(3, repo.observeOutbox().first().count { it.conversationId == "handoff" })
                repo.stageDraft("clear-all", "pending old write")
                val discarded = checkNotNull(repo.captureDraftToken("clear-all"))
                repo.clearAllDrafts()
                repo.saveStagedDraft("clear-all", "pending old write", discarded)
                assertEquals("", repo.observeDraft("clear-all").first())
                assertEquals("", repo.observeDraft("handoff").first())
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

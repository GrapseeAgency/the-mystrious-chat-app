package app.pulse.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.local.*
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.model.Message
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room + repository + transport, with response barriers rather than sleeps.
 * Startup is held at credential loading to isolate explicit cache requests from
 * unrelated inbox/demo/background work. No production credentials or network. */
@RunWith(AndroidJUnit4::class)
class ReadSnapshotOrderingTest {
    @Test fun unchangedNegativeProofRetiresOlderPageWithoutDroppingOtherMessages() = runBlocking {
        negativeProofWins(cached = true)
    }

    @Test fun unavailableUncachedProofCannotBeResurrectedByAnOlderPage() = runBlocking {
        negativeProofWins(cached = false)
    }

    private suspend fun negativeProofWins(cached: Boolean) = withTimeout(30_000) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        val prefsJob = SupervisorJob()
        val prefsFile = File.createTempFile("read-order-", ".preferences_pb", context.cacheDir).apply { delete() }
        val prefs = PulsePrefsLocalStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + prefsJob)) { prefsFile })
        val startupEntered = CompletableDeferred<Unit>()
        val startupStopped = CompletableDeferred<Unit>()
        val tokens = SessionTokenStore(object : SessionTokenPersistence {
            override suspend fun read(): String? {
                startupEntered.complete(Unit)
                try { awaitCancellation() } finally { startupStopped.complete(Unit) }
            }
            override suspend fun write(value: String) {}
            override suspend fun clear() {}
        }, object : SessionTokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decryptOrNull(stored: String) = stored
        })
        val pageEntered = CompletableDeferred<Unit>()
        val releasePage = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/conversations/room/messages" -> {
                    pageEntered.complete(Unit)
                    releasePage.await()
                    """{"syncVersion":1,"hasMore":false,"messages":[
                      {"id":"anchor","conversationId":"room","senderId":"peer","content":"old page","createdAt":"2026-10-26T00:00:00Z","readByMembershipIds":["old-peer"]},
                      {"id":"unrelated","conversationId":"room","senderId":"peer","content":"keep this row","createdAt":"2026-10-26T00:00:01Z"}]}"""
                }
                "/api/messages/anchor/read-state" -> """{"messageReadStateVersion":1,"conversationId":"room","userId":"owner","messageId":"anchor","messageAvailable":$cached,"readByMembershipIds":[]}"""
                else -> error("Unexpected request: ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val repo = PulseRepositoryImpl(PulseApi(http, gateway = { "https://read-order.test" }), db,
            db.conversationDao(), db.messageDao(), db.outboxDao(), db.draftDao(), db.topicDao(),
            db.savedDao(), db.callLogDao(), db.storyDao(), db.wave7Dao(),
            PulseSocketClient("", allowEndpointFallback = false), prefs, tokens, context)
        try {
            if (cached) db.messageDao().upsertAll(listOf(MessageEntity.from(Message(
                "anchor", "room", "peer", "Peer", Message.Kind.TEXT, "current body", "2026-10-26T00:00:00Z"))))
            repo.start("owner")
            startupEntered.await()
            coroutineScope {
                val oldPage = async { repo.refreshMessages("room", limit = 200) }
                pageEntered.await()
                try {
                    assertEquals(cached, repo.refreshMessageReadState("room", "anchor").getOrThrow())
                } finally { releasePage.complete(Unit) }
                oldPage.await().getOrThrow()
            }
            val anchor = db.messageDao().byId("anchor")?.toDomain()
            if (cached) {
                assertNotNull(anchor)
                assertTrue(anchor!!.readByMembershipIds.isEmpty())
                assertEquals("current body", anchor.body)
            } else assertNull(anchor)
            assertEquals("keep this row", db.messageDao().byId("unrelated")?.toDomain()?.body)
        } finally {
            repo.stop()
            withContext(NonCancellable) {
                if (startupEntered.isCompleted) withTimeout(5_000) { startupStopped.await() }
                prefsJob.cancelAndJoin()
            }
            http.close()
            db.close()
            prefsFile.delete()
        }
    }
}

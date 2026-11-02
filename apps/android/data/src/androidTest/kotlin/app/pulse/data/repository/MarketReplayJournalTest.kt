package app.pulse.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.PulsePrefsLocalStore
import app.pulse.data.local.SessionTokenCipher
import app.pulse.data.local.SessionTokenPersistence
import app.pulse.data.local.SessionTokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Section 11 item 4 - the market-create identity journal (real Room + real
 * PulseRepositoryImpl). Room needs an Android runtime, so this lives in the
 * instrumented suite (the JVM data test source set has no Robolectric):
 *  - createListing enqueues a "market-create" row BEFORE the POST (the row
 *    exists the moment the identity hits the wire) and confirms it after the
 *    receipt;
 *  - a lost ACK (transport failure) keeps the row and the restore-time flush
 *    replays the SAME clientId, then confirms it.
 */
@RunWith(AndroidJUnit4::class)
class MarketReplayJournalTest {

    private class Fixture(context: Context, http: HttpClient, private val owner: String, tokens: SessionTokenStore, private val db: PulseDatabase) {
        private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val preferencesFile = File(context.filesDir, "market-journal-test-${UUID.randomUUID()}.preferences_pb")
        private val preferences = PulsePrefsLocalStore(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = preferencesScope) { preferencesFile })
        val repository = PulseRepositoryImpl(
            app.pulse.data.remote.PulseApi(http, bearerToken = { null }, gateway = { "https://market-journal.test" }),
            db, db.conversationDao(), db.messageDao(), db.outboxDao(), db.draftDao(), db.topicDao(), db.savedDao(),
            db.callLogDao(), db.storyDao(), db.wave7Dao(), db.attachmentConsumptionDao(), db.recordingJournalDao(),
            app.pulse.data.remote.PulseSocketClient("", tokenProvider = { null }, allowEndpointFallback = false),
            preferences, tokens, context,
        )
        suspend fun journalRows(): Int = db.wave7Dao().readJournal("section8-intent:v1|$owner|").size
        fun close() {
            repository.stop()
            preferencesScope.cancel()
            db.close()
            preferencesFile.delete()
        }
    }

    private fun tokenStore(): SessionTokenStore {
        val persistence = object : SessionTokenPersistence {
            override suspend fun read(): String? = "journal-test-token"
            override suspend fun write(value: String) {}
            override suspend fun clear() {}
        }
        val cipher = object : SessionTokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decryptOrNull(stored: String) = stored
        }
        return SessionTokenStore(persistence, cipher)
    }

    /** Server stub: a canonical listing receipt echoing the posted clientId. */
    private fun receipt(postedBody: String, owner: String, replayed: Boolean): String =
        buildJsonObject {
            put("marketVersion", 1)
            put("userId", owner)
            put("listing", buildJsonObject {
                put("id", "l1"); put("title", "Thing"); put("price", 25)
                put("asset", "PC"); put("status", "open")
                put("createdAt", "2026-02-20T09:00:00.000Z")
                put("seller", buildJsonObject { put("id", owner); put("name", "Me") })
                put("mine", true)
            })
            val posted = kotlinx.serialization.json.Json.parseToJsonElement(postedBody) as JsonObject
            (posted["clientId"] as? JsonPrimitive)?.let {
                put("clientId", it.content)
                put("replayed", replayed)
            }
        }.toString()

    @Test fun marketCreateJournalsBeforeThePostAndConfirmsAfterTheReceipt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldGateway = PulseEndpoints.gatewayHttpUrl
        PulseEndpoints.applyBase(null)
        val owner = "market-journal-${UUID.randomUUID()}"
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        var rowExistedWhenPosted = false
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/hub/market" && request.method.value == "POST") {
                val body = (request.body as? TextContent)?.text.orEmpty()
                bodies += body
                // The identity must already be durable when it hits the wire.
                rowExistedWhenPosted = db.wave7Dao().readJournal("section8-intent:v1|$owner|").isNotEmpty()
                return@MockEngine respond(receipt(body, owner, replayed = false), HttpStatusCode.Created,
                    headersOf("Content-Type", "application/json"))
            }
            respond("""{"error":"unmocked"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        val fixtures = Fixture(context, http, owner, tokenStore(), db)
        try {
            withTimeout(30_000) {
                fixtures.repository.start(owner)
                // Let the startup pump's first (empty) drain finish so the
                // assertions below observe exactly the createListing POST.
                kotlinx.coroutines.delay(500)
                assertTrue(fixtures.repository.createListing("Thing", null, 25).isSuccess)
                assertTrue("the row was journaled before the POST", rowExistedWhenPosted)
                // The startup pump may race the very first create with its own
                // drain; both bodies carry the SAME identity and the server
                // answers one listing (that dedupe is the product contract).
                assertTrue("the identity rode every body",
                    bodies.isNotEmpty() && bodies.all { it.contains("\"clientId\"") })
                assertEquals("a confirmed receipt is the ONLY way a row leaves the journal",
                    0, fixtures.journalRows())
            }
        } finally {
            fixtures.close(); http.close()
            PulseEndpoints.gatewayHttpUrl = oldGateway
        }
    }

    @Test fun aLostAckKeepsTheRowAndFlushReplaysTheSameMarketIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldGateway = PulseEndpoints.gatewayHttpUrl
        PulseEndpoints.applyBase(null)
        val owner = "market-replay-${UUID.randomUUID()}"
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/hub/market" && request.method.value == "POST") {
                val body = (request.body as? TextContent)?.text.orEmpty()
                bodies += body
                if (attempts.getAndIncrement() == 0) {
                    // Lost ACK: transport-class failure, the row must survive.
                    return@MockEngine respond("""{"error":"The gateway could not commit."}""",
                        HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
                }
                return@MockEngine respond(receipt(body, owner, replayed = true), HttpStatusCode.Created,
                    headersOf("Content-Type", "application/json"))
            }
            respond("""{"error":"unmocked"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        val fixtures = Fixture(context, http, owner, tokenStore(), db)
        try {
            withTimeout(30_000) {
                fixtures.repository.start(owner)
                kotlinx.coroutines.delay(500) // drain the startup pump first
                assertTrue(fixtures.repository.createListing("Thing", null, 25).isFailure)
                assertEquals("a transport failure keeps the row for the next flush", 1, fixtures.journalRows())
                // Restore-time flush: replay the SAME identity, then confirm.
                fixtures.repository.flushOutbox()
                assertTrue("the flush replayed the SAME identity",
                    bodies.size >= 2 && bodies.all { it.contains("\"clientId\"") })
                val first = (kotlinx.serialization.json.Json.parseToJsonElement(bodies[0])
                    as JsonObject)["clientId"]
                val second = (kotlinx.serialization.json.Json.parseToJsonElement(bodies[1])
                    as JsonObject)["clientId"]
                assertEquals("the replay reuses the ORIGINAL identity", first, second)
                assertEquals("the replayed receipt confirms the row", 0, fixtures.journalRows())
            }
        } finally {
            fixtures.close(); http.close()
            PulseEndpoints.gatewayHttpUrl = oldGateway
        }
    }
}

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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room + repository + transport, with response barriers rather than sleeps.
 * Startup is held at credential loading to isolate explicit cache requests from
 * unrelated inbox/demo/background work. No production credentials or network. */
@RunWith(AndroidJUnit4::class)
class ReadSnapshotOrderingTest {
    @Test fun unchangedNegativeProofRetiresOlderPageWithoutDroppingOtherMessages() = runBlocking {
        exerciseReadOrdering(cached = true)
    }

    @Test fun unavailableUncachedProofCannotBeResurrectedByAnOlderPage() = runBlocking {
        exerciseReadOrdering(cached = false)
    }

    @Test fun retiredSessionCannotCommitEvenUnrelatedRowsFromAnInflightPage() = runBlocking {
        exerciseReadOrdering(cached = true, retire = true)
    }

    @Test fun laterCanonicalPageRetiresAnOlderExactInfoResponse() = runBlocking {
        exerciseReadOrdering(cached = true, infoFirst = true)
    }

    @Test fun unchangedCanonicalPageAlsoRetiresOlderPositiveInfoProof() = runBlocking {
        exerciseReadOrdering(cached = true, infoFirst = true, unchangedPage = true)
    }

    @Test fun olderOverlappingPageCannotResurrectSeenOrOverwriteCurrentContent() = runBlocking {
        exerciseReadOrdering(cached = true, overlappingPages = true)
    }

    @Test fun newerPageStillAppliesWhenTheEarlierPageCommitsFirst() = runBlocking {
        exerciseReadOrdering(cached = true, overlappingPages = true, olderCommitsFirst = true)
    }

    @Test fun retiredSearchCannotCommitAnInflightPage() = runBlocking {
        exerciseReadOrdering(cached = true, overlappingPages = true, retire = true)
    }

    @Test fun unrelatedPageDoesNotRetireAnExactInfoResponse() = runBlocking {
        exerciseReadOrdering(cached = true, infoFirst = true, unchangedPage = true, unrelatedOnly = true)
    }

    @Test fun deliveryAcknowledgementKeepsCurrentProofInRoomAndInItsPublishedEvent() = runBlocking {
        exerciseReadOrdering(cached = false, onReady = { repo, db ->
            for ((index, proof) in listOf(emptyList<String>(), listOf("current-member")).withIndex()) {
                val canonical = Message("delivered-$index", "room", "owner", "Owner", Message.Kind.TEXT,
                    "message", "2026-10-26T00:00:00Z", readByMembershipIds = proof)
                val entry = app.pulse.domain.model.OutboxEntry(conversationId = "room", clientId = "operation-$index",
                    content = canonical.body, createdAt = canonical.createdAt)
                db.messageDao().upsertAll(listOf(MessageEntity.from(canonical),
                    MessageEntity.from(canonical.copy(id = "local_${entry.clientId}"))))
                db.outboxDao().insert(OutboxEntity.from(entry))
                coroutineScope {
                    val event = async(start = CoroutineStart.UNDISPATCHED) {
                        repo.events().filterIsInstance<app.pulse.domain.repository.PulseEvent.OutboxFlushed>()
                            .first { it.clientId == entry.clientId }
                    }
                    repo.resolveOutboxDelivery(entry, canonical.copy(readByMembershipIds =
                        if (proof.isEmpty()) listOf("obsolete-member") else emptyList()))
                    assertEquals(proof, event.await().message.readByMembershipIds)
                }
                assertEquals(proof, db.messageDao().byId(canonical.id)?.toDomain()?.readByMembershipIds)
                assertNull(db.messageDao().byId("local_${entry.clientId}"))
                assertNull(db.outboxDao().pendingByClientId(entry.clientId))
            }
        })
    }

    private suspend fun exerciseReadOrdering(cached: Boolean, retire: Boolean = false,
        infoFirst: Boolean = false, unchangedPage: Boolean = false,
        overlappingPages: Boolean = false, olderCommitsFirst: Boolean = false, unrelatedOnly: Boolean = false,
        onReady: (suspend (PulseRepositoryImpl, PulseDatabase) -> Unit)? = null) = withTimeout(30_000) {
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
        val newerEntered = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        val infoEntered = CompletableDeferred<Unit>()
        val releaseInfo = CompletableDeferred<Unit>()
        val pageProof = if (unchangedPage) "[]" else "[\"old-peer\"]"
        val infoProof = if (unchangedPage) "[\"retired-peer\"]" else "[]"
        val http = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/conversations/room/messages" -> {
                    if (overlappingPages && request.url.parameters["q"] == null) {
                        newerEntered.complete(Unit)
                        releaseNewer.await()
                        """{"syncVersion":1,"hasMore":false,"messages":[
                          {"id":"anchor","conversationId":"room","senderId":"peer","content":"new page","createdAt":"2026-10-26T00:00:00Z","readByMembershipIds":[]}]}"""
                    } else {
                        pageEntered.complete(Unit)
                        if (!infoFirst) releasePage.await()
                        val anchor = if (unrelatedOnly) "" else """{"id":"anchor","conversationId":"room","senderId":"peer","content":"old page","createdAt":"2026-10-26T00:00:00Z","readByMembershipIds":$pageProof},"""
                        """{"syncVersion":1,"hasMore":false,"messages":[$anchor
                          {"id":"unrelated","conversationId":"room","senderId":"peer","content":"keep this row","createdAt":"2026-10-26T00:00:01Z"}]}"""
                    }
                }
                "/api/messages/anchor/read-state" -> {
                    infoEntered.complete(Unit)
                    if (infoFirst) releaseInfo.await()
                    """{"messageReadStateVersion":1,"conversationId":"room","userId":"owner","messageId":"anchor","messageAvailable":$cached,"readByMembershipIds":$infoProof}"""
                }
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
            if (onReady != null) { onReady(repo, db); return@withTimeout }
            if (overlappingPages) {
                coroutineScope {
                    val oldPage = async { repo.searchInConversation("room", "older") }
                    pageEntered.await()
                    val newPage = async { repo.refreshMessages("room", limit = 200) }
                    newerEntered.await()
                    if (retire) {
                        repo.stop()
                        releasePage.complete(Unit); releaseNewer.complete(Unit)
                        try { oldPage.await(); fail("Retired search must not commit") } catch (_: CancellationException) {}
                        try { newPage.await(); fail("Retired refresh must not commit") } catch (_: CancellationException) {}
                    } else if (olderCommitsFirst) {
                        releasePage.complete(Unit)
                        assertEquals("old page", oldPage.await().first { it.id == "anchor" }.body)
                        releaseNewer.complete(Unit)
                        newPage.await().getOrThrow()
                    } else {
                        releaseNewer.complete(Unit)
                        newPage.await().getOrThrow()
                        releasePage.complete(Unit)
                        val rows = oldPage.await()
                        assertEquals("new page", rows.first { it.id == "anchor" }.body)
                        assertTrue(rows.first { it.id == "anchor" }.readByMembershipIds.isEmpty())
                    }
                }
                val stored = checkNotNull(db.messageDao().byId("anchor")).toDomain()
                assertEquals(if (retire) "current body" else "new page", stored.body)
                assertTrue(stored.readByMembershipIds.isEmpty())
                if (retire) assertNull(db.messageDao().byId("unrelated"))
                else assertEquals("keep this row", db.messageDao().byId("unrelated")?.toDomain()?.body)
                return@withTimeout
            }
            if (infoFirst) {
                coroutineScope {
                    val oldInfo = async { repo.refreshMessageReadState("room", "anchor") }
                    infoEntered.await()
                    try { repo.refreshMessages("room", limit = 200).getOrThrow() }
                    finally { releaseInfo.complete(Unit) }
                    if (unrelatedOnly) assertTrue(oldInfo.await().getOrThrow())
                    else assertTrue("A page committed after this Info request began", oldInfo.await().isFailure)
                }
                val stored = checkNotNull(db.messageDao().byId("anchor")).toDomain()
                val expected = if (unrelatedOnly) listOf("retired-peer") else if (unchangedPage) emptyList<String>() else listOf("old-peer")
                assertEquals(expected, stored.readByMembershipIds)
                assertEquals(if (unrelatedOnly) "current body" else "old page", stored.body)
                return@withTimeout
            }
            coroutineScope {
                val oldPage = async { repo.refreshMessages("room", limit = 200) }
                pageEntered.await()
                try {
                    assertEquals(cached, repo.refreshMessageReadState("room", "anchor").getOrThrow())
                    if (retire) repo.stop()
                } finally { releasePage.complete(Unit) }
                if (retire) {
                    try {
                        oldPage.await()
                        fail("Retired page must be cancelled before its Room commit")
                    } catch (_: CancellationException) { assertTrue(oldPage.isCancelled) }
                } else oldPage.await().getOrThrow()
            }
            val anchor = db.messageDao().byId("anchor")?.toDomain()
            if (cached) {
                assertNotNull(anchor)
                assertTrue(anchor!!.readByMembershipIds.isEmpty())
                assertEquals("current body", anchor.body)
            } else assertNull(anchor)
            if (retire) assertNull(db.messageDao().byId("unrelated"))
            else assertEquals("keep this row", db.messageDao().byId("unrelated")?.toDomain()?.body)
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

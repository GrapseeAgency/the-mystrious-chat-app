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
import app.pulse.domain.model.Conversation
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room + repository + transport, with response barriers rather than sleeps.
 * Startup is held at credential loading to isolate explicit cache requests from
 * unrelated inbox/demo/background work. No production credentials or network. */
@RunWith(AndroidJUnit4::class)
class ReadSnapshotOrderingTest {
    @Test fun savedIntentsCommitOfflineBeforeHTTPAndKeepImmutableCausalOrder() = runBlocking {
        exerciseReadOrdering(cached = true, onReady = { repo, db, _ ->
            assertTrue(repo.setMessageSaved("anchor", true).getOrThrow())
            assertEquals(1, db.savedDao().count())
            val firstRows = db.wave7Dao().savedJournalPage("saved-intent:v1|owner|")
            assertEquals(1, firstRows.size)
            val first = app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.SavedIntentDto.serializer(), firstRows.single().json)
            assertEquals("0", first.expectedRevision)
            assertFalse(repo.setMessageSaved("anchor", false).getOrThrow())
            assertEquals(0, db.savedDao().count())
            val secondId = db.wave7Dao().get("saved-head:v1|owner|anchor")!!.json
            val secondRow = db.wave7Dao().get("saved-intent:v1|owner|anchor|$secondId")!!
            val second = app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.SavedIntentDto.serializer(), secondRow.json)
            assertEquals(first.clientId, second.predecessorClientId)
            db.wave7Dao().deferReadIntent(firstRows.single().key, firstRows.single().json, "saved-intent:v1|owner|")
            repo.setMessageSaved("anchor", true).getOrThrow()
            val lastId = db.wave7Dao().get("saved-head:v1|owner|anchor")!!.json
            val last = app.pulse.protocol.PulseJson.decodeFromString(app.pulse.protocol.SavedIntentDto.serializer(), db.wave7Dao().get("saved-intent:v1|owner|anchor|$lastId")!!.json)
            assertEquals(second.clientId, last.predecessorClientId)
            db.wave7Dao().clearAll()
            assertEquals(3, db.wave7Dao().savedJournalPage("saved-intent:v1|owner|").size)
            assertEquals(lastId, db.wave7Dao().get("saved-head:v1|owner|anchor")!!.json)
        })
    }

    @Test fun unreadableSavedIntentIsRetainedWithoutBlockingOtherMessages() = runBlocking {
        exerciseReadOrdering(cached = true, onReady = { repo, db, _ ->
            val bad = Wave7CacheEntity("saved-intent:v1|owner|damaged|bad", "{broken", 0)
            val intent = app.pulse.protocol.SavedIntentDto("owner", "anchor", "room", false, "valid", expectedRevision = "0")
            val json = app.pulse.protocol.PulseJson.encodeToString(app.pulse.protocol.SavedIntentDto.serializer(), intent)
            db.wave7Dao().upsert(bad); db.wave7Dao().upsert(Wave7CacheEntity(intent.key, json, 1))
            assertEquals(2, repo.flushSavedIntents().getOrThrow())
            assertEquals(bad.json, db.wave7Dao().get(bad.key)!!.json)
            // The valid row reached the offline transport and was deferred too.
            assertTrue(db.wave7Dao().get(intent.key)!!.updatedAt > 1)
            assertNotNull(db.wave7Dao().get("saved-sync-notice:v1|owner"))
        })
    }

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
        exerciseReadOrdering(cached = false, onReady = { repo, db, _ ->
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

    @Test fun genericOrForeignRefusalCannotDiscardTheDurableOriginalRead() = runBlocking {
        for (mode in listOf("generic", "foreign", "retired")) {
            val body = if (mode == "generic") """{"error":"Credential rejected"}""" else
                """{"error":"Retired","readRetirement":{"version":1,"userId":"owner","conversationId":"room","messageId":"anchor","readIntent":"${if (mode == "foreign") "foreign.member" else "original.member"}"}}"""
            exerciseReadOrdering(cached = true, readFailureBody = body, onReady = { repo, db, tokens ->
                tokens.save("fixture-credential")
                val entry = Wave7CacheEntity("read-journal:v1|owner|member|anchor|original.member",
                    """{"owner":"owner","room":"room","message":"anchor","minimumReadAt":"2026-10-26T00:00:00Z","intent":"original.member"}""", 1L)
                db.wave7Dao().insertReadIntent(entry)
                assertEquals(if (mode == "retired") 0 else 1, repo.flushReadReceipts().getOrThrow())
                assertEquals(if (mode == "retired") emptyList<Pair<String, String>>() else listOf(entry.key to entry.json),
                    db.wave7Dao().readJournal("read-journal:v1|owner|").map { it.key to it.json })
                assertEquals(emptyList<String>(), db.messageDao().byId("anchor")?.toDomain()?.readByMembershipIds)
            })
        }
    }

    @Test fun unboundRefusalDoesNotStarveAnIndependentObservation() = runBlocking {
        exerciseReadOrdering(cached = true, readFailureBody = """{"error":"Old room gateway refusal"}""",
            readSuccessRoom = "other-room", onReady = { repo, db, tokens ->
                tokens.save("fixture-credential")
                fun entry(room: String, message: String, at: Long) = Wave7CacheEntity(
                    "read-journal:v1|owner|member|$message|original.member",
                    """{"owner":"owner","room":"$room","message":"$message","minimumReadAt":"2026-10-26T00:00:00Z","intent":"original.member"}""", at)
                val blocked = entry("room", "anchor", 1)
                val healthy = entry("other-room", "healthy", 2)
                db.wave7Dao().insertReadIntent(blocked)
                db.wave7Dao().insertReadIntent(healthy)
                db.messageDao().upsertAll(listOf(MessageEntity.from(Message("healthy", "other-room", "peer", "Peer",
                    Message.Kind.TEXT, "healthy", "2026-10-26T00:00:00Z"))))
                assertEquals(1, repo.flushReadReceipts().getOrThrow())
                val remaining = db.wave7Dao().readJournal("read-journal:v1|owner|").single()
                assertEquals(blocked.key, remaining.key)
                assertEquals(blocked.json, remaining.json)
                assertTrue(remaining.updatedAt > healthy.updatedAt)
                assertEquals(emptyList<String>(), db.messageDao().byId("anchor")?.toDomain()?.readByMembershipIds)
                assertEquals(listOf("member"), db.messageDao().byId("healthy")?.toDomain()?.readByMembershipIds)
            })
    }

    @Test fun incompatibleGatewayPassIsBoundedAndLeavesUntouchedRowsAtTheHead() = runBlocking {
        exerciseReadOrdering(cached = true, readFailureBody = """{"error":"Unbound refusal"}""",
            onReady = { repo, db, tokens ->
                tokens.save("fixture-credential")
                repeat(20) { index ->
                    db.wave7Dao().insertReadIntent(Wave7CacheEntity("read-journal:v1|owner|member|m-$index|original.member",
                        """{"owner":"owner","room":"room","message":"m-$index","minimumReadAt":"2026-10-26T00:00:00Z","intent":"original.member"}""", index.toLong()))
                }
                assertEquals(20, repo.flushReadReceipts().getOrThrow())
                val pending = db.wave7Dao().readJournal("read-journal:v1|owner|")
                assertTrue(pending.first().key.contains("|m-16|"))
                assertEquals(20, pending.size)
                assertTrue(pending.all { it.json.contains("original.member") })
            })
    }

    @Test fun lostMarkAllAcknowledgementInvalidatesCanonicalScopesWithoutInventingProof() = runBlocking {
        val requests = mutableListOf<String>()
        exerciseReadOrdering(cached = true, lostReadAck = true, readRequests = requests,
            onReady = { repo, db, tokens ->
                tokens.save("fixture-credential")
                val before = repo.observeReadStateRevision("room").first()
                assertTrue(repo.markRead("room").isFailure)
                assertTrue(repo.observeReadStateRevision("room").first() > before)
                assertEquals(emptyList<String>(), db.messageDao().byId("anchor")?.toDomain()?.readByMembershipIds)
                assertEquals(listOf("read-intent", "read-state"), requests)
            })
    }

    @Test fun loadedInboxObservesCanonicalRefreshAndRetainsItWhenTheNextFetchFails() = runBlocking {
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val body = """{"conversations":[
            {"id":"room","name":"Room","isGroup":false,"members":[],"unreadCount":0,"myManualUnread":true},
            {"id":"other","name":"Other","isGroup":false,"members":[],"unreadCount":4},
            {"id":"archive","name":"Archive","isGroup":false,"members":[],"unreadCount":40,"archivedAt":"2026-01-01T00:00:00Z"},
            {"id":"self","name":"Notes","isGroup":false,"members":[],"unreadCount":50,"isSelf":true}
        ]}"""
        exerciseReadOrdering(cached = false, inboxResponse = {
            check(online.get()) { "Second fetch unavailable" }
            body
        }, onReady = { repo, db, tokens ->
            tokens.save("fixture-credential")
            db.conversationDao().upsertAll(listOf(ConversationEntity.from(
                Conversation("room", Conversation.Kind.DM, "Room", unreadCount = 9))))
            coroutineScope {
                val updates = Channel<List<Conversation>>(Channel.UNLIMITED)
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    repo.observeConversations("").collect { updates.send(it) }
                }
                try {
                    assertEquals(9, updates.receive().single().unreadCount)
                    repo.refreshConversations().getOrThrow()
                    var canonical: List<Conversation>
                    do {
                        canonical = updates.receive()
                        assertTrue("Atomic snapshot replacement must not emit a fabricated empty inbox", canonical.isNotEmpty())
                    } while (canonical.size != 4)
                    assertEquals(0, canonical.single { it.id == "room" }.unreadCount)
                    assertTrue(canonical.single { it.id == "room" }.myManualUnread)
                    assertEquals(5, canonical.filter { !it.isArchived && !it.isSelf }.sumOf { it.badgeUnreadCount })
                    online.set(false)
                    assertTrue(repo.refreshConversations().isFailure)
                    assertEquals(canonical, repo.observeConversations("").first())
                } finally {
                    observer.cancelAndJoin()
                    updates.close()
                }
            }
        })
    }

    @Test fun inboxFetchStartsDoNotRetireUsefulRepliesButAcceptedNewerSnapshotsDo() = runBlocking {
        for (mode in listOf("older-first", "newer-first", "newer-fails")) {
            val olderEntered = CompletableDeferred<Unit>()
            val newerEntered = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val releaseNewer = CompletableDeferred<Unit>()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            exerciseReadOrdering(cached = false, inboxResponse = {
                val first = calls.incrementAndGet() == 1
                if (first) { olderEntered.complete(Unit); releaseOlder.await() }
                else { newerEntered.complete(Unit); releaseNewer.await() }
                check(first || mode != "newer-fails") { "Newer request failed" }
                """{"conversations":[{"id":"room","name":"Room","isGroup":false,"members":[],"unreadCount":${if (first) 2 else 4}}]}"""
            }, onReady = { repo, db, tokens ->
                tokens.save("fixture-credential")
                suspend fun count() = db.conversationDao().byId("room")?.toDomain()?.unreadCount
                coroutineScope {
                    val older = async { repo.refreshConversations() }
                    olderEntered.await()
                    val newer = async { repo.refreshConversations() }
                    newerEntered.await()
                    if (mode == "older-first") {
                        releaseOlder.complete(Unit)
                        older.await().getOrThrow()
                        assertEquals(2, count()) // newer request is only pending
                        releaseNewer.complete(Unit)
                        newer.await().getOrThrow()
                        assertEquals(4, count())
                    } else {
                        releaseNewer.complete(Unit)
                        if (mode == "newer-fails") assertTrue(newer.await().isFailure)
                        else { newer.await().getOrThrow(); assertEquals(4, count()) }
                        releaseOlder.complete(Unit)
                        older.await().getOrThrow()
                        assertEquals(if (mode == "newer-fails") 2 else 4, count())
                    }
                }
            })
        }
    }

    @Test fun mutationSupersessionCannotBeReportedAsACompletedBackgroundInboxRefresh() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        exerciseReadOrdering(cached = false, inboxResponse = {
            if (calls.incrementAndGet() > 1) awaitCancellation() // scheduled trailing refresh is separate work
            entered.complete(Unit)
            release.await()
            """{"conversations":[]}"""
        }, onReady = { repo, db, tokens ->
            tokens.save("fixture-credential")
            db.conversationDao().upsertAll(listOf(ConversationEntity.from(
                Conversation("room", Conversation.Kind.DM, "Room", unreadCount = 9))))
            coroutineScope {
                val pending = async { repo.refreshConversations() }
                entered.await()
                repo.invalidateReadProjections("room")
                release.complete(Unit)
                assertTrue("A discarded response must leave background reconciliation incomplete", pending.await().isFailure)
                assertEquals(9, db.conversationDao().byId("room")?.toDomain()?.unreadCount)
            }
        })
    }

    private suspend fun exerciseReadOrdering(cached: Boolean, retire: Boolean = false,
        infoFirst: Boolean = false, unchangedPage: Boolean = false,
        overlappingPages: Boolean = false, olderCommitsFirst: Boolean = false, unrelatedOnly: Boolean = false,
        readFailureBody: String? = null, readSuccessRoom: String? = null,
        lostReadAck: Boolean = false, readRequests: MutableList<String>? = null,
        inboxResponse: (suspend () -> String)? = null,
        onReady: (suspend (PulseRepositoryImpl, PulseDatabase, SessionTokenStore) -> Unit)? = null) = withTimeout(30_000) {
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
            if (request.url.encodedPath == "/api/conversations" && inboxResponse != null)
                return@MockEngine respond(inboxResponse(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            if (lostReadAck && request.url.encodedPath == "/api/conversations/room/read-intent") {
                readRequests?.add("read-intent")
                return@MockEngine respond("""{"readIntentVersion":2,"userId":"owner","conversationId":"room","readIntent":"original.member"}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            if (lostReadAck && request.url.encodedPath == "/api/conversations/room/read-state") {
                readRequests?.add("read-state")
                return@MockEngine respond("""{"error":"Response lost after possible commit"}""",
                    HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
            }
            if (readSuccessRoom != null && request.url.encodedPath == "/api/conversations/$readSuccessRoom/read-state")
                return@MockEngine respond("""{"ok":true,"readReceiptVersion":3,"userId":"owner","conversationId":"$readSuccessRoom","messageId":"healthy","readIntent":"original.member","mode":"visible","lastReadAt":"2026-10-26T00:00:00Z"}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            if (request.url.encodedPath == "/api/conversations/room/read-state" && readFailureBody != null)
                return@MockEngine respond(readFailureBody, HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json"))
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
            if (onReady != null) { onReady(repo, db, tokens); return@withTimeout }
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

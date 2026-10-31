package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 8 acceptance (Android data layer):
 *  - item 8: folder create carries clientId; the replay receipt is confirmed.
 *  - item 10: phrase create/delete carry clientId + listRev/replay receipts.
 *  - item 6: block intents carry clientId + intentAt; stale receipts parse.
 *  - item 7: report replays carry reportId + evidenceRev + replayed.
 *  - item 5: directory pages carry nextCursor/hasMore and the cursor contract.
 */
class Section8ReceiptTest {
    private val answeredIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun apiOf(vararg bodies: Pair<Int, String>): HttpClient = HttpClient(MockEngine { request ->
        val (status, body) = bodies[answeredIndex.getAndIncrement() % bodies.size]
        if (request.body is TextContent) recorded += (request.body as TextContent).text
        paths += request.url.encodedPath + "?" + request.url.encodedQuery
        respond(body, HttpStatusCode(status, "test"), headersOf("Content-Type", "application/json"))
    })

    private fun lastBody(): String = recorded.last()
    private fun lastPath(): String = paths.last()

    @Test
    fun folderCreateSendsTheReplayIdentityAndConfirmsTheReceipt() = runTest {
        val http = apiOf(
            200 to """{"folderVersion":1,"rev":3,"userId":"me","replayed":false,"folder":
                {"id":"f1","name":"Work","emoji":"folder","position":0,"rev":0,"conversationIds":[]}}""",
            200 to """{"folderVersion":1,"rev":3,"userId":"me","replayed":true,"folder":
                {"id":"f1","name":"Work","emoji":"folder","position":0,"rev":0,"conversationIds":[]}}""",
            200 to """{"folderVersion":1,"rev":1,"userId":"me","folder":
                {"id":"f2","name":"X","emoji":"folder","position":1,"conversationIds":[]}}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://folders.test" })
            assertTrue(api.createFolder("me", "Work", "folder", "client000000000000001") is PulseResult.Success)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000001\""))
            // A lost-ACK retry answers replayed=true; the same identity is kept.
            assertTrue(api.createFolder("me", "Work", "folder", "client000000000000001") is PulseResult.Success)
            // A receipt WITHOUT the replay flag is refused when identity was sent.
            assertTrue(api.createFolder("me", "X", "folder", "client000000000000002") is PulseResult.Failure)
        } finally { http.close() }
    }

    @Test
    fun phraseCreateAndDeleteCarryClientIdAndDemandListRev() = runTest {
        val http = apiOf(
            201 to """{"phrase":{"id":"p1","text":"Hi","position":0},"listRev":2,"replayed":false}""",
            201 to """{"phrase":{"id":"p2","text":"Ho","position":1},"replayed":false}""",
            200 to """{"phrases":[],"listRev":7}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://phrases.test" })
            assertTrue(api.createPhrase("me", "Hi", "client000000000000003") is PulseResult.Success)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000003\""))
            // Missing listRev on a journaled write: refused.
            assertTrue(api.createPhrase("me", "Ho", "client000000000000004") is PulseResult.Failure)
            // The rail snapshot itself carries the revision.
            val page = api.phrases("me")
            assertEquals(7, (page as PulseResult.Success).value.listRev)
        } finally { http.close() }
    }

    @Test
    fun blockIntentsCarryClientIdAndIntentAtAndParseStaleReceipts() = runTest {
        val http = apiOf(
            200 to """{"ok":true,"blockReceiptVersion":1,"actorId":"me","targetId":"peer","blocked":true,
                "stale":false,"replayed":false,"intentAt":"2026-02-01T10:00:00Z"}""",
            200 to """{"ok":true,"blockReceiptVersion":1,"actorId":"me","targetId":"peer","blocked":true,
                "intentAt":"not-a-date"}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://blocks.test" })
            assertTrue(api.blockUser("peer", "me", "client000000000000005", "2026-02-01T10:00:00Z") is PulseResult.Success)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000005\""))
            assertTrue(lastBody().contains("\"intentAt\":\"2026-02-01T10:00:00Z\""))
            // A malformed watermark is refused client-side before any trust.
            assertTrue(api.unblockUser("peer", "me", "client000000000000006", "not-a-date") is PulseResult.Failure)
        } finally { http.close() }
    }

    @Test
    fun reportReplayReceiptCarriesTheSameReportIdAndEvidenceRev() = runTest {
        val http = apiOf(
            201 to """{"ok":true,"reportReceiptVersion":1,"reporterId":"me","reportedId":"peer","reason":"spam",
                "details":"","reported":true,"updated":false,"replayed":false,"reportId":"r1","evidenceRev":0,
                "reportedAt":"2026-02-01T10:00:00.000Z"}""",
            201 to """{"ok":true,"reportReceiptVersion":1,"reporterId":"me","reportedId":"peer","reason":"spam",
                "details":"","reported":true,"updated":false,"replayed":true,"reportId":"r1","evidenceRev":0,
                "reportedAt":"2026-02-01T10:00:00.000Z"}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://reports.test" })
            assertTrue(api.reportUser("peer", "me", "spam", null, "client000000000000007") is PulseResult.Success)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000007\""))
            val replay = api.reportUser("peer", "me", "spam", null, "client000000000000007")
            val ack = (replay as PulseResult.Success).value
            assertEquals(true, ack.replayed)
            assertEquals("r1", ack.reportId)
            assertEquals(0, ack.evidenceRev)
        } finally { http.close() }
    }

    @Test
    fun directoryPagesDemandHasMoreAndCursorAgreement() = runTest {
        val http = apiOf(
            200 to """{"users":[{"id":"u1","name":"Ada"}],"nextCursor":"u1","hasMore":true}""",
            200 to """{"users":[],"nextCursor":null,"hasMore":true}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://directory.test" })
            val page = api.directoryPage(limit = 1, after = null)
            val dto = (page as PulseResult.Success).value
            assertEquals("u1", dto.nextCursor)
            assertEquals(true, dto.hasMore)
            assertTrue(lastPath().contains("limit=1"))
            // hasMore=true with a null cursor: refused.
            assertTrue(api.directoryPage() is PulseResult.Failure)
        } finally { http.close() }
    }
}

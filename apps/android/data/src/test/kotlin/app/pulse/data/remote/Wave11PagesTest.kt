package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Section 11 items 3/7 - bounded hub pages (PulseApi):
 *  - the market board, room games and room tournaments lists put limit/cursor
 *    on the query and decode the {hasMore, nextCursor} continuation;
 *  - the legacy reads (no limit/cursor) keep the bare shape (no paging fields);
 *  - hasMore=true without a nextCursor is a protocol failure (Wave10 rule).
 */
class Wave11PagesTest {

    private var lastUrl = ""
    private var lastBody = ""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
                lastUrl = request.url.toString()
                lastBody = when (val b = request.body) {
                    is io.ktor.http.content.TextContent -> b.text
                    is io.ktor.http.content.ByteArrayContent -> b.bytes().decodeToString()
                    else -> ""
                }
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }),
            bearerToken = { "synthetic-token" },
        )

    @Before
    fun reset() {
        PulseEndpoints.applyBase(null)
        lastUrl = ""
        lastBody = ""
    }

    private val listing = """{"id":"l1","title":"Thing","price":25,"asset":"PC","status":"open",
        "createdAt":"2026-02-20T09:00:00.000Z","seller":{"id":"u1","name":"Me"},"mine":true}"""

    private val match = """{"id":"m1","conversationId":"room","game":"tictactoe","playerXId":"u1",
        "playerOId":null,"board":"         ","turn":"X","status":"active","winnerId":null,"winLine":null,
        "moveCount":0,"createdAt":"2026-02-20T09:00:00.000Z","updatedAt":"2026-02-20T09:00:00.000Z"}"""

    private val season = """{"id":"t1","conversationId":"room","name":"Season","game":"tictactoe",
        "status":"running","joined":false,"playerCount":2,"createdAt":"2026-02-20T09:00:00.000Z",
        "endsAt":null,"entries":[]}"""

    @Test fun `market pages request the keyset window and decode the continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"marketVersion":1,"userId":"u1","listings":[$listing],
            "hasMore":true,"nextCursor":"sold.1771606800000.l1"}"""
        val r = api(body = page).market("u1", 30, "open.1771606800000.l1")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore == true)
        assertEquals("sold.1771606800000.l1", dto.nextCursor)
        assertTrue("limit must ride the query", lastUrl.contains("limit=30"))
        assertTrue("cursor must ride the query", lastUrl.contains("cursor=open.1771606800000.l1"))
    }

    @Test fun `the legacy market read keeps the bare shape without paging fields`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val legacy = """{"marketVersion":1,"userId":"u1","listings":[$listing]}"""
        val r = api(body = legacy).market("u1")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertEquals(null, dto.hasMore)
        assertEquals(null, dto.nextCursor)
        assertTrue("no paging params on a legacy read", !lastUrl.contains("limit=") && !lastUrl.contains("cursor="))
    }

    @Test fun `a continued market page without its cursor is a protocol failure`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val broken = """{"marketVersion":1,"userId":"u1","listings":[$listing],"hasMore":true}"""
        assertTrue(api(body = broken).market("u1", 30, null) is PulseResult.Failure)
    }

    @Test fun `games pages decode their keyset continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"gameReadVersion":1,"userId":"u1","conversationId":"room","matches":[$match],
            "hasMore":true,"nextCursor":"1771606800000.m1"}"""
        val r = api(body = page).games("room", "u1", 50, "1771606900000.m9")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore == true)
        assertEquals("1771606800000.m1", dto.nextCursor)
        assertTrue(lastUrl.contains("limit=50"))
        assertTrue(lastUrl.contains("cursor=1771606900000.m9"))
    }

    @Test fun `the legacy games read keeps the bare shape without paging fields`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val legacy = """{"gameReadVersion":1,"userId":"u1","conversationId":"room","matches":[$match]}"""
        val r = api(body = legacy).games("room", "u1")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertEquals(null, dto.hasMore)
        assertEquals(null, dto.nextCursor)
    }

    @Test fun `tournament pages decode their keyset continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"gameReadVersion":1,"userId":"u1","conversationId":"room","tournaments":[$season],
            "hasMore":true,"nextCursor":"1771606800000.t1"}"""
        val r = api(body = page).tournaments("room", "u1", 25, "1771606900000.t9")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore == true)
        assertEquals("1771606800000.t1", dto.nextCursor)
        assertTrue(lastUrl.contains("limit=25"))
        assertTrue(lastUrl.contains("cursor=1771606900000.t9"))
    }

    @Test fun `the legacy tournaments read keeps the bare shape without paging fields`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val legacy = """{"gameReadVersion":1,"userId":"u1","conversationId":"room","tournaments":[$season]}"""
        val r = api(body = legacy).tournaments("room", "u1")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertEquals(null, dto.hasMore)
        assertEquals(null, dto.nextCursor)
    }
}

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
 * Section 11 item 4 - market creation replay contract (PulseApi):
 *  - an explicit clientId rides the POST /api/hub/market body;
 *  - a 200 receipt with replayed:true decodes as the SAME confirmed listing;
 *  - a 410 creation_gone refusal maps onto an honest Failure that carries the
 *    status + machine code (the journal retires the row client-side);
 *  - a legacy call without a clientId never sends the field.
 */
class MarketReplayTest {

    private var requests = 0
    private var lastMethod = ""
    private var lastUrl = ""
    private var lastBody = ""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
                requests++
                lastMethod = request.method.value
                lastUrl = request.url.toString()
                // setBody(String) with contentType json lands as TextContent
                // (Ktor 2.x); raw bytes surface as ByteArrayContent - capture both.
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
        requests = 0
        lastMethod = ""
        lastUrl = ""
        lastBody = ""
    }

    private val openListing = """{"id":"l1","title":"Thing","price":25,"asset":"PC","status":"open",
        "createdAt":"2026-02-20T09:00:00.000Z","seller":{"id":"u1","name":"Me"},"mine":true}"""

    @Test fun `createListing sends the journaled clientId in the body`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"marketVersion":1,"userId":"u1","listing":$openListing,
            "clientId":"mkt0000000000000000001","replayed":false}"""
        val r = api(body = receipt).createListing("u1", "Thing", null, 25, clientId = "mkt0000000000000000001")
        assertTrue(r is PulseResult.Success)
        assertEquals("POST", lastMethod)
        assertEquals("https://prod.example/api/hub/market", lastUrl)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals("u1", (body["userId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("mkt0000000000000000001", (body["clientId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(25, (body["price"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test fun `a replayed 200 receipt decodes as the SAME confirmed listing`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val replay = """{"marketVersion":1,"userId":"u1","listing":$openListing,
            "clientId":"mkt0000000000000000001","replayed":true}"""
        val r = api(body = replay).createListing("u1", "Thing", null, 25, clientId = "mkt0000000000000000001")
        assertTrue("a replay is a confirmed creation of the SAME row", r is PulseResult.Success)
        assertEquals("l1", (r as PulseResult.Success).value.id)
    }

    @Test fun `a legacy createListing without a journal never sends the identity field`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"marketVersion":1,"userId":"u1","listing":$openListing}"""
        val r = api(body = receipt).createListing("u1", "Thing", null, 25)
        assertTrue(r is PulseResult.Success)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertTrue("no clientId on a legacy write", body.keys.none { it == "clientId" })
    }

    @Test fun `a 410 creation_gone refusal maps onto an honest failure with the status`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val gone = """{"error":"The earlier listing was delivered, but is no longer available. Submit again to create a new one.",
            "code":"creation_gone","clientId":"mkt0000000000000000001"}"""
        val r = api(HttpStatusCode.Gone, gone).createListing("u1", "Thing", null, 25, clientId = "mkt0000000000000000001")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(410, f.status)
        assertEquals("creation_gone", f.code)
        assertEquals("mkt0000000000000000001", f.receiptClientId)
    }
}

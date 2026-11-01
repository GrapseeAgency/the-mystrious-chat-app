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
 * Section 11 item 8 - app connect/disconnect replay contract (PulseApi):
 *  - an explicit clientId rides the POST/DELETE install body;
 *  - a replayed:true receipt (with installsRev) decodes as a confirmed
 *    acknowledgement of the SAME identity - never a validation failure;
 *  - the DELETE verb carries the identity in the JSON body.
 */
class HubConnectionReplayTest {

    private var lastMethod = ""
    private var lastUrl = ""
    private var lastBody = ""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
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
        lastMethod = ""
        lastUrl = ""
        lastBody = ""
    }

    @Test fun `installApp carries the journaled clientId and a replayed receipt still confirms`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"appVersion":1,"appId":"7","userId":"u1","installed":true,"status":"connected",
            "installs":3,"installedAt":"2026-02-20T09:00:00.000Z","installers":[{"id":"u1","name":"Me"}],
            "installsRev":5,"replayed":true}"""
        val r = api(body = receipt).installApp("7", "u1", clientId = "conn0000000000000000001")
        assertTrue("a replayed connect is an acknowledgement, not a failure", r is PulseResult.Success)
        assertEquals("POST", lastMethod)
        assertEquals("https://prod.example/api/hub/apps/7/install", lastUrl)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals("conn0000000000000000001", (body["clientId"] as kotlinx.serialization.json.JsonPrimitive).content)
        val row = (r as PulseResult.Success).value
        assertTrue(row.installed)
        assertEquals(true, row.replayed)
        assertEquals(5, row.installsRev)
    }

    @Test fun `uninstallApp carries the identity in the DELETE body and accepts the replay receipt`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"appVersion":1,"appId":"7","userId":"u1","installed":false,"status":null,
            "installs":2,"installedAt":null,"installers":[],"installsRev":6,"replayed":true}"""
        val r = api(body = receipt).uninstallApp("7", "u1", clientId = "conn0000000000000000002")
        assertTrue(r is PulseResult.Success)
        assertEquals("DELETE", lastMethod)
        assertEquals("https://prod.example/api/hub/apps/7/install", lastUrl)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals("conn0000000000000000002", (body["clientId"] as kotlinx.serialization.json.JsonPrimitive).content)
        val row = (r as PulseResult.Success).value
        assertTrue(!row.installed)
        assertEquals(true, row.replayed)
    }

    @Test fun `a legacy install without a journal never sends the identity field`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"appVersion":1,"appId":"7","userId":"u1","installed":true,"status":"connected",
            "installs":1,"installedAt":"2026-02-20T09:00:00.000Z","installers":[{"id":"u1","name":"Me"}]}"""
        val r = api(body = receipt).installApp("7", "u1")
        assertTrue(r is PulseResult.Success)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertTrue("no clientId on a legacy write", body.keys.none { it == "clientId" })
    }
}

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
 * Section 11 item 6 - the ONE batch install snapshot (PulseApi):
 *  - GET /api/hub/apps/installs?userId= decodes the whole-catalog payload
 *    (appVersion 2, installsRev, one row per catalog app with only the
 *    connected flag);
 *  - a failed read fails honestly (SERVER) - no guessed install truth.
 */
class CatalogBatchTest {

    private var requests = 0
    private var lastMethod = ""
    private var lastUrl = ""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
                requests++
                lastMethod = request.method.value
                lastUrl = request.url.toString()
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
    }

    @Test fun `appInstallsBatch decodes the whole-catalog snapshot with its revision`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val payload = """{"appVersion":2,"userId":"u1","installsRev":4,"apps":[
            {"appId":"1","order":0,"installed":true,"installedAt":"2026-02-20T09:00:00.000Z"},
            {"appId":"2","order":1,"installed":false,"installedAt":null},
            {"appId":"3","order":2,"installed":false,"installedAt":null}]}"""
        val r = api(body = payload).appInstallsBatch("u1")
        assertTrue(r is PulseResult.Success)
        assertEquals("GET", lastMethod)
        assertEquals("https://prod.example/api/hub/apps/installs?userId=u1", lastUrl)
        val page = (r as PulseResult.Success).value
        assertEquals(2, page.appVersion)
        assertEquals(4, page.installsRev)
        assertEquals(listOf("1", "2", "3"), page.apps.map { it.appId })
        assertEquals(listOf(true, false, false), page.apps.map { it.installed })
        assertEquals("2026-02-20T09:00:00.000Z", page.apps.first { it.installed }.installedAt)
    }

    @Test fun `a failed catalog read fails honestly`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(HttpStatusCode.InternalServerError, """{"error":"Snapshot could not be read."}""").appInstallsBatch("u1")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(PulseResult.Failure.Kind.SERVER, f.kind)
        assertEquals(500, f.status)
    }

    @Test fun `a batch answered for another owner is refused`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val payload = """{"appVersion":2,"userId":"other","installsRev":4,"apps":[]}"""
        assertTrue(api(body = payload).appInstallsBatch("u1") is PulseResult.Failure)
        val legacy = """{"appVersion":1,"userId":"u1","apps":[]}"""
        assertTrue(api(body = legacy).appInstallsBatch("u1") is PulseResult.Failure)
    }
}

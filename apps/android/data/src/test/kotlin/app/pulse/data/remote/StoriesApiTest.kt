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
 * Wave-4 stories REST contract (PulseApi):
 *  - the generic offline-first gate holds (unconfigured gateway → ZERO engine
 *    requests + honest NETWORK failure — the HomeGatewayGuard rule);
 *  - postStory POSTs /api/stories with the exact body the route validates
 *    (text mode: requesterId+caption+background — background NEVER on photos);
 *  - markStoryViewed / storyViewers / deleteStory hit their exact paths with
 *    the plain requesterId identity param;
 *  - 403/404 map onto FORBIDDEN / NOT_FOUND failure kinds (owner gates).
 */
class StoriesApiTest {

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
                // (Ktor 2.x); raw bytes surface as ByteArrayContent — capture both.
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
        )

    @Before
    fun reset() {
        PulseEndpoints.applyBase(null)
        requests = 0
        lastMethod = ""
        lastUrl = ""
        lastBody = ""
    }

    @Test
    fun `unconfigured gateway - stories fires zero requests and fails honestly`() = runTest {
        val r = api().stories("u1")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(PulseResult.Failure.Kind.NETWORK, f.kind)
        assertTrue((f.message ?: "").contains("No gateway configured"))
        assertEquals(0, requests)
    }

    @Test
    fun `unconfigured gateway - story writes fire zero requests`() = runTest {
        val a = api()
        assertTrue(a.postStory("u1", caption = "hi", background = "rose") is PulseResult.Failure)
        assertTrue(a.markStoryViewed("s1", "u1") is PulseResult.Failure)
        assertTrue(a.storyViewers("s1", "u1") is PulseResult.Failure)
        assertTrue(a.deleteStory("s1", "u1") is PulseResult.Failure)
        assertEquals("no direct route may bypass the guard", 0, requests)
    }

    @Test
    fun `configured - postStory text mode sends path and exact body`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api().postStory("u1", caption = "focusing", background = "violet")
        assertTrue("text post must parse {story:...}", r is PulseResult.Success)
        assertEquals("POST", lastMethod)
        assertEquals("https://prod.example/api/stories", lastUrl)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("requesterId", "caption", "background"), body.keys)
        assertEquals("u1", (body["requesterId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("violet", (body["background"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `configured - postStory photo mode never sends background`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api().postStory("u1", caption = "sunset", imagePath = "abc-123.jpg")
        assertTrue(r is PulseResult.Success)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("requesterId", "caption", "imagePath"), body.keys)
    }

    @Test
    fun `configured - markStoryViewed posts to the view route`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = """{"viewCount": 3}""").markStoryViewed("s-9", "u1")
        assertTrue(r is PulseResult.Success)
        assertEquals(3, (r as PulseResult.Success).value.viewCount)
        assertEquals("POST", lastMethod)
        assertEquals("https://prod.example/api/stories/s-9/view", lastUrl)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody)
            as kotlinx.serialization.json.JsonObject
        assertEquals("u1", (body["requesterId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `configured - storyViewers GETs the owner-only route with requesterId`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val payload = """{"viewers":[{"userId":"u2","name":"Bob","username":null,"color":"teal",
            "viewedAt":"2026-02-20T09:05:00.000Z"}]}"""
        val r = api(body = payload).storyViewers("s-9", "u1")
        assertTrue(r is PulseResult.Success)
        assertEquals(listOf("u2"), (r as PulseResult.Success).value.viewers.map { it.userId })
        assertEquals("GET", lastMethod)
        assertTrue("requesterId must ride the query", lastUrl.endsWith("/api/stories/s-9/view?requesterId=u1"))
    }

    @Test
    fun `configured - deleteStory DELETEs with requesterId query`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = """{"ok": true}""").deleteStory("s-9", "u1")
        assertTrue(r is PulseResult.Success)
        assertEquals("DELETE", lastMethod)
        assertTrue(lastUrl.endsWith("/api/stories/s-9?requesterId=u1"))
    }

    @Test
    fun `403 maps to FORBIDDEN and 404 to NOT_FOUND for story routes`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val forbidden = api(HttpStatusCode.Forbidden, """{"error":"Only the owner can delete this status."}""")
            .deleteStory("s-9", "u2")
        assertEquals(PulseResult.Failure.Kind.FORBIDDEN, (forbidden as PulseResult.Failure).kind)
        val missing = api(HttpStatusCode.NotFound, """{"error":"Story not found."}""").storyViewers("s-x", "u1")
        assertEquals(PulseResult.Failure.Kind.NOT_FOUND, (missing as PulseResult.Failure).kind)
    }
}

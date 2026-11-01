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
 *    requests + honest NETWORK failure - the HomeGatewayGuard rule);
 *  - postStory POSTs /api/stories with the exact body the route validates
 *    (text mode: requesterId+caption+background - background NEVER on photos);
 *  - markStoryViewed / storyViewers / deleteStory hit their exact paths with
 *    the plain requesterId identity param;
 *  - 403/404 map onto FORBIDDEN / NOT_FOUND failure kinds (owner gates).
 */
class StoriesApiTest {

    private var requests = 0
    private var lastMethod = ""
    private var lastUrl = ""
    private var lastBody = ""
    private var lastAuthorization: String? = null

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
                requests++
                lastAuthorization = request.headers["Authorization"]
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
    @Test fun `all story requests carry the current owner credential including delete`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        api().stories("u1"); assertEquals("Bearer synthetic-token", lastAuthorization)
        api().postStory("u1", caption = "hello"); assertEquals("Bearer synthetic-token", lastAuthorization)
        api().markStoryViewed("s1", "u1"); assertEquals("Bearer synthetic-token", lastAuthorization)
        api().storyViewers("s1", "u1"); assertEquals("Bearer synthetic-token", lastAuthorization)
        api(body = """{"ok":true}""").deleteStory("s1", "u1"); assertEquals("Bearer synthetic-token", lastAuthorization)
    }
    @Test fun `delete never fabricates success from an absent malformed or negative acknowledgement`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        for (body in listOf("", "not json", "{}", """{"ok":false}""", """{"ok":"true"}""", """{"ok":1}""")) {
            assertTrue("unconfirmed body $body", api(body = body).deleteStory("s1", "u1") is PulseResult.Failure)
        }
    }
    @Test fun `viewer list requires an explicit array of unique nonempty identities`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        for (body in listOf("{}", """{"viewers":null}""", """{"viewers":[{}]}""", """{"viewers":[{"userId":"a"},{"userId":"a"}]}""")) {
            assertTrue(api(body = body).storyViewers("story", "owner") is PulseResult.Failure)
        }
        assertTrue(api(body = """{"viewers":[]}""").storyViewers("story", "owner") is PulseResult.Success)
    }

    @Test fun `feed requires an explicit unique identity snapshot`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        for (body in listOf("{}", """{"groups":null}""", """{"groups":[{}]}""",
            """{"groups":[{"user":{"id":"u","name":"User"}}]}""",
            """{"groups":[{"user":{"id":"u","name":"User"},"stories":[{"id":"a"},{"id":"a"}]}]}""")) {
            assertTrue(body, api(body = body).stories("owner") is PulseResult.Failure)
        }
        assertTrue(api(body = """{"groups":[]}""").stories("owner") is PulseResult.Success)
        assertTrue(api(body = """{"groups":[{"user":{"id":"u","name":"User"},"stories":[{"id":"a"}]}]}""").stories("owner") is PulseResult.Success)
    }
    @Test fun `view mark cannot invent a successful receipt`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        for (body in listOf("{}", """{"viewCount":null}""", """{"viewCount":-1}""", """{"viewCount":"1"}""", """{"viewCount":true}""")) {
            assertTrue(body, api(body = body).markStoryViewed("story", "owner") is PulseResult.Failure)
        }
        assertTrue(api(body = """{"viewCount":0}""").markStoryViewed("story", "owner") is PulseResult.Success)
    }

    // Section 9 - durable publication identity (item 1), bounded pages
    // (item 2), receipt privacy on the wire (item 3).

    @Test fun `postStory carries the journaled clientId on the wire`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = """{"story":{"id":"s-1","kind":"text","caption":"x","background":"emerald",
            "createdAt":"2026-02-20T09:00:00.000Z","expiresAt":"2026-02-21T09:00:00.000Z","viewCount":0,"viewedByMe":false},
            "replayed":false}""").postStory("u1", caption = "x", clientId = "replay-identity-000000001")
        assertTrue(r is PulseResult.Success)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(lastBody) as kotlinx.serialization.json.JsonObject
        assertEquals("replay-identity-000000001", (body["clientId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(false, (r as PulseResult.Success).value.replayed)
    }

    @Test fun `replayed publication receipts decode as the SAME confirmed story`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val replayBody = """{"story":{"id":"s-1","kind":"text","caption":"x","background":"emerald",
            "createdAt":"2026-02-20T09:00:00.000Z","expiresAt":"2026-02-21T09:00:00.000Z","viewCount":2,"viewedByMe":false},
            "replayed":true}"""
        val r = api(body = replayBody).postStory("u1", caption = "x", clientId = "replay-identity-000000001")
        assertTrue(r is PulseResult.Success)
        val created = (r as PulseResult.Success).value
        assertTrue("a replay is a confirmed publication of the SAME row", created.replayed)
        assertEquals("s-1", created.story?.id)
    }

    @Test fun `legacy story receipts keep their shape when the replayed flag is absent`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val legacy = """{"story":{"id":"s-2","kind":"text","caption":"x","background":"emerald",
            "createdAt":"2026-02-20T09:00:00.000Z","expiresAt":"2026-02-21T09:00:00.000Z","viewCount":0,"viewedByMe":false}}"""
        val r = api(body = legacy).postStory("u1", caption = "x")
        assertTrue(r is PulseResult.Success)
        assertEquals(false, (r as PulseResult.Success).value.replayed)
    }

    @Test fun `feed pages request the keyset window and decode the continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"groups":[{"user":{"id":"u2","name":"Bob"},"stories":[{"id":"b","caption":"x",
            "background":"emerald","createdAt":"2026-02-20T09:00:00.000Z","expiresAt":"2026-02-21T09:00:00.000Z",
            "viewCount":1,"viewedByMe":false}]}],"nextCursor":"1771606800000.b","hasMore":true}"""
        val r = api(body = page).storiesPage("u1", 60, "1771606800000.a")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore)
        assertEquals("1771606800000.b", dto.nextCursor)
        assertTrue("cursor must ride the query", lastUrl.contains("limit=60"))
        assertTrue(lastUrl.contains("cursor=1771606800000.a"))
    }

    @Test fun `an opening feed page omits the cursor parameter`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = """{"groups":[],"nextCursor":null,"hasMore":false}""").storiesPage("u1", 60, null)
        assertTrue(r is PulseResult.Success)
        assertTrue("no cursor on the opening page", lastUrl.contains("limit=60") && !lastUrl.contains("cursor="))
    }

    @Test fun `a continued page without its cursor is a protocol failure`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val broken = """{"groups":[{"user":{"id":"u2","name":"Bob"},"stories":[]}],"hasMore":true}"""
        assertTrue(api(body = broken).storiesPage("u1", 60, null) is PulseResult.Failure)
    }

    @Test fun `viewer pages decode their keyset continuation and receipt privacy`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"viewers":[{"userId":"u2","name":"Bob","username":null,"color":"teal",
            "viewedAt":"2026-02-20T09:05:00.000Z"}],"nextCursor":"1771607100000.u2","hasMore":true}"""
        val r = api(body = page).storyViewersPage("s-9", "u1", 100, "1771607000000.aa")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore)
        assertEquals("1771607100000.u2", dto.nextCursor)
        assertTrue(lastUrl.contains("limit=100"))
        assertTrue(lastUrl.contains("cursor=1771607000000.aa"))
    }

    @Test fun `a continued viewer page without its cursor is a protocol failure`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val broken = """{"viewers":[{"userId":"u2","name":"Bob","username":null,"color":"teal",
            "viewedAt":"2026-02-20T09:05:00.000Z"}],"hasMore":true}"""
        assertTrue(api(body = broken).storyViewersPage("s-9", "u1", 100, null) is PulseResult.Failure)
    }

    @Test fun `view acks decode receiptVisible and legacy acks stay visible`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val hidden = api(body = """{"viewCount":0,"receiptVisible":false}""").markStoryViewed("s-9", "u1")
        assertEquals(false, (hidden as PulseResult.Success).value.receiptVisible)
        val legacy = api(body = """{"viewCount":1}""").markStoryViewed("s-9", "u1")
        assertEquals(true, (legacy as PulseResult.Success).value.receiptVisible)
    }
}

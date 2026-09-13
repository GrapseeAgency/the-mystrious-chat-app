package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 6 — social-graph REST contract tests (JVM, MockEngine). Locks the
 * ROUTE-FIX audit items: block carries the actor in the BODY, unblock is
 * DELETE /block?userId= (POST /unblock does not exist), report carries the
 * reporter, PATCH propagates the 409 username_taken code+suggestion.
 */
class Wave6SocialApiTest {

    private var lastMethod: HttpMethod? = null
    private var lastUrl: String = ""
    private var lastBody: String = ""
    private var status: HttpStatusCode = HttpStatusCode.OK
    private var response: String = "{}"

    private fun api(): PulseApi = PulseApi(
        HttpClient(MockEngine { request ->
            lastMethod = request.method
            lastUrl = request.url.toString()
            lastBody = when (val b = request.body as? OutgoingContent) {
                is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                else -> ""
            }
            respond(response, status, headersOf("Content-Type", "application/json"))
        }),
    )

    @After
    fun reset() {
        PulseEndpoints.applyBase(null)
        status = HttpStatusCode.OK
        response = "{}"
    }

    private fun configured() = PulseEndpoints.applyBase("https://gw.example")

    @Test
    fun `block posts the actor in the body`() = runTest {
        configured()
        response = """{"ok":true,"blocked":true}"""
        val r = api().blockUser("target1", "viewer1")
        assertTrue(r is PulseResult.Success)
        assertTrue((r as PulseResult.Success).value.blocked)
        assertEquals(HttpMethod.Post, lastMethod)
        assertEquals("https://gw.example/api/users/target1/block", lastUrl)
        assertEquals("""{"userId":"viewer1"}""", lastBody)
    }

    @Test
    fun `unblock is DELETE block with the actor as a query param - route fix`() = runTest {
        configured()
        response = """{"ok":true,"blocked":false}"""
        val r = api().unblockUser("target1", "viewer1")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
        assertEquals("https://gw.example/api/users/target1/block?userId=viewer1", lastUrl)
    }

    @Test
    fun `report carries the reporter, reason and details`() = runTest {
        configured()
        response = """{"reported":true}"""
        val r = api().reportUser("bad1", "me1", "spam", "detail text")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertEquals("https://gw.example/api/users/bad1/report", lastUrl)
        assertEquals("""{"userId":"me1","reason":"spam","details":"detail text"}""", lastBody)
    }

    @Test
    fun `report omits blank details`() = runTest {
        configured()
        val r = api().reportUser("bad1", "me1", "other", null)
        assertTrue(r is PulseResult.Success)
        assertEquals("""{"userId":"me1","reason":"other"}""", lastBody)
    }

    @Test
    fun `patch user propagates the 409 username_taken code and suggestion`() = runTest {
        configured()
        status = HttpStatusCode.Conflict
        response = """{"error":"@cara is already taken.","code":"username_taken","suggestion":"cara1"}"""
        val r = api().patchUser(
            "me1",
            kotlinx.serialization.json.buildJsonObject {
                put("username", kotlinx.serialization.json.JsonPrimitive("cara"))
            },
        )
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals("username_taken", f.code)
        assertEquals("cara1", f.suggestion)
        assertEquals("@cara is already taken.", f.message)
        assertEquals(HttpMethod.Patch, lastMethod)
    }

    @Test
    fun `full user parses the envelope with scrubbed lastSeen`() = runTest {
        configured()
        response = """{"user":{"id":"u1","name":"Cara","createdAt":"2024-01-01T00:00:00.000Z","lastSeenAt":null}}"""
        val r = api().fullUser("u1")
        assertTrue(r is PulseResult.Success)
        assertEquals("Cara", (r as PulseResult.Success).value.name)
        assertNull(r.value.lastSeenAt)
        assertEquals("https://gw.example/api/users/u1", lastUrl)
    }

    @Test
    fun `safety state get and unverify ride the userId query`() = runTest {
        configured()
        response = """{"peerId":"p1","safetyNumber":"0 1 2","verified":false,"verifiedAt":null}"""
        val r = api().safetyState("p1", "me1")
        assertTrue(r is PulseResult.Success)
        assertEquals("https://gw.example/api/users/p1/safety?userId=me1", lastUrl)

        response = """{"verified":false}"""
        val un = api().safetyUnverify("p1", "me1")
        assertTrue(un is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
        assertEquals("https://gw.example/api/users/p1/safety?userId=me1", lastUrl)
    }

    @Test
    fun `safety verify posts the viewer`() = runTest {
        configured()
        response = """{"verified":true,"verifiedAt":"2025-05-05T10:00:00.000Z"}"""
        val r = api().safetyVerify("p1", "me1")
        assertTrue(r is PulseResult.Success)
        assertTrue((r as PulseResult.Success).value.verified)
        assertEquals(HttpMethod.Post, lastMethod)
        assertEquals("""{"userId":"me1"}""", lastBody)
    }

    @Test
    fun `blocked accounts list uses the owner as the actor`() = runTest {
        configured()
        response = """{"blocks":[]}"""
        val r = api().blockedAccounts("me1")
        assertTrue(r is PulseResult.Success)
        assertEquals("https://gw.example/api/users/me1/blocks?userId=me1", lastUrl)
    }

    @Test
    fun `channels directory toggles the mine flag`() = runTest {
        configured()
        response = """{"channels":[]}"""
        val all = api().channels("me1", mineOnly = false)
        assertTrue(all is PulseResult.Success)
        assertEquals("https://gw.example/api/channels?userId=me1", lastUrl)

        val mine = api().channels("me1", mineOnly = true)
        assertTrue(mine is PulseResult.Success)
        assertEquals("https://gw.example/api/channels?userId=me1&mine=1", lastUrl)
    }

    @Test
    fun `subscribe posts and unsubscribe deletes with a json body`() = runTest {
        configured()
        response = """{"already":false,"memberCount":4}"""
        val sub = api().subscribeChannel("c1", "me1")
        assertTrue(sub is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertEquals("https://gw.example/api/channels/c1/subscribe", lastUrl)
        assertEquals("""{"userId":"me1"}""", lastBody)

        response = """{"ok":true,"memberCount":3}"""
        val un = api().unsubscribeChannel("c1", "me1")
        assertTrue(un is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
        assertEquals("https://gw.example/api/channels/c1/subscribe", lastUrl)
        assertEquals("""{"userId":"me1"}""", lastBody)
    }

    @Test
    fun `folder membership full replace is a PUT with the ordered array`() = runTest {
        configured()
        response = """{"ok":true}"""
        val r = api().setFolderConversations("f1", listOf("c2", "c1"))
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Put, lastMethod)
        assertEquals("https://gw.example/api/folders/f1/conversations", lastUrl)
        assertEquals("""{"conversationIds":["c2","c1"]}""", lastBody)
    }

    @Test
    fun `invite preview joins with an optional viewer`() = runTest {
        configured()
        response = """{"invite":{"code":"PULSE12","conversationId":"c1","isGroup":true,"name":"G","memberCount":2,"alreadyMember":false}}"""
        val anon = api().invitePreview("PULSE12", null)
        assertTrue(anon is PulseResult.Success)
        assertEquals("https://gw.example/api/invite/PULSE12", lastUrl)

        val mine = api().invitePreview("PULSE12", "me1")
        assertTrue(mine is PulseResult.Success)
        assertEquals("https://gw.example/api/invite/PULSE12?userId=me1", lastUrl)

        response = """{"conversationId":"c1","alreadyMember":false}"""
        val join = api().inviteJoin("PULSE12", "me1")
        assertTrue(join is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertEquals("https://gw.example/api/invite/PULSE12/join", lastUrl)
    }

    @Test
    fun `unconfigured gateway never fires`() = runTest {
        val r = api().blockUser("t", "v")
        assertTrue(r is PulseResult.Failure)
        assertNull(lastMethod)
        assertEquals("", lastUrl)
    }
}

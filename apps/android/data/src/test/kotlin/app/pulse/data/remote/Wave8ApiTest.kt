package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.decodeSettingsEnvelope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 8 — REST contract tests (JVM, MockEngine):
 *  - settings GET/PATCH verbs, paths and bodies;
 *  - POST /api/users/login parse incl. the honest 404;
 *  - create response carries the session token;
 *  - Bearer attach: the Authorization header rides on EVERY request when a
 *    token is present, and stays absent when it isn't;
 *  - 401 → typed Kind.AUTH failure + the onAuthInvalid hook fires.
 */
class Wave8ApiTest {

    private var lastMethod: HttpMethod? = null
    private var lastUrl: String = ""
    private var lastBody: String = ""
    private var lastAuthHeader: String? = null
    private var status: HttpStatusCode = HttpStatusCode.OK
    private var response: String = "{}"

    private var authInvalidCalls = 0
    private var lastInvalidReason: String? = null

    private fun api(token: String? = null): PulseApi = PulseApi(
        HttpClient(MockEngine { request ->
            lastMethod = request.method
            lastUrl = request.url.toString()
            lastAuthHeader = request.headers["Authorization"]
            lastBody = when (val b = request.body as? OutgoingContent) {
                is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                else -> ""
            }
            respond(response, status, headersOf("Content-Type", "application/json"))
        }),
        bearerToken = { token },
        onAuthInvalid = { reason ->
            authInvalidCalls += 1
            lastInvalidReason = reason
        },
    )

    @After
    fun reset() {
        PulseEndpoints.applyBase(null)
        status = HttpStatusCode.OK
        response = "{}"
        authInvalidCalls = 0
        lastInvalidReason = null
    }

    private fun configured() = PulseEndpoints.applyBase("https://gw.example")

    // ── settings GET ─────────────────────────────────────────────

    @Test
    fun `settings GET hits the right path and decodes the preferences envelope`() = runTest {
        configured()
        response = """{"preferences":{"bubbleRadius":"pill","density":"compact","wallpaper":"aurora",
            "notifPreviews":false,"notifSound":true,"notifVibrate":true,
            "lastSeenVisible":false,"readReceipts":true,"typingVisible":false,"reducedMotion":true}}"""
        val r = api(token = "tok1").settings("u-123")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Get, lastMethod)
        assertTrue(lastUrl.startsWith("https://gw.example/api/settings?userId="))
        assertTrue(lastUrl.endsWith("u-123"))
        assertEquals("Bearer tok1", lastAuthHeader)
        val prefs = (r as PulseResult.Success).value.preferences
        assertNotNull(prefs)
        assertEquals("pill", prefs?.bubbleRadius)
        assertEquals("compact", prefs?.density)
        assertEquals("aurora", prefs?.wallpaper)
        assertEquals(false, prefs?.notifPreviews)
        assertEquals(true, prefs?.notifVibrate)
        assertEquals(false, prefs?.lastSeenVisible)
        assertEquals(true, prefs?.reducedMotion)
    }

    @Test
    fun `settings GET decode tolerates a partial blob via the merge defaults`() = runTest {
        configured()
        response = """{"preferences":{"wallpaper":"dusk"}}"""
        val r = api().settings("u1")
        assertTrue(r is PulseResult.Success)
        val prefs = (r as PulseResult.Success).value.preferences
        assertEquals("dusk", prefs?.wallpaper)
        // server merged defaults; decode-side tolerance ALSO guarantees them
        assertEquals("lg", prefs?.bubbleRadius)
        assertTrue(prefs?.readReceipts == true)
    }

    // ── settings PATCH ───────────────────────────────────────────

    @Test
    fun `settings PATCH sends userId plus only the carried fields`() = runTest {
        configured()
        response = """{"preferences":{"bubbleRadius":"md","density":"cozy","wallpaper":"none",
            "notifPreviews":true,"notifSound":true,"notifVibrate":false,
            "lastSeenVisible":true,"readReceipts":true,"typingVisible":true,"reducedMotion":false}}"""
        val r = api(token = "tok").updateSettings("u-9", WirePulsePrefs(wallpaper = "mono", notifSound = false))
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Patch, lastMethod)
        assertTrue(lastUrl.endsWith("/api/settings"))
        assertEquals("Bearer tok", lastAuthHeader)
        assertTrue(lastBody.contains("\"userId\":\"u-9\""))
        assertTrue(lastBody.contains("\"wallpaper\":\"mono\""))
        assertTrue(lastBody.contains("\"notifSound\":false"))
        // untouched fields stay out of the body
        assertFalse(lastBody.contains("bubbleRadius"))
        assertFalse(lastBody.contains("reducedMotion"))
    }

    // ── login + create token envelope ────────────────────────────

    @Test
    fun `login posts the name and decodes user plus token`() = runTest {
        configured()
        response = """{"user":{"id":"u1","name":"Ada","color":"emerald"},
            "token":"a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8"}"""
        val r = api(token = "stale").login("Ada")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertTrue(lastUrl.endsWith("/api/users/login"))
        assertTrue(lastBody.contains("\"name\":\"Ada\""))
        val v = (r as PulseResult.Success).value
        assertEquals("u1", v.user?.id)
        assertEquals("Ada", v.user?.name)
        assertEquals(64, v.token?.length)
    }

    @Test
    fun `login 404 surfaces the verbatim honest copy`() = runTest {
        configured()
        status = HttpStatusCode.NotFound
        response = """{"error":"No identity with that name on this Pulse."}"""
        val r = api().login("Nobody")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(PulseResult.Failure.Kind.NOT_FOUND, f.kind)
        assertEquals(404, f.status)
        assertEquals("No identity with that name on this Pulse.", f.message)
    }

    @Test
    fun `login 400 name-required maps to the server copy`() = runTest {
        configured()
        status = HttpStatusCode.BadRequest
        response = """{"error":"Name is required."}"""
        val r = api().login("")
        assertTrue(r is PulseResult.Failure)
        assertEquals("Name is required.", (r as PulseResult.Failure).message)
    }

    @Test
    fun `create user decode carries the Wave 8 token`() = runTest {
        configured()
        response = """{"user":{"id":"u2","name":"Grace"},
            "token":"f1e2d3c4b5a69788f1e2d3c4b5a69788f1e2d3c4b5a69788f1e2d3c4b5a69788"}"""
        val r = api().createUser("Grace", color = "emerald", username = "grace")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/users"))
        val v = (r as PulseResult.Success).value
        assertEquals("u2", v.user?.id)
        assertNotNull(v.token)
        assertTrue(lastBody.contains("\"username\":\"grace\""))
    }

    // ── Bearer attach on every request family ────────────────────

    @Test
    fun `bearer header attaches on GET POST PATCH and stays absent without a token`() = runTest {
        configured()
        response = "{}"

        val withToken = api(token = "secret-1")
        withToken.settings("u1")
        assertEquals("Bearer secret-1", lastAuthHeader)
        withToken.login("Ada")
        assertEquals("Bearer secret-1", lastAuthHeader)
        withToken.updateSettings("u1", WirePulsePrefs(density = "compact"))
        assertEquals("Bearer secret-1", lastAuthHeader)
        withToken.users()
        assertEquals("Bearer secret-1", lastAuthHeader)

        val noToken = api(token = null)
        noToken.settings("u1")
        assertNull(lastAuthHeader)
        noToken.login("Ada")
        assertNull(lastAuthHeader)
    }

    // ── 401 handling ─────────────────────────────────────────────

    @Test
    fun `401 maps to Kind AUTH fires the invalid hook and keeps the verbatim copy`() = runTest {
        configured()
        status = HttpStatusCode.Unauthorized
        response = """{"error":"Session token is invalid or has been rotated. Log in again."}"""
        val r = api(token = "rotated").settings("u1")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(PulseResult.Failure.Kind.AUTH, f.kind)
        assertEquals(401, f.status)
        assertEquals("Session token is invalid or has been rotated. Log in again.", f.message)
        assertEquals(1, authInvalidCalls)
        assertEquals("Session token is invalid or has been rotated. Log in again.", lastInvalidReason)
    }

    @Test
    fun `non-401 failures never fire the invalid hook`() = runTest {
        configured()
        status = HttpStatusCode.Forbidden
        response = """{"error":"nope"}"""
        val r = api(token = "tok").settings("u1")
        assertTrue(r is PulseResult.Failure)
        assertEquals(PulseResult.Failure.Kind.FORBIDDEN, (r as PulseResult.Failure).kind)
        assertEquals(0, authInvalidCalls)
    }

    // ── logic glue lives in protocol — sanity smoke here too ─────

    @Test
    fun `settings envelope decode helper tolerates a root-level preferences object`() = runTest {
        val prefs = """{"preferences":{"density":"compact"}}""".decodeSettingsEnvelope()
        assertEquals("compact", prefs.preferences?.density)
        val junk = PulseWave8Logic.mergePrefs("not json")
        assertEquals(PulseWave8Logic.DEFAULTS, junk)
    }
}

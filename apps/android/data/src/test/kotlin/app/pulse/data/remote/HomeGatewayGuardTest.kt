package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Root-cause regression for the Home "CLEARTEXT communication to localhost is
 * not permitted by network security policy" field report.
 *
 * Mechanism: with NO configured gateway (honest offline-first), the old code
 * still FIRED REST requests. `PulseEndpoints.http(path)` returned a bare
 * relative path, and Ktor's URLBuilder resolved it against its implicit
 * `http://localhost` default — so the OkHttp engine attempted a cleartext
 * request at the phone itself and Android's network security policy (target
 * SDK 35, correctly) blocked it. Home surfaced the raw policy string under
 * "Could not reach the gateway".
 *
 * Contract under test:
 *  1. Unconfigured → ZERO engine requests, honest NETWORK failure with
 *     actionable copy (never a policy dump, never an implicit localhost hit).
 *  2. Configured → exactly one request, at the configured origin.
 *  3. The direct-engine routes (media download, delete, unfurl, topic delete)
 *     honor the same guard.
 *  4. Manifest probes: offline bootstraps ONLY from the HTTPS CDN (the old
 *     relative "/update-manifest.json" probe was unresolvable dead code);
 *     configured raw-CDN origins probe both layouts over HTTPS.
 */
class HomeGatewayGuardTest {

    private var requests = 0
    private var lastUrl = ""

    private fun api(): PulseApi = PulseApi(
        HttpClient(MockEngine { request ->
            requests++
            lastUrl = request.url.toString()
            respondError(HttpStatusCode.InternalServerError, "boom")
        }),
    )

    @Before
    fun reset() {
        PulseEndpoints.applyBase(null)
        requests = 0
        lastUrl = ""
    }

    @Test
    fun `unconfigured gateway fires no request and fails honestly`() = runTest {
        val r = api().conversations("u1")
        assertTrue(r is PulseResult.Failure)
        val f = r as PulseResult.Failure
        assertEquals(PulseResult.Failure.Kind.NETWORK, f.kind)
        assertTrue("copy must be actionable, was: ${f.message}", (f.message ?: "").contains("No gateway configured"))
        assertEquals("the implicit http://localhost attempt must never happen", 0, requests)
    }

    @Test
    fun `configured gateway routes the request to the configured origin`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api().conversations("u1")
        // Mock answers 500 → mapped to SERVER, proving the request executed.
        assertTrue(r is PulseResult.Failure)
        assertEquals(PulseResult.Failure.Kind.SERVER, (r as PulseResult.Failure).kind)
        assertEquals("https://prod.example/api/conversations?userId=u1", lastUrl)
        assertEquals(1, requests)
    }

    @Test
    fun `direct engine sites honor the same guard`() = runTest {
        val api = api()
        assertTrue(api.downloadMedia("x/y.png") is PulseResult.Failure)
        assertTrue(api.deleteMessage("m1", "u1") is PulseResult.Failure)
        assertTrue(api.unfurl("m1", "u1") is PulseResult.Failure)
        assertTrue(api.deleteTopic("t1", "u1") is PulseResult.Failure)
        assertEquals("no direct route may bypass the guard", 0, requests)
    }

    @Test
    fun `manifest probe order - offline bootstraps from the CDN only`() {
        val urls = manifestProbeUrls("", "https://cdn.example/download/update-manifest.json")
        assertEquals(listOf("https://cdn.example/download/update-manifest.json"), urls)
        assertTrue("every probe must be absolute HTTPS (no relative URL dead code)", urls.all { it.startsWith("https://") })
    }

    @Test
    fun `manifest probe order - configured raw CDN probes both layouts`() {
        val urls = manifestProbeUrls(
            "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/",
            "https://cdn.example/download/update-manifest.json",
        )
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/update-manifest.json",
                "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json",
            ),
            urls,
        )
    }

    @Test
    fun `manifest probe order - configured non-CDN origin probes root layout only`() {
        val urls = manifestProbeUrls("https://gateway.example:8443/", "https://cdn.example/download/update-manifest.json")
        assertEquals(listOf("https://gateway.example:8443/update-manifest.json"), urls)
    }

    /**
     * The REAL engine end-to-end: production uses ktor-client-okhttp. Against a
     * hermetic loopback TCP server — unconfigured must produce ZERO requests
     * (this is the exact regression that produced the implicit
     * http://localhost attempt); configured must land exactly one request on
     * the configured origin and parse the real Home payload.
     */
    @Test
    fun `real OkHttp engine - zero requests offline, one request to the configured origin`() = runTest {
        var hits = 0
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = reader.readLine() ?: return@use
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break // end of headers
                    }
                    if (requestLine.contains("/api/conversations")) hits++
                    val body = "{\"conversations\":[]}".toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + body)
                }
            }
        }
        try {
            PulseEndpoints.applyBase(null)
            val api = PulseApi(HttpClient(OkHttp))
            val offline = api.conversations("u1")
            assertTrue("offline-first must fail honestly", offline is PulseResult.Failure)
            assertEquals("no request may leave the process while unconfigured", 0, hits)

            PulseEndpoints.applyBase("http://127.0.0.1:${server.localPort}")
            val online = api.conversations("u1")
            assertTrue("configured origin must answer", online is PulseResult.Success)
            assertEquals(1, hits)
        } finally {
            server.close()
            serverThread.join(2_000)
            PulseEndpoints.applyBase(null)
        }
    }
}

package app.pulse.protocol

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * LIVE parity tests — these hit the real gateway over HTTP and assert the
 * wire DTOs parse exactly what the Next.js API emits. Skipped (not failed)
 * when the gateway is unreachable, so CI environments without the sandbox
 * still pass.
 *
 * The conversationId probe is read from the LIVE payload itself (first group
 * conversation of the test user) — zero hardcoded fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LiveGatewayParityTest {

    private val gateway = System.getenv("PULSE_GATEWAY") ?: "http://127.0.0.1:81"
    private val aliceId = System.getenv("PULSE_USER_ID") ?: "cmtawq3h4001ktcwn674m1frp"

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1) // gateway 502s h2c upgrade probes — pin HTTP/1.1
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private var probedConversationId: String = ""

    @BeforeAll
    fun gatewayMustBeUp() {
        val up = try {
            val req = HttpRequest.newBuilder(URI.create("$gateway/api/users"))
                .timeout(Duration.ofSeconds(3)).GET().build()
            http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
        assumeTrue(up, "gateway $gateway unreachable — skipping live parity tests")
    }

    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create("$gateway$path"))
            .timeout(Duration.ofSeconds(8)).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertTrue(res.statusCode() in 200..299, "GET $path → ${res.statusCode()}")
        return res.body()
    }

    @Test
    @Order(1)
    fun `conversations list parses into wire DTOs`() {
        val body = get("/api/conversations?userId=$aliceId")
        val page = PulseJson.decodeFromString(ConversationsPageDto.serializer(), body)
        assertTrue(page.conversations.isNotEmpty(), "live payload returned zero conversations")
        val first = page.conversations.first()
        assertFalse(first.id.isBlank())
        probedConversationId = first.id

        val group = page.conversations.firstOrNull { it.isGroup }
        if (group != null) {
            assertTrue(group.members.isNotEmpty(), "group conversations must carry members")
            assertTrue(group.members.first().id.isNotBlank())
        }
    }

    @Test
    @Order(2)
    fun `messages page parses and maps wire kinds`() {
        assumeTrue(probedConversationId.isNotBlank(), "order(1) skipped or produced no probe id")
        val body = get("/api/conversations/$probedConversationId/messages?limit=5")
        val page = PulseJson.decodeFromString(MessagesPageDto.serializer(), body)
        assertTrue(page.messages.isNotEmpty(), "probe conversation has no messages to verify")

        val wireKinds = setOf("text", "image", "voice", "video", "file", "poll", "system", "sticker", "location", "red_packet")
        page.messages.forEach { m ->
            assertTrue(m.id.isNotBlank())
            assertTrue(m.kind in wireKinds, "unknown wire kind ${m.kind}")
            assertTrue(m.createdAt.isNotBlank())
        }
    }
}

package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 12 acceptance (Android data layer) - the integration wire contract:
 *  - item 1: the automations/webhooks GETs emit limit+cursor exactly when
 *    paging is requested (the legacy first-page call omits both).
 *  - item 2: automation create/delete carry clientId in the body and the
 *    receipt's replayed flag is demanded (and decoded) when identity was sent.
 *  - item 7: rotate PATCHes /api/webhooks/{token} and parses the FRESH token
 *    plus rotatedAt/revision/replayed.
 *  - items 6/8: webhook create/delete carry clientId; the page envelope
 *    (revision + page:{nextCursor,hasMore}) decodes.
 */
class IntegrationMutationAcceptanceTest {
    private val answeredIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val methods = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun apiOf(vararg bodies: Pair<Int, String>): HttpClient = HttpClient(MockEngine { request ->
        val (status, body) = bodies[answeredIndex.getAndIncrement() % bodies.size]
        if (request.body is TextContent) recorded += (request.body as TextContent).text
        paths += request.url.encodedPath + "?" + request.url.encodedQuery
        methods += request.method.value
        respond(body, HttpStatusCode(status, "test"), headersOf("Content-Type", "application/json"))
    })

    private fun lastBody(): String = recorded.last()
    private fun lastPath(): String = paths.last()
    private fun lastMethod(): String = methods.last()

    @Test
    fun automationsGetEmitsLimitAndCursorAndTheLegacyCallOmitsBoth() = runTest {
        val http = apiOf(
            200 to """{"automations":[{"id":"rule","conversationId":"room","trigger":"Hello","reply":"Welcome","enabled":true,"hits":0}],
                "revision":4,"page":{"nextCursor":"1717000000000.abc","hasMore":true}}""",
            200 to """{"automations":[],"revision":4}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://integrations.test" })
            val paged = api.automations("room", "me", cursor = "1717000000000.abc", limit = 25)
            val page = (paged as PulseResult.Success).value
            assertEquals(4, page.revision)
            assertEquals("1717000000000.abc", page.page?.nextCursor)
            assertEquals(true, page.page?.hasMore)
            assertTrue(lastPath().contains("limit=25"))
            assertTrue(lastPath().contains("cursor=1717000000000.abc"))
            // Legacy first-page call: no paging params at all.
            assertTrue(api.automations("room", "me") is PulseResult.Success)
            assertFalse(lastPath().contains("limit="))
            assertFalse(lastPath().contains("cursor="))
        } finally { http.close() }
    }

    @Test
    fun automationCreateAndDeleteCarryTheClientIdAndDemandTheReplayFlag() = runTest {
        val http = apiOf(
            201 to """{"automation":{"id":"rule","conversationId":"room","trigger":"Hello","reply":"Welcome","enabled":true,"hits":0},
                "revision":5,"replayed":false}""",
            201 to """{"automation":{"id":"rule2","conversationId":"room","trigger":"Hours","reply":"Nine to five","enabled":true,"hits":0}}""",
            200 to """{"ok":true,"deleted":true,"automationId":"rule","userId":"me","revision":6,"replayed":true}""",
            200 to """{"ok":true,"deleted":true,"automationId":"rule","userId":"me","revision":6}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://integrations.test" })
            val created = api.createAutomation("room", "me", "Hello", "Welcome", "client000000000000012")
            assertTrue(created is PulseResult.Success)
            assertEquals(false, (created as PulseResult.Success).value.replayed)
            assertEquals(5, created.value.revision)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000012\""))
            // A receipt WITHOUT the replay flag is refused when identity was sent.
            assertTrue(api.createAutomation("room", "me", "Hours", "Nine to five", "client000000000000013") is PulseResult.Failure)
            // Lost-ACK delete replay: the SAME verdict, replayed=true decoded.
            val deleted = api.deleteAutomation("rule", "me", "client000000000000014")
            assertTrue(deleted is PulseResult.Success)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000014\""))
            // A direct (non-journaled) delete carries no clientId and no flag demand.
            assertTrue(api.deleteAutomation("rule", "me") is PulseResult.Success)
        } finally { http.close() }
    }

    @Test
    fun rotatePatchHitsTheCapabilityPathAndParsesTheFreshTokenReceipt() = runTest {
        val http = apiOf(
            200 to """{"id":"hook","name":"Build bot","token":"fresh","avatarColor":"emerald",
                "url":"/api/webhooks/fresh","createdAt":"2026-03-01T09:00:00.000Z","createdBy":"me",
                "rotatedAt":"2026-03-01T10:00:00.000Z","revision":7,"replayed":false}""",
            200 to """{"id":"hook","name":"Build bot","token":"capability","avatarColor":"emerald",
                "url":"/api/webhooks/capability","createdAt":"2026-03-01T09:00:00.000Z","createdBy":"me"}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://integrations.test" })
            val rotated = api.rotateWebhook("capability", "me", "client000000000000015")
            assertTrue(rotated is PulseResult.Success)
            assertEquals("PATCH", lastMethod())
            assertTrue(lastPath().startsWith("/api/webhooks/capability"))
            assertTrue(lastBody().contains("\"requesterId\":\"me\""))
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000015\""))
            val receipt = (rotated as PulseResult.Success).value
            assertEquals("fresh", receipt.token)
            assertEquals("2026-03-01T10:00:00.000Z", receipt.rotatedAt)
            assertEquals(7, receipt.revision)
            assertEquals(false, receipt.replayed)
            // A receipt echoing the DEAD capability is not a rotation.
            assertTrue(api.rotateWebhook("capability", "me") is PulseResult.Failure)
        } finally { http.close() }
    }

    @Test
    fun webhookCreateAndDeleteCarryTheClientIdAndDecodeTheVerdicts() = runTest {
        val http = apiOf(
            201 to """{"id":"hook","name":"Build bot","token":"capability","avatarColor":"emerald",
                "url":"/api/webhooks/capability","createdAt":"2026-03-01T09:00:00.000Z","createdBy":"me","replayed":false}""",
            200 to """{"ok":true,"deleted":true,"token":"capability","userId":"me","revision":8,"replayed":false}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://integrations.test" })
            val created = api.createWebhook("room", "Build bot", "me", "client000000000000016")
            assertTrue(created is PulseResult.Success)
            assertEquals(false, (created as PulseResult.Success).value.replayed)
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000016\""))
            val deleted = api.deleteWebhook("capability", "me", "client000000000000017")
            assertTrue(deleted is PulseResult.Success)
            assertTrue(lastBody().contains("\"requesterId\":\"me\""))
            assertTrue(lastBody().contains("\"clientId\":\"client000000000000017\""))
        } finally { http.close() }
    }

    @Test
    fun webhookPagesEmitPagingParamsAndDecodeTheEnvelope() = runTest {
        val http = apiOf(
            200 to """{"webhooks":[{"id":"hook","name":"Build bot","token":"capability","avatarColor":"emerald",
                "url":"/api/webhooks/capability","createdAt":"2026-03-01T09:00:00.000Z","createdBy":"me",
                "rotatedAt":"2026-03-01T10:00:00.000Z"}],
                "revision":9,"page":{"nextCursor":"1717000000001.def","hasMore":false}}""",
            200 to """{"webhooks":[],"revision":9}""",
        )
        try {
            val api = PulseApi(http, gateway = { "https://integrations.test" })
            val paged = api.webhooks("room", "me", cursor = "1717000000000.abc", limit = 200)
            val page = (paged as PulseResult.Success).value
            assertEquals(9, page.revision)
            assertEquals(1, page.webhooks.size)
            assertEquals("2026-03-01T10:00:00.000Z", page.webhooks.single().rotatedAt)
            assertEquals("1717000000001.def", page.page?.nextCursor)
            assertEquals(false, page.page?.hasMore)
            assertTrue(lastPath().contains("limit=200"))
            assertTrue(lastPath().contains("cursor=1717000000000.abc"))
            assertTrue(api.webhooks("room", "me") is PulseResult.Success)
            assertFalse(lastPath().contains("limit="))
            assertFalse(lastPath().contains("cursor="))
        } finally { http.close() }
    }
}

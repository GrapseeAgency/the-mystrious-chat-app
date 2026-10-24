package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PinStateTest {
    @Test fun pinRetriesAlwaysAddAndRequireTheVersionedReceiptAndPinnedRow() = runTest {
        for (mode in listOf("valid", "missing", "wrong-state", "wrong-id", "wrong-row", "deleted")) {
            var attempts = 0
            val http = HttpClient(MockEngine { request ->
                attempts++
                assertEquals("/api/messages/m/pin-state", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"pinned\":true"))
                val receipt = if (mode == "missing") "" else """"pin":{"messageId":"${if (mode == "wrong-id") "other" else "m"}","pinned":${mode != "wrong-state"}},"""
                val stamp = if (mode == "wrong-row") "null" else "\"2026-10-22T00:01:00Z\""
                val deleted = if (mode == "deleted") "\"2026-10-22T00:02:00Z\"" else "null"
                respond("""{$receipt"message":{"id":"m","conversationId":"room","senderId":"peer","content":"Hi","createdAt":"2026-10-22T00:00:00Z","pinnedAt":$stamp,"deletedAt":$deleted}}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway = { "https://pin.test" })
                repeat(2) { assertEquals(mode == "valid", api.pinMessage("m", "me") is PulseResult.Success) }
                assertEquals(2, attempts)
            } finally { http.close() }
        }
    }

    @Test fun oldGatewayDoesNotFallBackToTheToggleRoute() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine { request ->
            attempts++
            assertEquals("/api/messages/m/pin-state", request.url.encodedPath)
            respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://pin.test" }).pinMessage("m", "me") is PulseResult.Failure)
            assertEquals(1, attempts)
        } finally { http.close() }
    }
}

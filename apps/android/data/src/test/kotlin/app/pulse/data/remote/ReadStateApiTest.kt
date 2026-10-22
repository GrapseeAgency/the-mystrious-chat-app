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

class ReadStateApiTest {
    @Test fun bothReadIntentsRequireTheExactReceiptInsteadOfAnyHttpSuccess() = runTest {
        for (anchor in listOf("anchor", null)) for (mode in listOf("valid", "legacy", "false", "version", "room", "owner", "anchor", "intent", "date", "behind")) {
            val intent = if (anchor == null) "all" else "visible"
            val http = HttpClient(MockEngine { request ->
                assertEquals("/api/conversations/room/read-state", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"mode\":\"$intent\""))
                val stamp = when (mode) { "date" -> "invalid"; "behind" -> "2026-10-22T00:00:00Z"; else -> "2026-10-22T00:00:10Z" }
                val message = if (mode == "anchor") "\"other\"" else anchor?.let { "\"$it\"" } ?: "null"
                val body = if (mode == "legacy") """{"ok":true}""" else """{"ok":${mode != "false"},"readReceiptVersion":${if (mode == "version") 0 else 1},"conversationId":"${if (mode == "room") "other" else "room"}","userId":"${if (mode == "owner") "other" else "me"}","messageId":$message,"mode":"${if (mode == "intent") "wrong" else intent}","lastReadAt":"$stamp"}"""
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://read.test" }).markRead("room", "me", anchor, "2026-10-22T00:00:05Z")
                assertEquals(mode, mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
    @Test fun unsupportedGatewayDoesNotFallbackToMarkAll() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("/api/conversations/room/read-state", request.url.encodedPath)
            respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://read.test" }).markRead("room", "me", "anchor") is PulseResult.Failure)
            assertEquals(1, calls)
        } finally { http.close() }
    }
}

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

class SavedStateApiTest {
    @Test fun repeatedSaveAndRemoveRequireExactStateIdentityAndServerTimestamp() = runTest {
        for (saved in listOf(true, false)) for (mode in listOf("valid", "legacy", "version", "owner", "message", "state", "stamp")) {
            var calls = 0
            val http = HttpClient(MockEngine { request ->
                calls++
                assertEquals("/api/messages/m/save-state", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"saved\":$saved"))
                val stamp = if (mode == "stamp") "\"invalid\"" else if (saved) "\"2026-10-22T00:00:00Z\"" else "null"
                val body = if (mode == "legacy") """{"saved":$saved}""" else """{"savedReceiptVersion":${if (mode == "version") 0 else 1},"messageId":"${if (mode == "message") "other" else "m"}","userId":"${if (mode == "owner") "other" else "me"}","saved":${if (mode == "state") !saved else saved},"savedAt":$stamp}"""
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway = { "https://saved.test" })
                repeat(2) { assertEquals(mode, mode == "valid", api.setMessageSaved("m", "me", saved) is PulseResult.Success) }
                assertEquals(2, calls)
            } finally { http.close() }
        }
    }
    @Test fun unsupportedRouteNeverFallsBackToLegacyToggle() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("/api/messages/m/save-state", request.url.encodedPath)
            respond("""{"error":"missing"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://saved.test" }).setMessageSaved("m", "me", true) is PulseResult.Failure)
            assertEquals(1, calls)
        } finally { http.close() }
    }
}

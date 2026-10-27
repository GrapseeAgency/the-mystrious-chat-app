package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PrivateFeedEnvelopeTest {
    @Test fun unavailableAndMissingFeedsCannotReportEmptySuccess() = runTest {
        for (mode in listOf("valid", "missing", "error")) {
            var requests = 0
            val http = HttpClient(MockEngine {
                requests++
                val body = if (mode == "missing") "{}" else if (it.url.encodedPath.endsWith("/search")) "{\"messages\":[],\"total\":0}" else "{\"items\":[]}"
                respond(body, if (mode == "error") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway = { "https://feeds.test" })
                assertEquals(mode, mode == "valid", api.search("owner", "term") is PulseResult.Success)
                assertEquals(mode, mode == "valid", api.mentions("owner") is PulseResult.Success)
                val before = requests
                assertTrue(api.search("owner", "🌿".repeat(33)) is PulseResult.Failure)
                assertEquals(before, requests)
            } finally { http.close() }
        }
    }
}

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

class WalletAcknowledgementTest {
    @Test fun `identified mutations use only the receipt endpoint and require matching echo`() = runTest {
        val id = "operation-00000001"
        var echo = "wrong-operation"
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/hub/wallet/operations/swap", request.url.encodedPath)
            val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains(id))
            respond("""{"clientId":"$echo","wallet":{"userId":"me","coins":0,"gems":1}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://wallet.test" })
            assertTrue(api.swap("me", "pc2gem", 100, id) is PulseResult.Failure)
            echo = id
            assertTrue(api.swap("me", "pc2gem", 100, id) is PulseResult.Success)
        } finally { http.close() }
    }
    @Test fun `HTTP 200 with missing or wrong owner wallet cannot acknowledge transfer`() = runTest {
        for (body in listOf("""{"wallet":{},"to":{"id":"other"}}""",
            """{"wallet":{"userId":"wrong","coins":10,"gems":0},"to":{"id":"other"}}""",
            """{"wallet":{"userId":"me","coins":-1,"gems":0},"to":{"id":"other"}}""")) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://wallet.test" }).transferCoins("me", "other", 1, null)
                assertTrue(result is PulseResult.Failure)
            } finally { http.close() }
        }
    }
    @Test fun `swap requires a complete balance acknowledgement and preserves HTTP rejection`() = runTest {
        for ((body, status) in listOf("""{"wallet":{},"note":"Swap complete"}""" to HttpStatusCode.OK,
            """{"error":"Insufficient balance"}""" to HttpStatusCode.PaymentRequired)) {
            val http = HttpClient(MockEngine { respond(body, status, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://wallet.test" }).swap("me", "pc2gem", 100)
                assertTrue(result is PulseResult.Failure)
                if (status.value == 402) assertEquals(402, (result as PulseResult.Failure).status)
            } finally { http.close() }
        }
    }
}

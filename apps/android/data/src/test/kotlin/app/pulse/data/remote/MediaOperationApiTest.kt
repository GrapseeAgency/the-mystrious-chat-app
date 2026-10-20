package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MediaOperationApiTest {
    @Test fun retryKeepsVoiceOperationAndUploadedPayloadIdentical() = runTest {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"error":"synthetic lost acknowledgement"}""", HttpStatusCode.ServiceUnavailable,
                headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://fixture.invalid" })
            repeat(2) {
                assertTrue(api.sendMessage("room", "viewer", "", clientId = "voice-operation",
                    audioPath = "uploads/voice.m4a", durationMs = 1700L) is PulseResult.Failure)
            }
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
            val payload = Json.parseToJsonElement(bodies[0]).jsonObject
            assertEquals("voice-operation", payload["clientId"]?.jsonPrimitive?.content)
            assertEquals("uploads/voice.m4a", payload["audioPath"]?.jsonPrimitive?.content)
            assertEquals("1700", payload["durationMs"]?.jsonPrimitive?.content)
        } finally { http.close() }
    }
    @Test fun malformedSuccessfulSendIsUnknownDeliveryNotPermanentRejection() = runTest {
        val http = HttpClient(MockEngine { respond("{", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
        try {
            val result = PulseApi(http, gateway = { "https://fixture.invalid" })
                .sendMessage("room", "viewer", "keep identity", clientId = "stable-operation-001")
            assertTrue(result is PulseResult.Failure)
            assertEquals(PulseResult.Failure.Kind.SERVER, (result as PulseResult.Failure).kind)
        } finally { http.close() }
    }

    @Test fun rateLimitHeaderReachesTheOutboxPolicy() = runTest {
        val http = HttpClient(MockEngine {
            respond("{\"error\":\"wait\"}", HttpStatusCode.TooManyRequests,
                headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("120")))
        })
        try {
            val result = PulseApi(http, gateway = { "https://fixture.invalid" })
                .sendMessage("room", "viewer", "held", clientId = "stable-operation-002")
            assertEquals(120, (result as PulseResult.Failure).retryAfter)
        } finally { http.close() }
    }

}

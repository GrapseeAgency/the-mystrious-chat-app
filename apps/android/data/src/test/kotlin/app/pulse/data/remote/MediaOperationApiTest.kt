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
}

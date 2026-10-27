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

class ReactionStateTest {
    private fun message(present: Boolean, id: String = "m") = """{"id":"$id","conversationId":"room","senderId":"peer","content":"Hi","createdAt":"2026-10-22T00:00:00Z","reactions":${if (present) """[{"emoji":"heart","userIds":["me"],"count":1}]""" else "[]"}}"""

    @Test fun explicitDesiredStateIsPreservedOnRetryAndRequiresMatchingAcknowledgement() = runTest {
        for (present in listOf(true, false)) for (mode in listOf("valid", "missing", "wrong-state", "wrong-id", "wrong-row")) {
            var attempts = 0
            val http = HttpClient(MockEngine { request ->
                attempts++
                assertEquals("/api/messages/m/react-state", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"present\":$present"))
                val receipt = if (mode == "missing") "" else """"reaction":{"messageId":"${if (mode == "wrong-id") "other" else "m"}","emoji":"heart","present":${if (mode == "wrong-state") !present else present}},"""
                respond("{$receipt\"message\":${message(if (mode == "wrong-row") !present else present)}}",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway = { "https://reaction.test" })
                repeat(2) { assertEquals(mode == "valid", api.react("m", "me", "heart", present) is PulseResult.Success) }
                assertEquals(2, attempts)
            } finally { http.close() }
        }
    }

    @Test fun unsupportedStateRouteNeverFallsBackToToggle() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine { request ->
            attempts++
            assertEquals("/api/messages/m/react-state", request.url.encodedPath)
            respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://reaction.test" }).react("m", "me", "heart", true) is PulseResult.Failure)
            assertEquals(1, attempts)
        } finally { http.close() }
    }
}

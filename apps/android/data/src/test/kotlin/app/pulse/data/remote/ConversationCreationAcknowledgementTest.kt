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

class ConversationCreationAcknowledgementTest {
    private val id = "creation-operation-0001"
    private val group = """{"id":"room","isGroup":true,"members":[{"id":"me","name":"Me"},{"id":"a","name":"A"},{"id":"b","name":"B"}]}"""
    private val channel = """{"id":"room","conversationId":"room","isSubscribed":true,"memberCount":1}"""
    private val broadcast = """{"id":"room","isGroup":true,"broadcastMode":true,"members":[{"id":"me","name":"Me"}]}"""

    @Test fun groupRetriesRequireVersionedPathAndMatchingReceipt() = runTest {
        for (receipt in listOf("", "\"clientId\":\"wrong\",\"replayed\":false,", "\"clientId\":\"$id\",\"replayed\":false,")) {
            val http = HttpClient(MockEngine { request ->
                assertEquals("/api/conversations/create-v2", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"clientId\":\"$id\""))
                respond("{$receipt\"conversation\":$group}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://creation.test" }).createConversation("me", listOf("a", "b"), true, "Autumn", id)
                assertEquals(receipt.contains(id), result is PulseResult.Success)
            } finally { http.close() }
        }
    }

    @Test fun channelNeedsBothItsReceiptAndAnOwnedBroadcastRoom() = runTest {
        for (room in listOf("null", group, broadcast)) {
            val http = HttpClient(MockEngine { request ->
                assertEquals("/api/channels/create-v2", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains(id))
                respond("""{"clientId":"$id","replayed":true,"channel":$channel,"conversation":$room}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://creation.test" }).createChannel("me", "Autumn", null, null, id)
                assertEquals(room == broadcast, result is PulseResult.Success)
            } finally { http.close() }
        }
    }

    @Test fun missingVersionedRouteNeverFallsBackToLegacyCreation() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine { request ->
            attempts++
            assertTrue(request.url.encodedPath.endsWith("/create-v2"))
            respond("""{"error":"Upgrade required"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://creation.test" })
            assertTrue(api.createConversation("me", listOf("a", "b"), true, "Autumn", id) is PulseResult.Failure)
            assertTrue(api.createChannel("me", "Autumn", null, null, id) is PulseResult.Failure)
            assertEquals(2, attempts)
        } finally { http.close() }
    }
}

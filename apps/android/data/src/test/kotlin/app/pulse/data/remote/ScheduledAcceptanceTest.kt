package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScheduledAcceptanceTest {
    private val whenIso = "2030-01-01T09:00:00.000Z"
    private fun row(id: String = "queued", room: String = "room", content: String = "Hello") = buildJsonObject {
        put("id", id); put("conversationId", room); put("content", content); put("scheduledAt", whenIso)
    }
    @Test fun readsAndActionsRequireOwnedExactReceipts() = runTest {
        for (action in listOf("list", "create", "cancel")) for (mode in listOf("valid", "empty", "version", "owner", "room", "missing", "wrong")) {
            val body = if (mode == "empty") "{}" else buildJsonObject {
                put("clientId", "scheduled-operation-0001"); put("replayed", false)
                put("scheduleVersion", if (mode == "version") 0 else 1)
                put("userId", if (mode == "owner") "other" else "me")
                put("conversationId", if (mode == "room") "" else "room")
                if (mode != "missing") {
                    val item = row(room = if (mode == "wrong") "other" else "room", content = if (mode == "wrong") "Changed" else "Hello")
                    put("item", item); put("items", JsonArray(listOf(item)))
                    put("hasMore", false); put("nextCursor", JsonNull)
                    put("ok", true); put("cancelled", true); put("scheduledId", if (mode == "wrong") "other" else "queued")
                }
            }.toString()
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://scheduled.test" })
                val result = when (action) {
                    "list" -> api.scheduledMessages("room", "me")
                    "create" -> api.scheduleMessage("room", "me", "Hello", whenIso, "scheduled-operation-0001")
                    else -> api.cancelScheduled("queued", "me")
                }
                assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
    @Test fun paginationIsCompleteOrErrorNeverAPartialSuccess() = runTest {
        for (mode in listOf("valid", "failure", "repeated", "duplicate", "owner")) {
            var count = 0
            val http = HttpClient(MockEngine { req ->
                count++
                assertEquals(if (count == 1) null else "next", req.url.parameters["cursor"])
                val body = buildJsonObject {
                    put("scheduleVersion", 1); put("userId", if (count == 2 && mode == "owner") "other" else "me"); put("conversationId", "room")
                    put("items", JsonArray(listOf(row(if (count == 2 && mode == "duplicate") "item1" else "item$count"))))
                    val more = count == 1 || mode == "repeated"
                    put("hasMore", more); put("nextCursor", if (more) JsonPrimitive("next") else JsonNull)
                }.toString()
                respond(body, if (count == 2 && mode == "failure") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://scheduled.test" }).scheduledMessages("room", "me")
                assertEquals(mode, mode == "valid", result is PulseResult.Success)
                if (result is PulseResult.Success) assertEquals(listOf("item1", "item2"), result.value.items.map { it.id })
                assertEquals(2, count)
            } finally { http.close() }
        }
    }
    @Test fun cancellationFailureRemainsRetryableUntilAnExactReceiptArrives() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("DELETE", request.method.value)
            assertEquals("/api/scheduled/queued", request.url.encodedPath)
            attempts++
            if (attempts == 1) throw java.io.IOException("Response lost after server commit")
            respond("""{"scheduleVersion":1,"userId":"me","conversationId":"room","scheduledId":"queued","ok":true,"cancelled":true}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://scheduled.test" })
            assertTrue(api.cancelScheduled("queued", "me") is PulseResult.Failure)
            assertTrue(api.cancelScheduled("queued", "me") is PulseResult.Success)
            assertEquals(2, attempts)
        } finally { http.close() }
    }
    @Test fun creationRequiresTheExactRequestTokenAndBooleanReplayReceipt() = runTest {
        for (mode in listOf("valid", "replayed", "missing", "wrong", "string-replayed", "missing-replayed", "sent", "cancelled")) {
            val http = HttpClient(MockEngine { request ->
                assertEquals("/api/conversations/room/scheduled/create-v2", request.url.encodedPath)
                val submitted = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                assertEquals("scheduled-operation-0001", submitted["clientId"]?.jsonPrimitive?.content)
                val body = buildJsonObject {
                    put("scheduleVersion", 1); put("userId", "me"); put("conversationId", "room")
                    if (mode != "missing") put("clientId", if (mode == "wrong") "wrong-token" else "scheduled-operation-0001")
                    if (mode == "string-replayed") put("replayed", "true")
                    else if (mode != "missing-replayed") put("replayed", mode == "replayed")
                    put("item", JsonObject(row().toMutableMap().apply {
                        if (mode == "sent") put("sentAt", JsonPrimitive(whenIso))
                        if (mode == "cancelled") put("cancelledAt", JsonPrimitive(whenIso))
                    }))
                }.toString()
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://scheduled.test" }).scheduleMessage("room", "me", "Hello", whenIso, "scheduled-operation-0001")
                assertEquals("$mode: $result", mode in listOf("valid", "replayed"), result is PulseResult.Success)
            } finally { http.close() }
        }
    }
    @Test fun oldServerRefusalNeverFallsBackToNonIdempotentCreation() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine { request ->
            attempts++
            assertEquals("/api/conversations/room/scheduled/create-v2", request.url.encodedPath)
            respond("{}", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            val result = PulseApi(http, gateway = { "https://scheduled.test" }).scheduleMessage("room", "me", "Hello", whenIso, "scheduled-operation-0001")
            assertTrue(result is PulseResult.Failure)
            assertEquals(1, attempts)
        } finally { http.close() }
    }
}

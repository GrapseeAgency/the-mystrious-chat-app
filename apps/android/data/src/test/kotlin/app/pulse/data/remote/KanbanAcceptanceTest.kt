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

class KanbanAcceptanceTest {
    @Test fun noTaskSuccessWithoutOwnedCanonicalState() = runTest {
        for (action in listOf("create", "update", "delete", "list")) {
            val modes = listOf("valid", "legacy", "version", "owner") + when (action) {
                "delete" -> listOf("id", "false-ok")
                "list" -> listOf("room", "stamp", "duplicate")
                "create" -> listOf("room", "stamp", "column", "title", "source", "assignee")
                else -> listOf("id", "room", "stamp", "column", "title", "assignee")
            }
            for (mode in modes) {
                val card = Json.parseToJsonElement("""{"id":"k","conversationId":"room","title":"Task","column":"doing","position":1,"createdById":"me","assigneeId":null,"sourceMessageId":"source","createdAt":"2026-10-22T00:00:00Z","updatedAt":"2026-10-22T00:00:00Z"}""").jsonObject.toMutableMap()
                when (mode) {
                    "id" -> card["id"] = JsonPrimitive("other")
                    "room" -> card["conversationId"] = JsonPrimitive("other")
                    "stamp" -> card.remove("updatedAt")
                    "column" -> card["column"] = JsonPrimitive("todo")
                    "title" -> card["title"] = JsonPrimitive("Ignored edit")
                    "source" -> card.remove("sourceMessageId")
                    "assignee" -> card["assigneeId"] = JsonPrimitive("other")
                }
                val response = buildJsonObject {
                    if (mode != "legacy") {
                        put("boardReceiptVersion", if (mode == "version") 0 else 1)
                        put("userId", if (mode == "owner") "other" else "me")
                        put("conversationId", "room")
                    }
                    put("card", JsonObject(card)); put("cards", JsonArray(if (mode == "duplicate") listOf(JsonObject(card), JsonObject(card)) else listOf(JsonObject(card))))
                    put("cardId", if (mode == "id") "other" else "k"); put("ok", mode != "false-ok")
                }
                val http = HttpClient(MockEngine { respond(response.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://board.test" })
                    val result = when (action) {
                        "create" -> api.createKanbanCard("room", "me", "Task", "doing", null, "source")
                        "update" -> api.updateKanbanCard("k", "me", "Task", "doing", null, true, null)
                        "delete" -> api.deleteKanbanCard("k", "me")
                        else -> api.kanbanBoard("room", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

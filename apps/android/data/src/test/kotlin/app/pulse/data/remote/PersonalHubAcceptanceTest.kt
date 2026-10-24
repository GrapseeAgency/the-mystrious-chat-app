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

class PersonalHubAcceptanceTest {
    @Test fun ownedReadsAndTaskIntentsRequireCanonicalResponses() = runTest {
        for (action in listOf("wallet", "list", "create", "update", "delete")) {
            val modes = listOf("valid", "empty", "owner") + when (action) {
                "wallet" -> listOf("ledger")
                "delete" -> listOf("id", "ok")
                "list" -> listOf("row-owner", "stamp")
                else -> listOf("row-owner", "stamp", "title", "status") + if (action == "update") listOf("id") else emptyList()
            }
            for (mode in modes) {
                val task = buildJsonObject {
                    put("id", if (mode == "id") "other" else "t")
                    put("ownerId", if (mode == "owner" || mode == "row-owner") "other" else "me")
                    put("title", if (mode == "title") "Ignored edit" else "Task")
                    put("status", if (mode == "status") "todo" else "done")
                    put("createdAt", "2026-10-22T00:00:00Z")
                    if (mode != "stamp") put("updatedAt", "2026-10-22T00:00:00Z")
                }
                val body = if (mode == "empty") "{}" else buildJsonObject {
                    put("userId", if (mode == "owner") "other" else "me")
                    put("task", task); put("tasks", JsonArray(listOf(task)))
                    put("taskId", if (mode == "id") "other" else "t"); put("ok", mode != "ok")
                    put("wallet", buildJsonObject { put("userId", if (mode == "owner") "other" else "me"); put("coins", 1); put("gems", 0) })
                    if (mode != "ledger") put("ledger", JsonArray(emptyList()))
                }.toString()
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://personal.test" })
                    val result = when (action) {
                        "wallet" -> api.wallet("me")
                        "list" -> api.hubTasks("me")
                        "create" -> api.createHubTask("me", "Task", "done")
                        "update" -> api.updateHubTask("t", "me", "Task", "done")
                        else -> api.deleteHubTask("t", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

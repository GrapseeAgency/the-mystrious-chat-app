package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomControlAcceptanceTest {
    @Test fun invalidListsRowsAndDeletionReceiptsNeverConfirmControls() = runTest {
        for (action in listOf("list", "create", "patch", "delete", "hooks", "hookCreate", "hookDelete")) {
            for (mode in listOf("valid", "empty", "wrong", "missing")) {
                val rule = buildJsonObject {
                    put("id", if (mode == "wrong") "other" else "rule")
                    put("conversationId", if (mode == "wrong") "other" else "room")
                    put("trigger", "Hello"); put("reply", "Welcome"); put("hits", 0)
                    if (mode != "missing") put("enabled", action != "patch")
                }
                val hook = buildJsonObject {
                    put("id", "hook"); put("name", "Build bot"); put("createdBy", "me")
                    if (mode != "missing") put("token", if (mode == "wrong") "" else "capability")
                    put("url", "/api/webhooks/capability")
                }
                val body = if (mode == "empty") "{}" else if (action == "hookCreate") hook.toString() else buildJsonObject {
                    put("automation", rule)
                    if (mode != "missing") {
                        put("automations", JsonArray(listOf(rule))); put("webhooks", JsonArray(listOf(hook)))
                        put("deleted", true)
                    }
                    put("ok", true); put("userId", if (mode == "wrong") "other" else "me")
                    put("automationId", "rule"); put("token", "capability")
                }.toString()
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://controls.test" })
                    val result = when (action) {
                        "list" -> api.automations("room", "me")
                        "create" -> api.createAutomation("room", "me", "Hello", "Welcome")
                        "patch" -> api.patchAutomation("rule", "me", enabled = false, trigger = null)
                        "delete" -> api.deleteAutomation("rule", "me")
                        "hooks" -> api.webhooks("room", "me")
                        "hookCreate" -> api.createWebhook("room", "Build bot", "me")
                        else -> api.deleteWebhook("capability", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

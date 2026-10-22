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

class HubAppAcceptanceTest {
    @Test fun appActionsRequireTheRequestedOwnerAppAndState() = runTest {
        for (action in listOf("state", "connect", "disconnect", "community", "join")) {
            val modes = listOf("valid", "empty", "owner", "app", "version") + when (action) {
                "state" -> listOf("count", "stamp", "status")
                "connect", "disconnect" -> listOf("count", "status", "ignored")
                else -> listOf("roster", "count", "private", "preview")
            }
            for (mode in modes) {
                val desired = action != "disconnect"
                val installed = if (mode == "ignored") !desired else desired
                val body = if (mode == "empty") "{}" else buildJsonObject {
                    put("appVersion", if (mode == "version") 0 else 1); put("appId", if (mode == "app") "2" else "1")
                    put("userId", if (mode == "owner") "other" else "me")
                    put("installed", installed); if (installed) put("status", if (mode == "status") "muted" else "connected") else if (mode == "status") put("status", "connected")
                    put("installs", if (mode == "count") -1 else 1)
                    if (mode != "stamp") put("installedAt", "2026-10-22T00:00:00Z")
                    put("installers", JsonArray(emptyList()))
                    put("joined", mode != "preview"); put("memberCount", if (mode == "count") 2 else 1)
                    put("conversation", buildJsonObject {
                        put("id", "room"); put("isGroup", true)
                        if (mode == "private") put("myDraft", "private draft")
                        put("members", JsonArray(if (mode == "roster" || mode == "preview") emptyList() else listOf(buildJsonObject { put("id", "me"); put("name", "Me") })))
                    })
                }.toString()
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://hub-app.test" })
                    val result = when (action) {
                        "state" -> api.appInstallState("1", "me")
                        "connect" -> api.installApp("1", "me")
                        "disconnect" -> api.uninstallApp("1", "me")
                        "community" -> api.appCommunity("1", "me")
                        else -> api.joinAppCommunity("1", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid" || (action == "community" && mode == "preview"), result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

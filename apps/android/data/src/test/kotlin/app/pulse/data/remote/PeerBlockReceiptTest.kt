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

class PeerBlockReceiptTest {
    @Test fun `pair reads and mutations require exact owned typed receipts`() = runTest {
        for (desired in listOf(false, true)) for (mode in listOf("valid", "empty", "owner", "target", "version", "ok", "missing", "quoted", "opposite")) {
            val fields = buildJsonObject {
                put("ok", true); put("blockReceiptVersion", 1); put("actorId", "owner"); put("targetId", "peer"); put("blocked", desired)
            }.toMutableMap()
            when (mode) {
                "empty" -> fields.clear()
                "owner" -> fields["actorId"] = JsonPrimitive("other")
                "target" -> fields["targetId"] = JsonPrimitive("other")
                "version" -> fields["blockReceiptVersion"] = JsonPrimitive("1")
                "ok" -> fields["ok"] = JsonPrimitive("true")
                "missing" -> fields.remove("blocked")
                "quoted" -> fields["blocked"] = JsonPrimitive(desired.toString())
                "opposite" -> fields["blocked"] = JsonPrimitive(!desired)
            }
            val http = HttpClient(MockEngine { respond(JsonObject(fields).toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://blocks.test" })
                assertEquals(mode, mode in listOf("valid", "opposite"), api.blockState("peer", "owner") is PulseResult.Success)
                val result = if (desired) api.blockUser("peer", "owner") else api.unblockUser("peer", "owner")
                assertEquals("$mode/$desired", mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }

    @Test fun `list cannot default missing malformed or foreign data to an empty success`() = runTest {
        for (mode in listOf("valid", "empty-list", "missing-list", "foreign", "version", "duplicate", "self", "blank", "date", "missing-date")) {
            val row = buildJsonObject {
                put("id", when(mode) { "self" -> "owner"; "blank" -> " "; else -> "peer" }); put("name", "Peer")
                if (mode != "missing-date") put("blockedAt", if(mode == "date") "invalid" else "2026-01-01T00:00:00.000Z")
            }
            val body = buildJsonObject {
                put("blockListVersion", if(mode == "version") JsonPrimitive("1") else JsonPrimitive(1))
                put("userId", if(mode == "foreign") "other" else "owner")
                if (mode != "missing-list") put("blocks", JsonArray(when(mode) {
                    "empty-list" -> emptyList(); "duplicate" -> listOf(row,row); else -> listOf(row)
                }))
            }
            val http = HttpClient(MockEngine { respond(body.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://blocks.test" }).blockedAccounts("owner")
                assertEquals(mode, mode in listOf("valid", "empty-list"), result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

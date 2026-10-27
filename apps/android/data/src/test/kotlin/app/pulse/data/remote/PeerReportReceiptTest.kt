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

class PeerReportReceiptTest {
    @Test fun `report requires exact owner peer reason evidence and typed confirmation`() = runTest {
        val evidence = " evidence 😀 "
        for (mode in listOf("valid", "empty", "owner", "target", "reason", "details", "reported", "version", "updated", "date")) {
            val fields = buildJsonObject {
                put("ok", true); put("reportReceiptVersion", 1); put("reporterId", "owner"); put("reportedId", "peer")
                put("reason", "spam"); put("details", evidence); put("reported", true); put("updated", false)
                put("reportedAt", "2026-01-01T00:00:00.000Z")
            }.toMutableMap()
            when (mode) {
                "empty" -> fields.clear()
                "owner" -> fields["reporterId"] = JsonPrimitive("other")
                "target" -> fields["reportedId"] = JsonPrimitive("other")
                "reason" -> fields["reason"] = JsonPrimitive("other")
                "details" -> fields["details"] = JsonPrimitive("different")
                "reported" -> fields["reported"] = JsonPrimitive(false)
                "version" -> fields["reportReceiptVersion"] = JsonPrimitive("1")
                "updated" -> fields.remove("updated")
                "date" -> fields["reportedAt"] = JsonPrimitive("invalid")
            }
            val http = HttpClient(MockEngine { respond(JsonObject(fields).toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://reports.test" }).reportUser("peer", "owner", "spam", evidence)
                assertEquals(mode, mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }

    @Test fun `report history cannot invent missing rows or accept another owners history`() = runTest {
        for (mode in listOf("valid", "empty", "missing", "owner", "target", "duplicate", "reason", "date")) {
            val row = buildJsonObject {
                put("reason", if(mode == "reason") "unknown" else "spam")
                put("at", if(mode == "date") "invalid" else "2026-01-01T00:00:00.000Z")
            }
            val body = buildJsonObject {
                put("reportHistoryVersion", 1); put("reporterId", if(mode == "owner") "other" else "owner")
                put("reportedId", if(mode == "target") "other" else "peer")
                if(mode != "missing") put("reasons", JsonArray(when(mode) {
                    "empty" -> emptyList(); "duplicate" -> listOf(row,row); else -> listOf(row)
                }))
            }
            val http = HttpClient(MockEngine { respond(body.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://reports.test" }).reportReasons("peer", "owner")
                assertEquals(mode, mode in listOf("valid", "empty"), result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

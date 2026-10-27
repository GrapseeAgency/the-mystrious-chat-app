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

class ConversationPreferenceReceiptTest {
    @Test fun `all flags require owned typed receipts and retain the exact server timestamps`() = runTest {
        val stamp = "2001-01-01T00:00:00.000Z"
        for (enabled in listOf(false, true)) for (mode in listOf("valid", "empty", "foreign-room", "foreign-owner", "wrong-value", "missing-stamp", "bad-stamp", "quoted-ok", "quoted-version")) {
            val fields = buildJsonObject {
                put("ok", true); put("conversationPreferenceVersion", 1); put("conversationId", "room"); put("userId", "owner")
                put("pinned", enabled); put("archived", enabled); put("mutePreset", if (enabled) JsonPrimitive("8h") else JsonNull)
                for (key in listOf("pinnedAt", "archivedAt", "mutedUntil")) put(key, if (enabled) JsonPrimitive(stamp) else JsonNull)
            }.toMutableMap()
            when (mode) {
                "empty" -> fields.clear()
                "foreign-room" -> fields["conversationId"] = JsonPrimitive("other")
                "foreign-owner" -> fields["userId"] = JsonPrimitive("other")
                "wrong-value" -> { fields["pinned"] = JsonPrimitive(!enabled); fields["archived"] = JsonPrimitive(!enabled); fields["mutePreset"] = JsonPrimitive("1w") }
                "missing-stamp" -> for (key in listOf("pinnedAt", "archivedAt", "mutedUntil")) fields.remove(key)
                "bad-stamp" -> for (key in listOf("pinnedAt", "archivedAt", "mutedUntil")) fields[key] = JsonPrimitive("not-a-date")
                "quoted-ok" -> fields["ok"] = JsonPrimitive("true")
                "quoted-version" -> fields["conversationPreferenceVersion"] = JsonPrimitive("1")
            }
            val http = HttpClient(MockEngine { respond(JsonObject(fields).toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://flags.test" })
                val results = listOf(api.setConversationPinned("room", "owner", enabled), api.setConversationArchived("room", "owner", enabled),
                    api.setConversationMuted("room", "owner", if (enabled) "8h" else null))
                for (result in results) {
                    assertEquals("$mode/$enabled", mode == "valid", result is PulseResult.Success)
                    if (result is PulseResult.Success) {
                        assertEquals(if (enabled) stamp else null, result.value.timestamp)
                        assertEquals(if (enabled) 978307200000L else null, result.value.epochMillis)
                    }
                }
            } finally { http.close() }
        }
    }
}

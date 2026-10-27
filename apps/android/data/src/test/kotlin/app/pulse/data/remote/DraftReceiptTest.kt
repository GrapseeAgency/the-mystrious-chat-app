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

class DraftReceiptTest {
    @Test fun mirrorsRequireTheExactOwnerRoomAndCompleteTextIncludingExplicitClear() = runTest {
        for (mode in listOf("valid", "clear", "legacy", "version", "owner", "room", "clipped", "ok")) {
            val text = if (mode == "clear") "" else "🌿".repeat(1000)
            val receipt = buildJsonObject {
                put("ok", if (mode == "ok") JsonPrimitive("true") else JsonPrimitive(true))
                if (mode != "legacy") put("draftReceiptVersion", if (mode == "version") JsonPrimitive("1") else JsonPrimitive(1))
                put("userId", if (mode == "owner") "other" else "owner")
                put("conversationId", if (mode == "room") "other" else "room")
                put("draft", if (mode == "clipped") text.take(1998) else text)
            }
            var requests = 0
            val http = HttpClient(MockEngine { requests++; respond(receipt.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://draft.test" })
                assertEquals(mode, mode in listOf("valid", "clear"), api.setDraft("room", "owner", text) is PulseResult.Success)
                assertEquals(1, requests)
                val oversized = api.setDraft("room", "owner", "a".repeat(1999) + "🌿")
                assertTrue(oversized is PulseResult.Failure)
                assertEquals(1, requests)
            } finally { http.close() }
        }
    }
}

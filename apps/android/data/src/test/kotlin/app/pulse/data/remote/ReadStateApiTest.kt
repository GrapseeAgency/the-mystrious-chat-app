package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ReadStateApiTest {
    @Test fun bothReadIntentsRequireTheExactReceiptInsteadOfAnyHttpSuccess() = runTest {
        for (anchor in listOf("anchor", null)) for (mode in listOf("valid", "legacy", "false", "version", "room", "owner", "anchor", "intent", "date", "behind", "fence")) {
            val intent = if (anchor == null) "all" else "visible"
            val http = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/read-intent")) return@MockEngine respond(
                    """{"readIntentVersion":2,"conversationId":"room","userId":"me","readIntent":"fence"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                assertEquals("/api/conversations/room/read-state", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"mode\":\"$intent\""))
                val stamp = when (mode) { "date" -> "invalid"; "behind" -> "2026-10-22T00:00:00Z"; else -> "2026-10-22T00:00:10Z" }
                val message = if (mode == "anchor") "\"other\"" else anchor?.let { "\"$it\"" } ?: "null"
                val body = if (mode == "legacy") """{"ok":true}""" else """{"ok":${mode != "false"},"readReceiptVersion":${if (mode == "version") 0 else 3},"readIntent":"${if (mode == "fence") "other-fence" else "fence"}","conversationId":"${if (mode == "room") "other" else "room"}","userId":"${if (mode == "owner") "other" else "me"}","messageId":$message,"mode":"${if (mode == "intent") "wrong" else intent}","lastReadAt":"$stamp"}"""
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://read.test" }).markRead("room", "me", anchor, "2026-10-22T00:00:05Z")
                assertEquals("$mode: $result", mode == "valid", result is PulseResult.Success)
                if (result is PulseResult.Failure) assertEquals("Malformed mutation ACKs are uncertain server responses, not connectivity outages", PulseResult.Failure.Kind.SERVER, result.kind)
            } finally { http.close() }
        }
    }
    @Test fun unsupportedGatewayDoesNotFallbackToMarkAll() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("/api/conversations/room/read-intent", request.url.encodedPath)
            respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://read.test" }).markRead("room", "me", "anchor") is PulseResult.Failure)
            assertEquals(1, calls)
        } finally { http.close() }
    }
    @Test fun manualUnreadRequiresExactOwnerRoomVersionAndDesiredBoolean() = runTest {
        for (on in listOf(true, false)) for (mode in listOf("valid", "legacy", "false", "version", "room", "owner", "opposite", "missing", "null", "string", "ok-string", "version-string", "numeric-owner")) {
            val http = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/read-intent")) return@MockEngine respond(
                    """{"readIntentVersion":2,"conversationId":"room","userId":"me","readIntent":"fence"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                assertEquals("PATCH", request.method.value)
                assertEquals("/api/conversations/room/mark-unread", request.url.encodedPath)
                assertTrue((request.body as TextContent).text.contains("\"on\":$on"))
                val body = buildJsonObject {
                    if (mode == "ok-string") put("ok", "true") else put("ok", mode != "false")
                    if (mode != "legacy") {
                        if (mode == "version-string") put("unreadReceiptVersion", "1")
                        else put("unreadReceiptVersion", if (mode == "version") 0 else 2)
                        put("readIntent", "fence")
                        put("conversationId", if (mode == "room") "other" else "room")
                        if (mode == "numeric-owner") put("userId", 1)
                        else put("userId", if (mode == "owner") "other" else "me")
                        when (mode) {
                            "missing" -> Unit
                            "null" -> put("manualUnread", JsonNull)
                            "string" -> put("manualUnread", on.toString())
                            else -> put("manualUnread", if (mode == "opposite") !on else on)
                        }
                    }
                }.toString()
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://read.test" }).markUnread("room", "me", on)
                assertEquals("$mode: $result", mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
    @Test fun malformedReadIntentCannotDispatchAnyReadOrUnreadWrite() = runTest {
        for (mode in listOf("missing", "owner", "room", "version", "version-string", "numeric-token", "blank")) {
            var writes = 0
            val http = HttpClient(MockEngine { request ->
                if (request.method.value != "GET") writes++
                val receipt = buildJsonObject {
                    if (mode != "missing") {
                        if (mode == "version-string") put("readIntentVersion", "1")
                        else put("readIntentVersion", if (mode == "version") 1 else 2)
                        put("conversationId", if (mode == "room") "other" else "room")
                        put("userId", if (mode == "owner") "other" else "me")
                        if (mode == "numeric-token") put("readIntent", 1) else put("readIntent", if (mode == "blank") " " else "fence")
                    }
                }
                respond(receipt.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway = { "https://read.test" })
                assertEquals(PulseResult.Failure.Kind.VALIDATION, (api.markRead("room", "me", "anchor") as PulseResult.Failure).kind)
                assertTrue(api.markUnread("room", "me", true) is PulseResult.Failure)
                assertEquals(mode, 0, writes)
            } finally { http.close() }
        }
    }

    @Test fun groupRingCarriesTheAdmittedCallIdentity() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/conversations/room/calls/ring", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"callId\":\"admitted-call\""))
            assertTrue(body.contains("\"userId\":\"me\""))
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://read.test" }).postGroupCallRing("room", "me", "voice", "admitted-call") is PulseResult.Success)
        } finally { http.close() }
    }
    @Test fun groupProbeRequiresBoundVersionedCompleteStateAndPreservesErrors() = runTest {
        for (mode in listOf("valid", "empty", "legacy", "room", "owner", "version", "version-string", "missing-members", "missing-call", "kind", "started", "refused")) {
            val http = HttpClient(MockEngine { request ->
                assertEquals("me", request.url.parameters["userId"])
                assertEquals("room", request.url.parameters["conversationId"])
                val member = buildJsonObject {
                    put("id", "peer"); put("name", "Peer"); put("color", "emerald"); put("avatar", JsonNull)
                }
                val body = buildJsonObject {
                    if (mode != "legacy") {
                        if (mode == "version-string") put("callStateVersion", "1")
                        else put("callStateVersion", if (mode == "version") 0 else 1)
                        put("conversationId", if (mode == "room") "other" else "room")
                        put("userId", if (mode == "owner") "other" else "me")
                        if (mode != "missing-call") put("callId", if (mode == "empty") "" else "call")
                        put("kind", if (mode == "kind") "unknown" else "voice")
                        if (mode != "started") put("startedAt", 1000)
                    }
                    if (mode != "missing-members") put("members", kotlinx.serialization.json.JsonArray(if (mode == "empty") emptyList() else listOf(member)))
                }.toString()
                respond(body, if (mode == "refused") HttpStatusCode.Forbidden else HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://read.test" }).groupCallState("room", "me")
                assertEquals("$mode: $result", mode in listOf("valid", "empty"), result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

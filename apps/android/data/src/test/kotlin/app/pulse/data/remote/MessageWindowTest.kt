package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import app.pulse.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageWindowTest {
    private val at = "2026-10-22T00:00:00Z"
    private fun row(id: String, parent: String? = "root") = ChatMessageDto(id, "room", "peer", "body", createdAt = at, parentId = parent)
    private fun page(rows: List<ChatMessageDto> = listOf(row("a"), row("b"))) = MessageWindowDto(
        1, "room", null, "root", row("root", null), rows, true, true,
        MessageWindowPositionDto("a", at), MessageWindowPositionDto("b", at))

    @Test fun validatesOrderScopeDuplicateAndCursorMetadataBeforeCaching() = runTest {
        val valid = page()
        for (value in listOf(valid, valid.copy(messages = listOf(row("b"), row("a"))),
            valid.copy(messages = listOf(row("a"), row("a"))), valid.copy(olderCursor = null),
            valid.copy(messages = listOf(row("a").copy(conversationId = "other"))),
            valid.copy(messages = listOf(row("a").copy(createdAt = "bad"))),
            valid.copy(parent = row("other", null)))) {
            val http = HttpClient(MockEngine { respond(PulseJson.encodeToString(MessageWindowDto.serializer(), value),
                HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://windows.test" }).messageWindow("room", null, "root", null, null)
                assertEquals(value == valid, result is PulseResult.Success)
            } finally { http.close() }
        }
    }

    @Test fun onlyBoundRootRefusalsKeepDenialStatuses() = runTest {
        for ((status, code, expected) in listOf(Triple(403, "thread_access", 403), Triple(410, "thread_gone", 410),
            Triple(404, "message_missing", 409), Triple(410, "message_gone", 409), Triple(403, "proxy", 409), Triple(404, "missing_route", 409))) {
            val http = HttpClient(MockEngine { respond("""{"error":"unavailable","code":"$code"}""",
                HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json")) })
            try {
                val failure = PulseApi(http, gateway = { "https://windows.test" }).messageWindow("room", "reply", "root", null, null) as PulseResult.Failure
                assertEquals(expected, failure.status); assertEquals(code, failure.code)
            } finally { http.close() }
        }
    }
}

package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ThreadPagingApiTest {
    private fun row(id: String, parent: String = "null") = """{"id":"$id","conversationId":"room","senderId":"me","content":"Hi","createdAt":"2026-10-22T00:00:00Z","parentId":$parent}"""
    @Test fun pagesAreCompleteOrFailureAndUnknownRefusalsDoNotPoisonThreadAccess() = runTest {
        for (mode in listOf("valid", "old-route", "missing", "unrelated-denial", "denied", "gone", "interrupted")) {
            var calls = 0
            val http = HttpClient(MockEngine { request ->
                calls++
                assertEquals("/api/messages/root/thread-page", request.url.encodedPath)
                assertEquals(if (calls == 1) null else "next", request.url.parameters["cursor"])
                val status = when (mode) { "old-route", "missing" -> HttpStatusCode.NotFound; "unrelated-denial", "denied" -> HttpStatusCode.Forbidden; "gone" -> HttpStatusCode.Gone; "interrupted" -> if (calls == 2) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK; else -> HttpStatusCode.OK }
                val body = if (status != HttpStatusCode.OK) {
                    val code = when (mode) { "missing" -> "thread_missing"; "denied" -> "thread_access"; "gone" -> "thread_gone"; else -> "unrelated" }
                    """{"error":"failure","code":"$code"}"""
                } else """{"parent":${row("root")},"replies":[${row(if (calls == 1) "a" else "b", "\"root\"")}],"threadPageVersion":1,"hasMore":${calls == 1},"nextCursor":${if (calls == 1) "\"next\"" else "null"}}"""
                respond(body, status, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://thread.test" }).thread("root", "me")
                if (mode == "valid") {
                    assertTrue(result is PulseResult.Success)
                    assertEquals(2, (result as PulseResult.Success).value.replies.size)
                } else {
                    assertTrue(result is PulseResult.Failure)
                    assertEquals(when (mode) { "missing" -> 404; "denied" -> 403; "gone" -> 410; "interrupted" -> 503; else -> 409 }, (result as PulseResult.Failure).status)
                }
                assertEquals(if (mode in listOf("valid", "interrupted")) 2 else 1, calls)
            } finally { http.close() }
        }
    }
}

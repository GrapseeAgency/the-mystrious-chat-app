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

class ReminderPagingTest {
    @Test fun `all pages are consumed without losing same-time rows`() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            val body = if (request.url.parameters["cursor"] == null)
                """{"items":[{"id":"a"}],"nextCursor":"next"}"""
            else """{"items":[{"id":"b"}],"nextCursor":null}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val result = PulseApi(http, gateway = { "https://reminder.test" }).reminders("me", false)
            assertEquals(listOf("a", "b"), (result as PulseResult.Success).value.items.map { it.id })
            assertEquals(2, calls)
        } finally { http.close() }
    }
    @Test fun `failure on later page does not report partial list as success`() = runTest {
        val http = HttpClient(MockEngine { request ->
            val first = request.url.parameters["cursor"] == null
            respond(if (first) """{"items":[{"id":"a"}],"nextCursor":"next"}""" else """{"error":"offline"}""",
                if (first) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "application/json"))
        })
        try { assertTrue(PulseApi(http, gateway = { "https://reminder.test" }).reminders("me", false) is PulseResult.Failure) }
        finally { http.close() }
    }
    @Test fun `repeated cursor terminates with error rather than an infinite loop`() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond("""{"items":[],"nextCursor":"again"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            assertTrue(PulseApi(http, gateway = { "https://reminder.test" }).reminders("me", false) is PulseResult.Failure)
            assertEquals(2, calls)
        } finally { http.close() }
    }
}

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

class PrivateActivityTest {
    @Test fun globalOrForeignActivityIsNeverAdoptedAsMyHistory() = runTest {
        for (mode in listOf("valid", "empty-valid", "legacy", "empty-legacy", "version", "owner", "row-owner", "orphan")) {
            val rowOwner = if (mode == "row-owner") "other" else "me"
            val user = if (mode == "orphan") "null" else """{"id":"$rowOwner"}"""
            val row = """{"id":"a","kind":"message","message":"private text","user":$user}"""
            val logs = if (mode.startsWith("empty")) "[]" else "[$row]"
            val body = if (mode.endsWith("legacy")) """{"logs":$logs}""" else """{"activityVersion":${if (mode == "version") 0 else 1},"userId":"${if (mode == "owner") "other" else "me"}","logs":$logs}"""
            val http = HttpClient(MockEngine { request ->
                assertEquals("me", request.url.parameters["userId"])
                assertEquals("message", request.url.parameters["kind"])
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://private.test" }).hubLogs("me", 80, "message")
                assertEquals(mode, mode == "valid" || mode == "empty-valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageSyncApiTest {
    @Test fun syncAndStableHistoryCursorsRoundTripWithoutQueryInjection() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("2026-10-19T00:00:00+06:00", request.url.parameters["syncFrom"])
            assertEquals("cursor_abc-123", request.url.parameters["syncCursor"])
            assertEquals("id&other", request.url.parameters["beforeId"])
            assertNull(request.url.parameters["other"])
            respond("""{"messages":[],"hasMore":false,"total":0,"syncVersion":1,"nextSyncCursor":null}""",
                headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://fixture.invalid" })
            val result = api.messages("room", syncFrom = "2026-10-19T00:00:00+06:00",
                syncCursor = "cursor_abc-123", beforeId = "id&other")
            assertTrue(result is PulseResult.Success)
            assertEquals(1, (result as PulseResult.Success).value.syncVersion)
            assertEquals(1, calls)
        } finally { http.close() }
    }

    @Test fun oldResponseDoesNotAdvertiseSyncSupport() = runTest {
        val http = HttpClient(MockEngine { respond("""{"messages":[],"hasMore":false,"total":0}""",
            headers = headersOf("Content-Type", "application/json")) })
        try {
            val result = PulseApi(http, gateway = { "https://fixture.invalid" }).messages("room")
            assertNull((result as PulseResult.Success).value.syncVersion)
        } finally { http.close() }
    }
}

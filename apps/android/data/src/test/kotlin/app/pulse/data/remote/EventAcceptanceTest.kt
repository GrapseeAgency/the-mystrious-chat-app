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

class EventAcceptanceTest {
    @Test fun attendanceAndRsvpRequireOwnedVersionedCanonicalReceipts() = runTest {
        for (action in listOf("checkin", "rsvp", "delete")) {
            val nested = if (action == "checkin") "checkIn" else "rsvp"
            val valid = """{"eventReceiptVersion":1,"eventId":"e","userId":"me","ok":true,"$nested":{"id":"r","eventId":"e","userId":"me","status":"going","checkedInAt":"2026-10-22T12:00:00Z"},"counts":{"going":1,"maybe":0,"no":0},"xpAwarded":true,"alreadyCheckedIn":false,"checkedInCount":1}"""
            val modes = listOf("valid", "empty", "version", "event", "owner") + when (action) {
                "delete" -> listOf("false-ok")
                "checkin" -> listOf("missing-stamp", "bad-stamp", "nested-event", "nested-owner", "bonus", "count")
                else -> listOf("nested-event", "nested-owner", "status", "counts")
            }
            for (mode in modes) {
                val fields = Json.parseToJsonElement(valid).jsonObject.toMutableMap()
                when (mode) {
                    "empty" -> fields.clear()
                    "version" -> fields["eventReceiptVersion"] = JsonPrimitive(0)
                    "event" -> fields["eventId"] = JsonPrimitive("other")
                    "owner" -> fields["userId"] = JsonPrimitive("other")
                    "false-ok" -> fields["ok"] = JsonPrimitive(false)
                    "missing-stamp" -> fields[nested] = JsonNull
                    "bonus" -> fields["alreadyCheckedIn"] = JsonPrimitive(true)
                    "count" -> fields["checkedInCount"] = JsonPrimitive(0)
                    "counts" -> fields["counts"] = Json.parseToJsonElement("""{"going":0,"maybe":0,"no":0}""")
                    "nested-event", "nested-owner", "status", "bad-stamp" -> {
                        val row = fields.getValue(nested).jsonObject.toMutableMap()
                        row[when (mode) { "nested-event" -> "eventId"; "nested-owner" -> "userId"; "status" -> "status"; else -> "checkedInAt" }] = JsonPrimitive("wrong")
                        fields[nested] = JsonObject(row)
                    }
                }
                val http = HttpClient(MockEngine { respond(JsonObject(fields).toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://event.test" })
                    val result = when (action) { "checkin" -> api.checkinEvent("e", "me"); "rsvp" -> api.rsvpEvent("e", "me", "going"); else -> api.deleteEvent("e", "me") }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
    @Test fun anEmptyOrForeignCreationResponseCannotClearTheForm() = runTest {
        for (body in listOf("{}", """{"event":{"id":"","title":"Meet","createdById":"me","startsAt":"2026-10-22T12:00:00Z"}}""",
            """{"event":{"id":"e","title":"Meet","createdById":"other","startsAt":"2026-10-22T12:00:00Z"}}""")) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try { assertTrue(PulseApi(http, gateway = { "https://event.test" }).createEvent("room", "me", "Meet", "2026-10-22T12:00:00Z", null, null) is PulseResult.Failure) }
            finally { http.close() }
        }
    }
}

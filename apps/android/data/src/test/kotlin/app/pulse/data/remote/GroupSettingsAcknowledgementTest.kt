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

class GroupSettingsAcknowledgementTest {
    @Test fun missingFieldsCannotAcknowledgeTurningSettingsOff() = runTest {
        for (body in listOf("{}", """{"ok":true}""", """{"ok":true,"slowModeSeconds":null,"screenPrivacy":null}""")) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://groups.test" })
                val slow = api.setSlowMode("room", "me", 0)
                val privacy = api.setMyScreenPrivacy("room", "me", false)
                assertEquals(PulseResult.Failure.Kind.SERVER, (slow as PulseResult.Failure).kind)
                assertEquals(PulseResult.Failure.Kind.SERVER, (privacy as PulseResult.Failure).kind)
            } finally { http.close() }
        }
    }
    @Test fun explicitAcknowledgementsPreserveZeroAndFalse() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"ok":true,"slowModeSeconds":0,"screenPrivacy":false}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://groups.test" })
            assertTrue(api.setSlowMode("room", "me", 0) is PulseResult.Success)
            assertTrue(api.setMyScreenPrivacy("room", "me", false) is PulseResult.Success)
            assertTrue(api.setSlowMode("room", "me", 5) is PulseResult.Failure)
            assertTrue(api.setMyScreenPrivacy("room", "me", true) is PulseResult.Failure)
        } finally { http.close() }
    }
    @Test fun ttlNeedsTheRightRoomAndAnExplicitSupportedValue() = runTest {
        for (body in listOf(
            """{"conversation":{"id":"other","ttlSeconds":86400}}""",
            """{"conversation":{"id":"room"}}""",
            """{"conversation":{"id":"room","ttlSeconds":3}}""",
        )) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://groups.test" }).setDisappearingTtl("room", "me", 86400)
                assertEquals(PulseResult.Failure.Kind.SERVER, (result as PulseResult.Failure).kind)
            } finally { http.close() }
        }
    }
    @Test fun ttlAdoptsNewerServerTruthInsteadOfInventingTheRequestedValue() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"conversation":{"id":"room","ttlSeconds":604800}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val result = PulseApi(http, gateway = { "https://groups.test" }).setDisappearingTtl("room", "me", 86400)
            assertEquals(604800, (result as PulseResult.Success).value.ttlSeconds)
        } finally { http.close() }
    }
    @Test fun roomPrivacyCannotBeConfirmedByMissingFieldsOrAnotherRoom() = runTest {
        for (body in listOf("""{"conversation":{"id":"room"}}""", """{"conversation":{"id":"other","screenPrivacy":true}}""")) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://groups.test" }).patchConversation("room", "me", screenPrivacy = true)
                assertTrue(result is PulseResult.Failure)
            } finally { http.close() }
        }
    }
}

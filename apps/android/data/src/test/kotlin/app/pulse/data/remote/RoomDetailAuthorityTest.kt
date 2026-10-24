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

class RoomDetailAuthorityTest {
    @Test fun `detail escapes room IDs and requires the requested room and viewer`() = runTest {
        for (mode in listOf("valid", "foreign", "missing-member", "quoted-policy")) {
            val room = "room/one?&#% space"
            val http = HttpClient(MockEngine { request ->
                assertEquals("/api/conversations/room%2Fone%3F%26%23%25%20space", request.url.encodedPath)
                assertEquals("me&other=x#part", request.url.parameters["userId"])
                val id = if (mode == "foreign") "foreign" else room
                val members = if (mode == "missing-member") "[]" else """[{"id":"me&other=x#part","name":"Me"}]"""
                val policy = if (mode == "quoted-policy") "\"false\"" else "false"
                respond("""{"conversation":{"id":"$id","members":$members,"screenPrivacy":$policy}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            })
            try {
                val result = PulseApi(http, gateway = { "https://groups.test" }).conversationDetail(room, "me&other=x#part")
                assertEquals(mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
    @Test fun `mutation details must retain membership group identity and touched field presence`() = runTest {
        for (mode in listOf("valid", "missing-member", "dm", "missing-photo", "missing-name")) {
            val members = if (mode == "missing-member") "[]" else """[{"id":"me","name":"Me"}]"""
            val photo = if (mode == "missing-photo") "" else "\"photo\":null,"
            val name = if (mode == "missing-name") "" else "\"name\":\"Current server name\","
            val body = """{"conversation":{"id":"room","isGroup":${mode != "dm"},$photo$name"members":$members,"broadcastMode":false,"screenPrivacy":false}}"""
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://groups.test" }).patchConversation("room", "me", name = "Requested name", photo = "/api/uploads/new.png", broadcast = true, screenPrivacy = true)
                assertEquals(mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

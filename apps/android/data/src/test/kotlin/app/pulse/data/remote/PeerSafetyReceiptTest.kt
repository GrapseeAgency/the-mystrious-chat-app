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

class PeerSafetyReceiptTest {
    @Test fun `comparison markers require exact owned typed state and real digits`() = runTest {
        for (desired in listOf(false,true)) for(mode in listOf("valid","empty","owner","peer","version","quoted","opposite","digits","missing-stamp","bad-stamp")) {
            val state = if(mode == "opposite") !desired else desired
            val fields = buildJsonObject {
                put("ok",true); put("safetyReceiptVersion",1); put("ownerId","owner"); put("peerId","peer")
                put("safetyNumber",List(12) { "12345" }.joinToString(" ")); put("verified",state)
                put("verifiedAt",if(state) JsonPrimitive("2026-01-01T00:00:00.000Z") else JsonNull)
            }.toMutableMap()
            when(mode) {
                "empty" -> fields.clear()
                "owner" -> fields["ownerId"] = JsonPrimitive("other")
                "peer" -> fields["peerId"] = JsonPrimitive("other")
                "version" -> fields["safetyReceiptVersion"] = JsonPrimitive("1")
                "quoted" -> fields["verified"] = JsonPrimitive(state.toString())
                "digits" -> fields["safetyNumber"] = JsonPrimitive("12345")
                "missing-stamp" -> fields.remove("verifiedAt")
                "bad-stamp" -> fields["verifiedAt"] = JsonPrimitive("invalid")
            }
            val http = HttpClient(MockEngine { respond(JsonObject(fields).toString(),HttpStatusCode.OK,headersOf("Content-Type","application/json")) })
            try {
                val api = PulseApi(http,gateway={"https://safety.test"})
                assertEquals(mode,mode in listOf("valid","opposite"),api.safetyState("peer","owner") is PulseResult.Success)
                val result = if(desired) api.safetyVerify("peer","owner") else api.safetyUnverify("peer","owner")
                assertEquals(mode,mode == "valid",result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

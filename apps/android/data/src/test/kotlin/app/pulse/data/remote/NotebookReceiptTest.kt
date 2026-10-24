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

class NotebookReceiptTest {
    @Test fun `notebook receipt must name one owner and contain only that owner's private chat`() = runTest {
        for (mode in listOf("valid", "legacy", "version", "owner", "empty", "foreign", "shared", "group", "ordinary")) {
            val row = buildJsonObject {
                put("id", if(mode == "empty") "" else "notebook")
                put("isGroup", mode == "group"); put("isSelf", mode != "ordinary")
                put("members", buildJsonArray {
                    add(buildJsonObject { put("id", if(mode == "foreign") "other" else "owner"); put("name", "Owner") })
                    if(mode == "shared") add(buildJsonObject { put("id", "other"); put("name", "Other") })
                })
            }
            val body = buildJsonObject {
                if(mode != "legacy") put("notebookReceiptVersion", if(mode == "version") JsonPrimitive("1") else JsonPrimitive(1))
                put("userId", if(mode == "owner") "other" else "owner"); put("conversation", row)
            }
            val http = HttpClient(MockEngine { respond(body.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
            try {
                val result = PulseApi(http, gateway = { "https://notebook.test" }).createSelfChat("owner")
                assertEquals(mode, mode == "valid", result is PulseResult.Success)
            } finally { http.close() }
        }
    }
}

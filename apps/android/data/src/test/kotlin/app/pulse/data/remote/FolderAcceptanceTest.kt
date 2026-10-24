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

class FolderAcceptanceTest {
    @Test fun folderReadsAndWritesRequireOwnedExactReceipts() = runTest {
        for (action in listOf("list", "create", "update", "members", "delete")) {
            for (mode in listOf("valid", "empty", "owner", "version", "stringVersion", "missing", "wrong")) {
                val row = buildJsonObject {
                    put("id", "folder"); put("name", if (mode == "wrong" && action in listOf("create", "update")) "Other" else "Work")
                    put("emoji", "folder"); put("position", 0)
                    if (mode != "missing") put("conversationIds", JsonArray(if (mode == "wrong" && action == "members") emptyList() else listOf(JsonPrimitive("room"))))
                }
                val body = if (mode == "empty") "{}" else buildJsonObject {
                    if (mode == "stringVersion") put("folderVersion", "1") else put("folderVersion", if (mode == "version") 0 else 1)
                    put("userId", if (mode == "owner") "other" else "me")
                    if (mode != "missing") {
                        put("folder", row)
                        put("folders", JsonArray(if (mode == "wrong") listOf(row, row) else listOf(row)))
                        put("folderId", if (mode == "wrong") "other" else "folder")
                        put("deleted", true); put("ok", true)
                    }
                }.toString()
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://folder.test" })
                    val result = when (action) {
                        "list" -> api.folders("me")
                        "create" -> api.createFolder("me", "Work", "folder")
                        "update" -> api.patchFolder("folder", "Work", "folder", 0, "me")
                        "members" -> api.setFolderConversations("folder", listOf("room"), "me")
                        else -> api.deleteFolder("folder", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

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

class GameReadAcceptanceTest {
    @Test fun privateGameReadsRequireOwnedScopedEnvelopes() = runTest {
        for (action in listOf("game", "games", "tournament", "tournaments")) {
            val modes = listOf("valid", "empty", "owner", "version", "room", "missing") +
                if (action in listOf("game", "tournament")) listOf("id") else listOf("duplicate")
            for (mode in modes) {
                val row = buildJsonObject {
                    put("id", if (mode == "id") "other" else "item")
                    put("conversationId", if (mode == "room") "other" else "room")
                    put("board", "         "); put("playerXId", "me"); put("moveCount", 0); put("turn", "X")
                    put("status", if (action.startsWith("game")) "active" else "running")
                    put("joined", true); put("entries", JsonArray(emptyList()))
                }
                val body = if (mode == "empty") "{}" else buildJsonObject {
                    put("gameReadVersion", if (mode == "version") 0 else 1)
                    put("userId", if (mode == "owner") "other" else "me"); put("conversationId", "room")
                    if (mode != "missing") {
                        put("match", row); put("tournament", row)
                        val rows = JsonArray(if (mode == "duplicate") listOf(row, row) else listOf(row))
                        put("matches", rows); put("tournaments", rows)
                    }
                }.toString()
                val http = HttpClient(MockEngine { req ->
                    assertEquals("me", req.url.parameters["userId"])
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                })
                try {
                    val api = PulseApi(http, gateway = { "https://game-read.test" })
                    val result = when (action) {
                        "game" -> api.game("item", "me")
                        "games" -> api.games("room", "me")
                        "tournament" -> api.tournament("item", "me")
                        else -> api.tournaments("room", "me")
                    }
                    assertEquals("$action:$mode", mode == "valid", result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

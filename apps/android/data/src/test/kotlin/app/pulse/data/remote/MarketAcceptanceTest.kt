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

class MarketAcceptanceTest {
    @Test fun marketReadsAndWritesRequireOwnedCanonicalReceipts() = runTest {
        for (action in listOf("list", "create", "buy")) {
            val modes = listOf("valid", "empty", "version", "owner") + when (action) {
                "buy" -> listOf("id", "ok", "wallet-owner", "negative", "gems")
                else -> listOf("seller", "stamp", "price", "asset", "mine", "status", "buyer") +
                    if (action == "list") listOf("duplicate", "sold-deleted", "foreign-sold") else listOf("title", "price-mismatch", "seller-other")
            }
            for (mode in modes) {
                val row = buildJsonObject {
                    put("id", "item"); put("title", if (mode == "title") "Ignored input" else "Thing")
                    put("price", when (mode) { "price" -> 0; "price-mismatch" -> 26; else -> 25 })
                    put("asset", if (mode == "asset") "GEM" else "PC")
                    put("status", when (mode) { "status" -> "reserved"; "sold-deleted", "foreign-sold" -> "sold"; else -> "open" })
                    put("mine", mode != "mine" && mode != "seller-other" && mode != "foreign-sold")
                    if (mode != "stamp") put("createdAt", "2026-10-22T00:00:00Z")
                    put("seller", buildJsonObject { put("id", when (mode) { "seller" -> ""; "seller-other", "foreign-sold" -> "other"; else -> "me" }); put("name", "Me") })
                    if (mode == "buyer") put("buyer", buildJsonObject { put("id", "other") })
                }
                val body = if (mode == "empty") "{}" else buildJsonObject {
                    put("marketVersion", if (mode == "version") 0 else 1)
                    put("userId", if (mode == "owner") "other" else "me")
                    put("listing", row)
                    put("listings", JsonArray(if (mode == "duplicate") listOf(row, row) else listOf(row)))
                    put("listingId", if (mode == "id") "other" else "item"); put("ok", mode != "ok")
                    put("wallet", buildJsonObject {
                        put("userId", if (mode == "wallet-owner") "other" else "me")
                        put("coins", if (mode == "negative") -1 else 10)
                        if (mode != "gems") put("gems", 0)
                    })
                }.toString()
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
                try {
                    val api = PulseApi(http, gateway = { "https://market.test" })
                    val result = when (action) {
                        "list" -> api.market("me")
                        "create" -> api.createListing("me", "Thing", null, 25)
                        else -> api.buyListing("item", "me")
                    }
                    assertEquals("$action:$mode", mode in listOf("valid", "sold-deleted"), result is PulseResult.Success)
                } finally { http.close() }
            }
        }
    }
}

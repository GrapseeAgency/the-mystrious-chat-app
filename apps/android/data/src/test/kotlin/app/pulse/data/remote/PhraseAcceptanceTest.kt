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

class PhraseAcceptanceTest {
    @Test fun creationDecodesTheEnvelopeOnceAndKeepsTheRealId() = runTest {
        for ((body, accepted) in listOf(
            """{"phrase":{"id":"p1","text":"Hello","position":2}}""" to true,
            """{"id":"p1","text":"Hello","position":2}""" to false,
            """{"phrase":{"id":"","text":"Hello","position":2}}""" to false,
            """{"phrase":{"id":"p1","text":"Wrong","position":2}}""" to false,
            "{}" to false,
        )) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.Created, headersOf("Content-Type","application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://phrase.test" })
                val result = api.createPhrase("me", " Hello ")
                assertEquals(body, accepted, result is PulseResult.Success)
                if (result is PulseResult.Success) {
                    assertEquals("p1", result.value.phrase?.id)
                    assertEquals("Hello", result.value.phrase?.text)
                }
            } finally { http.close() }
        }
    }
    @Test fun deleteRequiresAnExactPositiveOwnedReceiptAndListsCannotDefaultToEmpty() = runTest {
        for ((body, accepted) in listOf(
            """{"ok":true,"deleted":true,"userId":"me","phraseId":"p1"}""" to true,
            """{"ok":true}""" to false,
            """{"ok":true,"deleted":false,"userId":"me","phraseId":"p1"}""" to false,
            """{"ok":true,"deleted":true,"userId":"other","phraseId":"p1"}""" to false,
            """{"ok":true,"deleted":true,"userId":"me","phraseId":"other"}""" to false,
            "{}" to false,
        )) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type","application/json")) })
            try {
                val api = PulseApi(http, gateway = { "https://phrase.test" })
                assertEquals(body, accepted, api.deletePhrase("me", "p1") is PulseResult.Success)
                assertTrue(api.phrases("me") is PulseResult.Failure)
                assertTrue(api.quickPhrases("me") is PulseResult.Failure)
            } finally { http.close() }
        }
    }
}

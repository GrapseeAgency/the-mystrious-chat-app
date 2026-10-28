package app.pulse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.local.PulseDatabase
import app.pulse.data.remote.PulseApi
import app.pulse.protocol.ComposerReceiptDto
import app.pulse.protocol.PulseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerJournalTest {
    @Test fun latePreflightRefusalCannotHoldNewerDraftOrEdit() = runBlocking {
        for (kind in listOf("draft", "edit")) {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
            val journal = ComposerJournal(db)
            val target = if (kind == "draft") "room" else "message"
            suspend fun stage(text: String) = journal.stage("me", kind, target, "room", null, text, "original")
            var requests = 0
            val applied = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                requests++
                if (requests == 1) {
                    // The user types while the old state request is in flight.
                    // Its eventual refusal owns only the old desired identity.
                    stage("newer text")
                    return@MockEngine respond("""{"error":"Old lookup refused"}""", HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/json"))
                }
                val flight = journal.entries("me").single().flight
                val write = request.method.value == "PATCH"
                val receipt = ComposerReceiptDto(composerVersion=1, userId="me", kind=kind, targetId=target,
                    conversationId="room", text=if (write) checkNotNull(flight).text else "original",
                    revision=if (write) "member:1" else "member:0",
                    editedAt=if (write && kind == "edit") "2026-10-28T00:00:00.001Z" else null,
                    clientId=if (write) checkNotNull(flight).clientId else null,
                    outcome=if (write) "applied" else null)
                respond(PulseJson.encodeToString(ComposerReceiptDto.serializer(), receipt), HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
            })
            try {
                val api = PulseApi(http, gateway={ "https://composer-journal.test" })
                stage("older text")
                val original = journal.entries("me").single().desiredId
                assertEquals(1, journal.flush("me", api, { true }))
                val newer = journal.entries("me").single()
                assertNotEquals(original, newer.desiredId)
                assertEquals("newer text", newer.text)
                assertNull(newer.blocked)
                assertNull(newer.flight)
                assertEquals(0, journal.flush("me", api, { true }) { _, receipt -> applied.add(receipt.text) })
                assertEquals(listOf("newer text"), applied)
                assertEquals(3, requests)
                assertTrue(journal.entries("me").isEmpty())
            } finally { http.close(); db.close() }
        }
    }
}

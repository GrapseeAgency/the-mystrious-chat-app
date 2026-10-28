package app.pulse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.data.local.PulseDatabase
import app.pulse.data.remote.PulseApi
import app.pulse.protocol.ScheduledCommandDto
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
class ScheduledJournalTest {
    @Test fun acceptedInstructionsRemainFencedUntilComposerAcknowledgementAndStoreNoDuplicateBody() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        val command = ScheduledCommandDto("me", "operation-000000001", "create", "room", "private-body", "2030-01-01T09:00:00Z")
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"scheduleVersion":1,"userId":"me","conversationId":"room","clientId":"operation-000000001","replayed":false,
                "item":{"id":"accepted","conversationId":"room","content":"private-body","scheduledAt":"2030-01-01T09:00:00.000Z"}}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val api = PulseApi(http, gateway = { "https://scheduled-journal.test" })
            val journal = ScheduledJournal(db)
            journal.stage(command)
            assertEquals(0, journal.flush("me", api) { true })
            val raw = checkNotNull(db.wave7Dao().get("composer-schedule-ack:v1|me|create|${command.clientId}"))
            assertFalse(raw.json.contains("private-body"))
            assertEquals("private-body", journal.result(command)?.content)
            assertTrue(runCatching { journal.stage(command.copy(text = "changed")) }.isFailure)
            val next = command.copy(clientId = "another-operation", scheduledAt = "2030-01-02T09:00:00Z")
            assertTrue(runCatching { journal.stage(next) }.isFailure)
            journal.stage(command)
            assertEquals(0, journal.flush("me", api) { true })
            assertEquals(1, requests)
            assertTrue(runCatching { journal.acknowledge("me", command.clientId, "wrong", "room") }.isFailure)
            journal.acknowledge("me", command.clientId, "accepted", "room")
            journal.stage(next)
            assertNotNull(db.wave7Dao().get("composer-schedule:v1|me|create|${next.clientId}"))
        } finally { http.close(); db.close() }
    }
}

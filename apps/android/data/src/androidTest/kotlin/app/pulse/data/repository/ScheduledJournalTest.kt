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
      for (acknowledgeBeforeCancel in listOf(false, true)) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        val command = ScheduledCommandDto("me", "operation-000000001", "create", "room", "private-body", "2030-01-01T09:00:00Z")
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            if (request.method.value == "DELETE") return@MockEngine respond(
                """{"scheduleVersion":1,"userId":"me","conversationId":"room","scheduledId":"accepted","ok":true,"cancelled":true}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
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
            if (acknowledgeBeforeCancel) journal.acknowledge("me", command.clientId, "accepted", "room")
            val cancel = ScheduledCommandDto("me", "accepted", "cancel")
            journal.stage(cancel)
            assertEquals(0, journal.flush("me", api) { true })
            assertTrue(journal.cancelled(cancel))
            assertTrue(runCatching { journal.stage(command) }.isFailure)
            assertTrue(runCatching { journal.result(command) }.isFailure)
            assertNull(db.wave7Dao().get("composer-schedule-head:v1|me|room"))
            journal.stage(next)
            // A late acknowledgement of the cancelled predecessor cannot
            // release the newly staged replacement's room fence.
            journal.acknowledge("me", command.clientId, "accepted", "room")
            assertEquals(next.clientId, db.wave7Dao().get("composer-schedule-head:v1|me|room")?.json)
            assertNotNull(db.wave7Dao().get("composer-schedule:v1|me|create|${next.clientId}"))
        } finally { http.close(); db.close() }
      }
    }

    @Test fun composerAndScheduleRetryAfterSurvivesReopenAndDoesNotChangeInstructions() = runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      for (kind in listOf("draft", "edit")) for (scheduleFirst in listOf(false, true)) for (status in listOf(429, 503)) {
        val name = "replay-admission-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
        val command = ScheduledCommandDto("me", "original-operation-001", "create", "room", "original schedule", "2030-01-01T09:00:00Z")
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"error":"Wait for admission"}""", HttpStatusCode.fromValue(status),
                headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("60")))
        })
        try {
            val api = PulseApi(http, gateway = { "https://replay-admission.test" })
            val schedules = ScheduledJournal(db); val composer = ComposerJournal(db)
            schedules.stage(command)
            composer.stage("me", kind, if (kind == "edit") "message" else "draft-room", "draft-room", null, "original draft", "")
            if (scheduleFirst) schedules.flush("me", api) { true } else composer.flush("me", api, { true })
            assertEquals(1, requests)
            assertNotNull(db.wave7Dao().get("outbox-retry:v1|me|global"))
            assertEquals(1, schedules.flush("me", api) { true })
            assertEquals(1, composer.flush("me", api, { true }))
            assertEquals(1, requests)
            db.close()
            db = Room.databaseBuilder(context, PulseDatabase::class.java, name).build()
            assertEquals(1, ScheduledJournal(db).flush("me", api) { true })
            assertEquals(1, ComposerJournal(db).flush("me", api, { true }))
            assertEquals("A new repository/process must not bypass Retry-After", 1, requests)
            assertEquals("original draft", ComposerJournal(db).entries("me").single().text)
            val saved = checkNotNull(db.wave7Dao().get("composer-schedule:v1|me|create|${command.clientId}"))
            assertTrue(saved.json.contains(command.scheduledAt))
            assertTrue(saved.json.contains(command.text))
            // Negative control: transport itself is not cached/throttled by this fixture.
            api.cancelScheduled("control", "me")
            assertEquals(2, requests)
        } finally { http.close(); db.close(); context.deleteDatabase(name) }
      }
    }

    @Test fun messageRoomHoldDoesNotBlockComposerButGlobalHoldIsOwnerScoped() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java).build()
        try {
            OutboxRetryJournal(db, app.pulse.domain.sync.MessageDispatchGate()).hold("me", 60, "room")
            val admission = ComposerRetryAdmission(db)
            assertTrue(admission.canRun("me"))
            admission.hold("me", 60)
            assertFalse(admission.canRun("me"))
            assertTrue(admission.canRun("other"))
            assertFalse(ComposerRetryAdmission(db).canRun("me"))
        } finally { db.close() }
    }

}

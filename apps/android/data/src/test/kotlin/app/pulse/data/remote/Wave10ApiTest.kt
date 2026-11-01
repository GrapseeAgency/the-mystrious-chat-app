package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Section 10 wire contracts (PulseApi): durable creation identities ride the
 * body for events, tasks, kanban, reminders and packets (items 1/4/6/11/13);
 * bounded history pages for events (2), tasks (5), boards (8) and whiteboards
 * (10) pin the hasMore=>nextCursor continuation contract.
 */
class Wave10ApiTest {

    private var requests = 0
    private var lastUrl = ""
    private var lastBody = ""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): PulseApi =
        PulseApi(
            HttpClient(MockEngine { request ->
                requests++
                lastUrl = request.url.toString()
                lastBody = when (val b = request.body) {
                    is io.ktor.http.content.TextContent -> b.text
                    is io.ktor.http.content.ByteArrayContent -> b.bytes().decodeToString()
                    else -> ""
                }
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }),
            bearerToken = { "synthetic-token" },
        )

    @Before
    fun reset() {
        requests = 0; lastUrl = ""; lastBody = ""
        PulseEndpoints.applyBase(null)
    }

    private val eventJson = """{"id":"e1","title":"Meet","createdAt":"2026-02-20T09:00:00.000Z",
        "startsAt":"2026-02-21T09:00:00.000Z","createdById":"u1","rsvps":[],"counts":{"going":0,"maybe":0,"no":0},"myStatus":null}"""

    private val taskJson = """{"id":"t1","ownerId":"u1","title":"Ship","status":"todo",
        "createdAt":"2026-02-20T09:00:00.000Z","updatedAt":"2026-02-20T09:00:00.000Z"}"""

    private val cardJson = """{"id":"c1","conversationId":"room","title":"Card","column":"todo","position":0,
        "assigneeId":null,"assigneeName":null,"createdById":"u1","createdByName":null,"sourceMessageId":null,
        "sourceRevoked":false,"sourceAvailable":null,"createdAt":"2026-02-20T09:00:00.000Z","updatedAt":"2026-02-20T09:00:00.000Z"}"""

    @Test fun `event creation carries the journaled clientId on the wire`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = eventJson).createEvent("room", "u1", "Meet", "2026-02-21T09:00:00.000Z", null, null, clientId = "event00000000000000001")
        assertTrue(r is PulseResult.Success)
        assertTrue("clientId rides the body", lastBody.contains("event00000000000000001"))
    }

    @Test fun `event history pages request the keyset window and decode the continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val page = """{"events":[$eventJson],"eventsRev":7,"hasMore":true,"nextCursor":"1771606800000.e1"}"""
        val r = api(body = page).eventsPage("room", "u1", 60, "1771606900000.e9")
        assertTrue(r is PulseResult.Success)
        val dto = (r as PulseResult.Success).value
        assertTrue(dto.hasMore)
        assertEquals(7, dto.eventsRev)
        assertEquals("1771606800000.e1", dto.nextCursor)
        assertTrue(lastUrl.contains("limit=60"))
        assertTrue(lastUrl.contains("cursor=1771606900000.e9"))
    }

    @Test fun `a continued event page without its cursor is a protocol failure`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val broken = """{"events":[$eventJson],"hasMore":true}"""
        assertTrue(api(body = broken).eventsPage("room", "u1", 60, null) is PulseResult.Failure)
    }

    @Test fun `task creation carries the journaled clientId and pages decode their cursor`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val r = api(body = taskJson).createHubTask("u1", "Ship", null, clientId = "task000000000000000001")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("task000000000000000001"))

        val page = """{"userId":"u1","tasks":[$taskJson],"hasMore":true,"nextCursor":"1771606800000.t1"}"""
        val p = api(body = page).hubTasksPage("u1", 100, null)
        assertTrue(p is PulseResult.Success)
        assertTrue("opening page has no cursor param", lastUrl.contains("limit=100") && !lastUrl.contains("cursor="))
        val broken = """{"userId":"u1","tasks":[],"hasMore":true}"""
        assertTrue(api(body = broken).hubTasksPage("u1", 100, null) is PulseResult.Failure)
    }

    @Test fun `kanban card creation carries the identity and board pages gate their receipt`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val receipt = """{"boardReceiptVersion":1,"userId":"u1","conversationId":"room","replayed":false,"card":$cardJson}"""
        val r = api(body = receipt).createKanbanCard("room", "u1", "Card", null, null, null, clientId = "kanban00000000000000001")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("kanban00000000000000001"))

        val page = """{"boardReceiptVersion":1,"userId":"u1","conversationId":"room","cards":[$cardJson],
            "boardRev":12,"hasMore":true,"nextCursor":"1771606800000.c1"}"""
        val p = api(body = page).kanbanBoardPage("room", "u1", 200, null)
        assertTrue(p is PulseResult.Success)
        assertEquals(12, (p as PulseResult.Success).value.boardRev)
        val broken = """{"boardReceiptVersion":1,"userId":"u1","conversationId":"room","cards":[],"hasMore":true}"""
        assertTrue(api(body = broken).kanbanBoardPage("room", "u1", 200, null) is PulseResult.Failure)
    }

    @Test fun `reminder creation carries the journaled clientId on the wire`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val reminder = """{"id":"r1","conversationId":"room","messageId":null,"note":"","remindAt":"2026-03-01T09:00:00.000Z",
            "firedAt":null,"createdAt":"2026-02-20T09:00:00.000Z","conversation":{"id":"room","name":"Room","isGroup":true},"snippet":null}"""
        val r = api(body = """{"item":$reminder,"replayed":false}""").createReminder("u1", "room", null, "n", "2026-03-01T09:00:00.000Z", clientId = "remnd00000000000000001")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("remnd00000000000000001"))
    }

    @Test fun `red packet creation carries the identity and decodes the replay receipt`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val body = """{"message":{"id":"m1","conversationId":"room","senderId":"u1","content":"go","kind":"redpacket",
            "createdAt":"2026-02-20T09:00:00.000Z","deletedAt":null,"payload":null,"anon":false,"viaAutomation":false},
            "packet":{"id":"p1","total":100,"count":5,"grabbed":0,"note":"go","expiresAt":"2026-02-21T09:00:00.000Z"},
            "replayed":true}"""
        val r = api(body = body).createRedPacket("u1", "room", 100, 5, "go", clientId = "pkt0000000000000000001")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("pkt0000000000000000001"))
        assertTrue("a replay is a confirmed publication of the SAME packet", (r as PulseResult.Success).value.replayed)
    }

    @Test fun `whiteboard pages request the keyset window and decode the continuation`() = runTest {
        PulseEndpoints.applyBase("https://prod.example")
        val stroke = """{"id":"s1","userId":"u1","color":"#000000","width":3.0,"points":[],"createdAt":"2026-02-20T09:00:00.000Z"}"""
        val page = """{"strokes":[$stroke],"complete":true,"hasMore":true,"nextCursor":"1771606800000.s1",
            "operationsVersion":1,"serverTime":1,"resetAt":null}"""
        val r = api(body = page).whiteboardPage("room", "u1", 500, null)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.contains("limit=500") && !lastUrl.contains("cursor="))
        val broken = """{"strokes":[],"complete":true,"hasMore":true,"operationsVersion":1}"""
        assertTrue(api(body = broken).whiteboardPage("room", "u1", 500, null) is PulseResult.Failure)
    }
}

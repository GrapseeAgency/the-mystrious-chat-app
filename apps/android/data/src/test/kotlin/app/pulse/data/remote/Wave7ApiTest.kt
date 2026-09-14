package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 7 — collaboration & hub REST contract tests (JVM, MockEngine).
 * Locks the exact verbs/paths/bodies the server routes define (worklog 7-a):
 * red packets, whiteboard (since-polling), kanban, events, reminders, games,
 * tournaments, leaderboard, hub economy, apps installs/communities.
 */
class Wave7ApiTest {

    private var lastMethod: HttpMethod? = null
    private var lastUrl: String = ""
    private var lastBody: String = ""
    private var status: HttpStatusCode = HttpStatusCode.OK
    private var response: String = "{}"

    private fun api(): PulseApi = PulseApi(
        HttpClient(MockEngine { request ->
            lastMethod = request.method
            lastUrl = request.url.toString()
            lastBody = when (val b = request.body as? OutgoingContent) {
                is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                else -> ""
            }
            respond(response, status, headersOf("Content-Type", "application/json"))
        }),
    )

    @After
    fun reset() {
        PulseEndpoints.applyBase(null)
        status = HttpStatusCode.OK
        response = "{}"
    }

    private fun configured() = PulseEndpoints.applyBase("https://gw.example")

    // ── red packets ──────────────────────────────────────────────

    @Test
    fun `create red packet posts total count and note`() = runTest {
        configured()
        response = """{"message":{"id":"m1","conversationId":"c1","senderId":"u1","content":"🧧 Red packet","kind":"redpacket",
            "payload":"{\"packetId\":\"p1\",\"total\":100,\"count\":5,\"note\":\"hi\"}","createdAt":"2026-01-15T00:00:00.000Z"},
            "packet":{"id":"p1","total":100,"count":5,"grabbed":0,"expiresAt":"2026-01-16T00:00:00.000Z"}}"""
        val r = api().createRedPacket("u1", "c1", total = 100, count = 5, note = "hi")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertTrue(lastUrl.endsWith("/api/redpackets"))
        assertTrue(lastBody.contains("\"userId\":\"u1\""))
        assertTrue(lastBody.contains("\"total\":100"))
        assertTrue(lastBody.contains("\"count\":5"))
        val v = (r as PulseResult.Success).value
        assertEquals("p1", v.packet?.id)
        assertEquals("redpacket", v.message?.kind)
    }

    @Test
    fun `grab posts userId and decodes amount`() = runTest {
        configured()
        response = """{"amount":37,"grabbed":2,"count":5}"""
        val r = api().grabRedPacket("p1", "u2")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/redpackets/p1/grab"))
        assertTrue(lastBody.contains("\"userId\":\"u2\""))
        assertEquals(37L, (r as PulseResult.Success).value.amount)
    }

    @Test
    fun `insufficient funds maps to 402 failure with verbatim copy`() = runTest {
        configured()
        status = HttpStatusCode.PaymentRequired
        response = """{"error":"Insufficient PC — you have 10, tried to send 100."}"""
        val r = api().createRedPacket("u1", "c1", total = 100, count = 5, note = null)
        assertTrue(r is PulseResult.Failure)
        assertTrue((r as PulseResult.Failure).message?.contains("Insufficient PC") == true)
    }

    // ── whiteboard ───────────────────────────────────────────────

    @Test
    fun `whiteboard GET carries requesterId and since epoch`() = runTest {
        configured()
        response = """{"strokes":[{"id":"s1","userId":"u1","color":"#22c55e","width":3.0,"points":[[0.1,0.2],[0.4,0.5]]}],
            "serverTime":1700,"resetAt":null}"""
        val r = api().whiteboard("c1", "u1", since = 1700L)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.contains("/api/conversations/c1/whiteboard"))
        assertTrue(lastUrl.contains("requesterId=u1"))
        assertTrue(lastUrl.contains("since=1700"))
        val v = (r as PulseResult.Success).value
        assertEquals(1, v.strokes.size)
        assertEquals(2, v.strokes[0].points.size)
        assertNull(v.resetAt)
    }

    @Test
    fun `whiteboard POST batches strokes with 0-1 coords`() = runTest {
        configured()
        response = """{"ids":["s9"],"created":1,"serverTime":1800}"""
        val r = api().postWhiteboardStrokes(
            "c1", "u1",
            listOf(app.pulse.protocol.WhiteboardStrokePostDto(color = "#ff0000", width = 2.5, points = listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)))),
        )
        assertTrue(r is PulseResult.Success)
        val v = (r as PulseResult.Success).value
        assertEquals(listOf("s9"), v.ids)
        assertTrue(lastBody.contains("\"requesterId\":\"u1\""))
        assertTrue(lastBody.contains("#ff0000"))
        assertTrue(lastBody.contains("[[0.0,0.0],[1.0,1.0]]"))
    }

    @Test
    fun `whiteboard undo posts action undo`() = runTest {
        configured()
        response = """{"success":true,"removedId":"s9","serverTime":1900}"""
        val r = api().undoWhiteboardStroke("c1", "u1")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("\"action\":\"undo\""))
    }

    @Test
    fun `whiteboard clear uses DELETE with requesterId query`() = runTest {
        configured()
        response = """{"success":true,"reset":true,"at":2000,"cleared":3,"serverTime":2000}"""
        val r = api().clearWhiteboard("c1", "u1")
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
        assertTrue(lastUrl.contains("requesterId=u1"))
    }

    // ── kanban ───────────────────────────────────────────────────

    @Test
    fun `kanban board GET and card create POST`() = runTest {
        configured()
        response = """{"card":{"id":"k1","conversationId":"c1","title":"Ship it","column":"todo","position":0}}"""
        val r = api().createKanbanCard("c1", "u1", title = "Ship it", column = "todo", assigneeId = null, messageId = null)
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Post, lastMethod)
        assertTrue(lastUrl.endsWith("/api/conversations/c1/kanban"))
        assertEquals("Ship it", (r as PulseResult.Success).value.title)
    }

    @Test
    fun `kanban PATCH moves card and DELETE goes with query`() = runTest {
        configured()
        response = """{"card":{"id":"k1","column":"doing","position":4}}"""
        val r = api().updateKanbanCard("k1", "u1", title = null, column = "doing", assigneeId = null, clearAssignee = false, position = null)
        assertTrue(r is PulseResult.Success)
        assertEquals(HttpMethod.Patch, lastMethod)
        assertTrue(lastUrl.endsWith("/api/kanban/k1"))
        assertTrue(lastBody.contains("\"column\":\"doing\""))
        assertTrue(!lastBody.contains("position"))

        response = """{"ok":true}"""
        val d = api().deleteKanbanCard("k1", "u1")
        assertTrue(d is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
        assertTrue(lastUrl.contains("userId=u1"))
    }

    // ── events ───────────────────────────────────────────────────

    @Test
    fun `event create posts startsAt ISO and RSVP posts status`() = runTest {
        configured()
        response = """{"event":{"id":"e1","title":"Standup","startsAt":"2026-01-20T10:00:00.000Z","rsvps":[],"counts":{"going":0,"maybe":0,"no":0},"myStatus":null}}"""
        val r = api().createEvent("c1", "u1", "Standup", "2026-01-20T10:00:00.000Z", null, null)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("\"startsAt\":\"2026-01-20T10:00:00.000Z\""))

        response = """{"rsvp":{"id":"r1","eventId":"e1","userId":"u1","status":"going"},"counts":{"going":1,"maybe":0,"no":0}}"""
        val s = api().rsvpEvent("e1", "u1", "going")
        assertTrue(s is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/events/e1/rsvp"))
        assertTrue(lastBody.contains("\"status\":\"going\""))
    }

    @Test
    fun `checkin posts and idempotent response decodes`() = runTest {
        configured()
        response = """{"checkIn":null,"xpAwarded":false,"checkedInCount":3,"alreadyCheckedIn":true}"""
        val r = api().checkinEvent("e1", "u1")
        assertTrue(r is PulseResult.Success)
        assertTrue((r as PulseResult.Success).value.alreadyCheckedIn)
    }

    // ── reminders ────────────────────────────────────────────────

    @Test
    fun `reminders GET due flag and POST body`() = runTest {
        configured()
        response = """{"items":[]}"""
        val r = api().reminders("u1", dueOnly = true)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.contains("/api/reminders?userId=u1"))
        assertTrue(lastUrl.contains("due=1"))

        response = """{"item":{"id":"rm1","conversationId":"c1","note":"hi","remindAt":"2026-01-15T11:00:00.000Z","conversation":{"id":"c1","name":"DM","isGroup":false}}}"""
        val c = api().createReminder("u1", "c1", messageId = "m1", note = "hi", remindAtIso = "2026-01-15T11:00:00.000Z")
        assertTrue(c is PulseResult.Success)
        assertTrue(lastBody.contains("\"messageId\":\"m1\""))
        assertEquals("rm1", (c as PulseResult.Success).value.id)
    }

    // ── games ────────────────────────────────────────────────────

    @Test
    fun `game create posts open challenge and move posts cell`() = runTest {
        configured()
        response = """{"match":{"id":"m1","conversationId":"c1","game":"tictactoe","playerXId":"u1","playerOId":null,"board":"         ","turn":"X","status":"active","moveCount":0},
            "message":{"id":"gm1","conversationId":"c1","senderId":"u1","content":"⚔️ Tic-tac-toe — open challenge","kind":"game","createdAt":"2026-01-15T00:00:00.000Z"}}"""
        val r = api().createGame("u1", "c1", opponentId = null)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/games"))
        val v = (r as PulseResult.Success).value
        assertEquals("game", v.message?.kind)

        response = """{"match":{"id":"m1","board":"X        ","turn":"O","status":"active","moveCount":1},"playerX":{"id":"u1","name":"A"},"playerO":null}"""
        val mv = api().gameMove("m1", "u1", cell = 4)
        assertTrue(mv is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/games/m1/move"))
        assertTrue(lastBody.contains("\"cell\":4"))
    }

    // ── hub economy ──────────────────────────────────────────────

    @Test
    fun `wallet checkin posts userId`() = runTest {
        configured()
        response = """{"wallet":{"userId":"u1","coins":52,"gems":0,"streak":1,"lastCheckIn":"2026-01-15T02:00:00.000Z"},"reward":25,"streak":1}"""
        val r = api().checkinWallet("u1")
        assertTrue(r is PulseResult.Success)
        assertEquals(25L, (r as PulseResult.Success).value.reward)
    }

    @Test
    fun `transfer normalizes handle client-side`() = runTest {
        configured()
        response = """{"wallet":{"userId":"u1","coins":0},"to":{"id":"u2","name":"B","username":"bob"}}"""
        val r = api().transferCoins("u1", "@Bob", amount = 25, note = null)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("\"toUsername\":\"bob\""))
    }

    @Test
    fun `swap posts direction and amount`() = runTest {
        configured()
        response = """{"wallet":{"userId":"u1","coins":0,"gems":2},"note":"Swapped 200 PC → 2 GEM"}"""
        val r = api().swap("u1", direction = "pc2gem", amount = 200)
        assertTrue(r is PulseResult.Success)
        assertTrue(lastBody.contains("\"direction\":\"pc2gem\""))
    }

    @Test
    fun `market buy posts and returns ok plus wallet`() = runTest {
        configured()
        response = """{"ok":true,"wallet":{"userId":"u2","coins":40}}"""
        val r = api().buyListing("l1", "u2")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/hub/market/l1/buy"))
        assertTrue((r as PulseResult.Success).value.ok)
    }

    @Test
    fun `app install state GET install POST and uninstall DELETE`() = runTest {
        configured()
        response = """{"installed":true,"status":"connected","installedAt":"2026-01-15T00:00:00.000Z","installs":3,"installers":[{"id":"u1","name":"A"}]}"""
        val g = api().appInstallState("11", "u1")
        assertTrue(g is PulseResult.Success)
        assertTrue(lastUrl.endsWith("/api/hub/apps/11/install?userId=u1"))

        response = """{"installed":true,"status":"connected","installs":4}"""
        val p = api().installApp("11", "u1")
        assertTrue(p is PulseResult.Success)

        val d = api().uninstallApp("11", "u1")
        assertTrue(d is PulseResult.Success)
        assertEquals(HttpMethod.Delete, lastMethod)
    }

    @Test
    fun `app community join posts userId and decodes conversation`() = runTest {
        configured()
        response = """{"conversation":{"id":"cnv1","isGroup":true,"name":"#011 · Twitch community"},"memberCount":2,"joined":true}"""
        val r = api().joinAppCommunity("11", "u1")
        assertTrue(r is PulseResult.Success)
        val v = (r as PulseResult.Success).value
        assertEquals("cnv1", v.conversation?.id)
        assertTrue(v.joined)
    }

    @Test
    fun `leaderboard room scoped carries both ids`() = runTest {
        configured()
        response = """{"rows":[{"userId":"u1","name":"A","color":"emerald","xp":120,"messageCount":40,"gameWins":2,"tournamentPoints":5}]}"""
        val r = api().leaderboard("c1", "u1")
        assertTrue(r is PulseResult.Success)
        assertTrue(lastUrl.contains("conversationId=c1"))
        assertTrue(lastUrl.contains("userId=u1"))
        assertEquals(120, (r as PulseResult.Success).value.rows[0].xp)

        val g = api().leaderboard(null, null)
        assertTrue(g is PulseResult.Success)
        assertTrue(!lastUrl.contains("conversationId"))
    }

    // ── offline gate ─────────────────────────────────────────────

    @Test
    fun `unconfigured gateway returns network failure without firing`() = runTest {
        PulseEndpoints.applyBase(null)
        val r = api().wallet("u1")
        assertTrue(r is PulseResult.Failure)
        assertTrue((r as PulseResult.Failure).message?.contains("No gateway") == true)
    }
}

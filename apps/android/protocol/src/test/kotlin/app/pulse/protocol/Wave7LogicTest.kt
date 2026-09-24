package app.pulse.protocol

import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave 7 — pure logic parity tests (Collaboration & Hub).
 * parseRelativeReminder mirrors src/components/chat/reminders-sheet.tsx:132
 * exactly (tokens, tomorrow/tonight hour shifts, "log in in 30m" case).
 */
class Wave7LogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** 2026-01-15T10:00:00+06:00 → epoch ms */
    private fun at(hour: Int, minute: Int = 0): Long =
        java.time.LocalDateTime.of(2026, 1, 15, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val now: Long = at(10)

    // ── parseRelativeReminder ────────────────────────────────────

    @Test
    fun `in 30m parses relative minutes`() {
        val r = PulseWave7Logic.parseRelativeReminder("Stand up in 30m", now, zone)!!
        assertEquals("Stand up", r.note)
        assertEquals(now + 30 * 60_000L, r.remindAtEpochMs)
    }

    @Test
    fun `bare trailing token parses and strips leftover in`() {
        val r = PulseWave7Logic.parseRelativeReminder("log in in 30m", now, zone)!!
        assertEquals("log in", r.note)
        assertEquals(now + 30 * 60_000L, r.remindAtEpochMs)
    }

    @Test
    fun `hours days weeks tokens parse`() {
        assertEquals(now + 2 * 3_600_000L, PulseWave7Logic.parseRelativeReminder("x 2h", now, zone)!!.remindAtEpochMs)
        assertEquals(now + 86_400_000L, PulseWave7Logic.parseRelativeReminder("x 1day", now, zone)!!.remindAtEpochMs)
        assertEquals(now + 14 * 86_400_000L, PulseWave7Logic.parseRelativeReminder("x 2w", now, zone)!!.remindAtEpochMs)
    }

    @Test
    fun `tomorrow shifts to 9am local next day`() {
        val r = PulseWave7Logic.parseRelativeReminder("Call mom tomorrow", now, zone)!!
        assertEquals("Call mom", r.note)
        val expected = java.time.LocalDateTime.of(2026, 1, 16, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, r.remindAtEpochMs)
    }

    @Test
    fun `tonight shifts to 8pm same day and next day when past`() {
        val expected = java.time.LocalDateTime.of(2026, 1, 15, 20, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, PulseWave7Logic.parseRelativeReminder("Party tonight", now, zone)!!.remindAtEpochMs)
        // 23:00 → tonight already passed → tomorrow 20:00
        val late = at(23)
        val expected2 = java.time.LocalDateTime.of(2026, 1, 16, 20, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected2, PulseWave7Logic.parseRelativeReminder("Party tonight", late, zone)!!.remindAtEpochMs)
    }

    @Test
    fun `next week adds 7 days`() {
        assertEquals(now + 7 * 86_400_000L, PulseWave7Logic.parseRelativeReminder("x next week", now, zone)!!.remindAtEpochMs)
    }

    @Test
    fun `empty note or no time token returns null`() {
        assertNull(PulseWave7Logic.parseRelativeReminder("", now, zone))
        assertNull(PulseWave7Logic.parseRelativeReminder("no time here", now, zone))
        assertNull(PulseWave7Logic.parseRelativeReminder("in 30m", now, zone)) // note collapses to "in" pre-strip? web returns null (note empty)
        assertNull(PulseWave7Logic.parseRelativeReminder("buy milk 5parsecs", now, zone))
    }

    @Test
    fun `case insensitive tokens`() {
        val r = PulseWave7Logic.parseRelativeReminder("Ping TOMORROW", now, zone)!!
        assertEquals("Ping", r.note)
    }

    // ── red packet / game / tournament payload decode ────────────

    @Test
    fun `red packet payload decodes and tolerates junk`() {
        val p = PulseWave7Logic.redPacketPayload("""{"packetId":"p1","total":100,"count":5,"note":"新年快乐"}""")!!
        assertEquals("p1", p.packetId)
        assertEquals(100, p.total)
        assertEquals(5, p.count)
        assertEquals("新年快乐", p.note)
        assertNull(PulseWave7Logic.redPacketPayload(null))
        assertNull(PulseWave7Logic.redPacketPayload("not json"))
    }

    @Test
    fun `game payload decodes`() {
        val p = PulseWave7Logic.gamePayload("""{"matchId":"m1","game":"tictactoe"}""")!!
        assertEquals("m1", p.matchId)
        assertNull(PulseWave7Logic.gamePayload(null))
    }

    @Test
    fun `tournament payload decodes`() {
        val p = PulseWave7Logic.tournamentPayload("""{"tournamentId":"t1","name":"Friday","game":"tictactoe"}""")!!
        assertEquals("t1", p.tournamentId)
    }

    // ── tictactoe board helpers ──────────────────────────────────

    @Test
    fun `detectWinLine finds rows columns diagonals`() {
        assertEquals('X' to listOf(0, 1, 2), PulseWave7Logic.detectWinLine("XXX      "))
        assertEquals('O' to listOf(0, 4, 8), PulseWave7Logic.detectWinLine("O   O   O"))
        assertEquals('O' to listOf(0, 3, 6), PulseWave7Logic.detectWinLine("O  O  O  "))
        assertEquals('X' to listOf(0, 4, 8), PulseWave7Logic.detectWinLine("X   X   X"))
        assertEquals('O' to listOf(2, 4, 6), PulseWave7Logic.detectWinLine("  O O O  "))
        assertNull(PulseWave7Logic.detectWinLine("XOX      "))
        assertNull(PulseWave7Logic.detectWinLine("bad"))
    }

    @Test
    fun `cellAt reads padded board`() {
        val board = "X O      " // 9 chars
        assertEquals('X', PulseWave7Logic.cellAt(board, 0))
        assertEquals('O', PulseWave7Logic.cellAt(board, 2))
        assertEquals(' ', PulseWave7Logic.cellAt(board, 8))
        assertEquals(' ', PulseWave7Logic.cellAt(board, 9))
        assertEquals(' ', PulseWave7Logic.cellAt("short", 0))
    }

    @Test
    fun `sideOf and isMyTurn gate viewer actions`() {
        val match = GameMatchDto(id = "m", playerXId = "u1", playerOId = null, turn = "X", status = "active")
        assertEquals('X', PulseWave7Logic.sideOf(match, "u1"))
        assertNull(PulseWave7Logic.sideOf(match, "u2"))
        assertTrue(PulseWave7Logic.isMyTurn(match, "u1"))
        assertFalse(PulseWave7Logic.isMyTurn(match, "u2")) // spectating
        assertFalse(PulseWave7Logic.isMyTurn(match.copy(status = "x_won"), "u1"))
        assertFalse(PulseWave7Logic.isMyTurn(match.copy(turn = "O"), "u1"))
        assertTrue(PulseWave7Logic.isMyTurn(match.copy(turn = "O", playerOId = "u2"), "u2"))
    }

    // ── check-in window + wallet reward rules ────────────────────

    @Test
    fun `checkin window is -15min to +2h`() {
        val start = at(12)
        assertTrue(PulseWave7Logic.checkinWindowOpen(start, start - 15 * 60_000))
        assertFalse(PulseWave7Logic.checkinWindowOpen(start, start - 16 * 60_000))
        assertTrue(PulseWave7Logic.checkinWindowOpen(start, start + 2 * 3_600_000))
        assertFalse(PulseWave7Logic.checkinWindowOpen(start, start + 2 * 3_600_000 + 1))
    }

    @Test
    fun `checkin reward is 25 base plus 2 per streak day capped at 20`() {
        assertEquals(25L, PulseWave7Logic.checkinReward(1))
        assertEquals(27L, PulseWave7Logic.checkinReward(2))
        assertEquals(45L, PulseWave7Logic.checkinReward(11))
        assertEquals(45L, PulseWave7Logic.checkinReward(30))
    }

    // ── wire DTO tolerant decode (populated + absent) ─────────────

    @Test
    fun `wire page DTOs decode populated and empty`() {
        val page = PulseJson.decodeFromString(
            WalletPageDto.serializer(),
            """{"wallet":{"userId":"u1","coins":500,"gems":2,"streak":3,"checkedInToday":false},
                "ledger":[{"id":"l1","kind":"checkin","asset":"PC","amount":27,"note":"Daily check-in · 3-day streak (+4 bonus)"}]}""",
        )
        assertEquals(500, page.wallet.coins)
        assertEquals(1, page.ledger.size)
        val empty = PulseJson.decodeFromString(WalletPageDto.serializer(), "{}")
        assertEquals(0, empty.wallet.coins)
        assertTrue(empty.ledger.isEmpty())
    }

    @Test
    fun `match detail decodes with nullable playerO and winLine`() {
        val d = PulseJson.decodeFromString(
            GameDetailDto.serializer(),
            """{"match":{"id":"m1","board":"XOXOX    ","turn":"O","status":"active","moveCount":5,"winLine":null},
                "playerX":{"id":"u1","name":"A","color":"emerald"},
                "playerO":{"id":"u2","name":"B","color":"rose"}}""",
        )
        assertEquals("m1", d.match.id)
        assertEquals(9, d.match.board.length)
        assertNull(d.match.winLine)
        assertEquals("u2", d.playerO?.id)
    }

    @Test
    fun `tournament standings decode with entries`() {
        val t = PulseJson.decodeFromString(
            TournamentSummaryDto.serializer(),
            """{"id":"t1","name":"S1","status":"running","entries":[{"userId":"u1","points":3,"wins":1,"draws":0,"losses":0}]}""",
        )
        assertEquals(1, t.entries.size)
        assertEquals(3, t.entries[0].points)
    }

    // ── R5-B — send envelope + streak nudge (web chat-room.tsx:1736-1751) ──

    @Test
    fun `send envelope decodes message streak and xpAwarded`() {
        val body = """
        {"message":{"id":"m1","conversationId":"c1","senderId":"u1","content":"gm",
         "createdAt":"2026-01-15T04:00:00.000Z"},
         "streak":{"count":3,"best":7,"continued":true},"xpAwarded":15}
        """.trimIndent()
        val env = decodeMessageSend(body)
        assertEquals("m1", env.message?.id)
        assertNotNull(env.streak)
        assertEquals(3, env.streak?.count)
        assertEquals(7, env.streak?.best)
        assertEquals(true, env.streak?.continued)
        assertEquals(15, env.xpAwarded)
    }

    @Test
    fun `send envelope without streak decodes null and bare row still unwraps`() {
        val envelope = decodeMessageSend(
            """{"message":{"id":"m2","conversationId":"c1","senderId":"u1","content":"hi",
                "createdAt":"2026-01-15T04:00:00.000Z"},"xpAwarded":15}""",
        )
        assertNull(envelope.streak)
        assertEquals("m2", envelope.message?.id)
        // legacy/alternate gateways that reply with a bare row still decode
        val bare = decodeMessageSend(
            """{"id":"m3","conversationId":"c1","senderId":"u1","content":"yo",
                "createdAt":"2026-01-15T04:00:00.000Z"}""",
        )
        assertEquals("m3", bare.message?.id)
        assertNull(bare.streak)
    }

    @Test
    fun `streak nudge fires only when grown second-or-later day`() {
        // web fire-condition verbatim: continued && count >= 2
        assertEquals(null, MessageStreakDto(count = 1, best = 1, continued = false).nudgeText()) // restart
        assertEquals(null, MessageStreakDto(count = 1, best = 4, continued = true).nudgeText()) // first day
        assertEquals(null, MessageStreakDto(count = 5, best = 9, continued = false).nudgeText()) // restart w/ history
        assertEquals(null, (null as MessageStreakDto?).nudgeText()) // same-day resend / no bump
        assertEquals("2-day streak — keep it alive", MessageStreakDto(count = 2, best = 2, continued = true).nudgeText())
        assertEquals("7-day streak", MessageStreakDto(count = 7, best = 9, continued = true).nudgeText())
    }

    // ── R5-B — hub connector relative stamp (web app-detail-sheet.tsx:97-108) ──

    @Test
    fun `relative stamp mirrors web formatRelative branches`() {
        val now = java.time.Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
        assertEquals("just now", PulseWave7Logic.relativeStamp("2026-01-15T11:59:40Z", now))
        assertEquals("3m ago", PulseWave7Logic.relativeStamp("2026-01-15T11:57:00Z", now))
        assertEquals("3h ago", PulseWave7Logic.relativeStamp("2026-01-15T09:00:00Z", now))
        assertEquals("2d ago", PulseWave7Logic.relativeStamp("2026-01-13T12:00:00Z", now))
        assertTrue(PulseWave7Logic.relativeStamp("2025-06-01T00:00:00Z", now).isNotEmpty()) // ≥7d → date
        assertEquals("", PulseWave7Logic.relativeStamp(null, now))
        assertEquals("", PulseWave7Logic.relativeStamp("not-a-date", now))
    }

    // ── R7 item 4 — reminder rows carry the anchored message id (the Jump
    // action + the room route ?jump= arg are dead without it on the wire) ──

    @Test
    fun `reminder item decodes messageId present and absent`() {
        val withId = PulseJson.decodeFromString(
            RemindersPageDto.serializer(),
            """{"items":[{"id":"r1","conversationId":"c1","messageId":"m9","note":"check this",
                "remindAt":"2026-01-20T09:00:00.000Z","firedAt":null,
                "conversation":{"id":"c1","name":"Lounge","isGroup":true}}]}""",
        )
        assertEquals("m9", withId.items.single().messageId)
        // legacy relays that never sent the field keep decoding (null = no Jump row).
        val withoutId = PulseJson.decodeFromString(
            RemindersPageDto.serializer(),
            """{"items":[{"id":"r2","conversationId":"c1","note":"plain","remindAt":"2026-01-20T09:00:00.000Z"}]}""",
        )
        assertNull(withoutId.items.single().messageId)
    }
}

package app.pulse.protocol

import kotlinx.serialization.json.Json

/**
 * Wave 7 — pure, UI-free logic kernels (Collaboration & Hub).
 * Ported 1:1 from the web so native behaviour matches bit-for-bit:
 *   parseRelativeReminder   ← src/components/chat/reminders-sheet.tsx:132
 *   durationToMs/endOfDay   ← reminders-sheet.tsx:118-130
 *   tictactoe win display   ← src/app/api/games/[id]/move/route.ts:29-38 (display side only)
 */
object PulseWave7Logic {

    val json: Json = PulseJson

    private const val DURATION_TOKEN = "(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)"

    /** Minutes→ms etc. — unit is matched on its first char exactly like the web. */
    internal fun durationToMs(value: Long, unit: String): Long? = when (unit.firstOrNull()) {
        'm' -> value * 60_000
        'h' -> value * 3_600_000
        'd' -> value * 86_400_000
        'w' -> value * 7 * 86_400_000
        else -> null
    }

    data class ParsedReminder(val note: String, val remindAtEpochMs: Long)

    /**
     * Parse the tail of a "/remind <note> <time>" draft into {note, remindAt}.
     * Accepted trailing times: "in 30m|2h|1d|2w", bare "30m|2h", "tomorrow"
     * (09:00 local), "tonight" (20:00 local), "next week" (+7d). Case-insensitive.
     * Returns null when no sane future time token is found or the note is empty.
     */
    fun parseRelativeReminder(arg: String, nowEpochMs: Long, zone: java.time.ZoneId): ParsedReminder? {
        val input = arg.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty()) return null

        fun endOfDayShift(hour: Int): Long {
            val base = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zone)
            var target = base.toLocalDate().atTime(hour, 0)
            var result = target.atZone(zone).toInstant().toEpochMilli()
            if (result <= nowEpochMs) {
                target = target.plusDays(1)
                result = target.atZone(zone).toInstant().toEpochMilli()
            }
            return result
        }

        data class Pattern(val regex: Regex, val stripTrailingIn: Boolean = false, val resolve: (MatchResult) -> Long?)

        val patterns = listOf(
            Pattern(Regex("\\s+in\\s+(\\d+)\\s*$DURATION_TOKEN$", RegexOption.IGNORE_CASE)) { m ->
                durationToMs(m.groupValues[1].toLongOrNull() ?: return@Pattern null, m.groupValues[2])?.let { nowEpochMs + it }
            },
            Pattern(Regex("\\s+(\\d+)\\s*$DURATION_TOKEN$", RegexOption.IGNORE_CASE), stripTrailingIn = true) { m ->
                durationToMs(m.groupValues[1].toLongOrNull() ?: return@Pattern null, m.groupValues[2])?.let { nowEpochMs + it }
            },
            Pattern(Regex("\\s+next\\s+week$", RegexOption.IGNORE_CASE)) { nowEpochMs + 7 * 86_400_000 },
            Pattern(Regex("\\s+tomorrow$", RegexOption.IGNORE_CASE)) { endOfDayShift(9) },
            Pattern(Regex("\\s+tonight$", RegexOption.IGNORE_CASE)) { endOfDayShift(20) },
        )
        for (p in patterns) {
            val m = p.regex.find(input) ?: continue
            val remindAt = p.resolve(m) ?: continue
            var note = input.substring(0, m.range.first).trim()
            if (p.stripTrailingIn) note = note.replace(Regex("\\s+in$", RegexOption.IGNORE_CASE), "").trim()
            if (note.isEmpty() || note.equals("in", ignoreCase = true)) return null
            return ParsedReminder(note, remindAt)
        }
        return null
    }

    /** Tolerant decode of a red-packet message payload (raw JSON string). */
    fun redPacketPayload(payload: String?): RedPacketPayloadDto? {
        if (payload.isNullOrBlank()) return null
        return runCatching { PulseJson.decodeFromString(RedPacketPayloadDto.serializer(), payload) }.getOrNull()
    }

    /** Tolerant decode of a game message payload (raw JSON string). */
    fun gamePayload(payload: String?): GamePayloadDto? {
        if (payload.isNullOrBlank()) return null
        return runCatching { PulseJson.decodeFromString(GamePayloadDto.serializer(), payload) }.getOrNull()
    }

    /** Tolerant decode of a tournament message payload (raw JSON string). */
    fun tournamentPayload(payload: String?): TournamentPayloadDto? {
        if (payload.isNullOrBlank()) return null
        return runCatching { PulseJson.decodeFromString(TournamentPayloadDto.serializer(), payload) }.getOrNull()
    }

    /** Cell getter over the 9-char board string; out-of-range/short board → ' '. */
    fun cellAt(board: String, index: Int): Char =
        if (index in 0..8 && board.length == 9) board[index] else ' '

    /** The 8 classic tic-tac-toe triples (server move route:29-38). */
    val WIN_LINES: List<List<Int>> = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6),
    )

    /** Mirror of the server's win scan — used to strike the winning line while polling. */
    fun detectWinLine(board: String): Pair<Char, List<Int>>? {
        if (board.length != 9) return null
        for (line in WIN_LINES) {
            val (a, b, c) = line
            val v = board[a]
            if (v != ' ' && v == board[b] && v == board[c]) return v to line
        }
        return null
    }

    /** True when the viewer can still act on this match (a seated player, active, their turn). */
    fun isMyTurn(match: GameMatchDto, viewerId: String): Boolean {
        if (match.status != "active") return false
        val side = sideOf(match, viewerId) ?: return false
        return match.turn == side.toString()
    }

    /** 'X' | 'O' for the viewer, or null when spectating. */
    fun sideOf(match: GameMatchDto, viewerId: String): Char? = when {
        match.playerXId == viewerId -> 'X'
        match.playerOId != null && match.playerOId == viewerId -> 'O'
        else -> null
    }

    /** Kanban column order used everywhere (server: todo → doing → done). */
    val KANBAN_COLUMNS = listOf("todo", "doing", "done")

    /** Hub task status values (server-validated). */
    val TASK_STATUSES = listOf("todo", "doing", "done")

    /** RSVP statuses in display order (web events sheet:145-147). */
    val RSVP_STATUSES = listOf("going", "maybe", "no")

    /** Check-in window: startsAt − 15 min … + 2 h (server checkin route:36-37). */
    fun checkinWindowOpen(startsAtEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs >= startsAtEpochMs - 15 * 60_000 && nowEpochMs <= startsAtEpochMs + 2 * 3_600_000

    /** Streak bonus display rule: +2/day capped +20 on the 25 PC base (server checkin:44-51). */
    fun checkinReward(streakAfter: Int): Long = 25 + minOf((streakAfter - 1).coerceAtLeast(0) * 2L, 20L)
}

package app.pulse.feature.chat

import app.pulse.core.fx.PulseFx

/**
 * F-MS-22 / F-MS-23 — slash-command + message-effect logic, ported from the
 * web ground truth (no transport, no UI):
 *  · PULSE_SLASH_COMMANDS — src/components/chat/slash-palette.tsx (verbatim)
 *  · applySlash / rollDice — src/components/chat/chat-room.tsx:296-502
 *  · fuzzyMatch — src/components/chat/slash-palette.tsx (subsequence match)
 *  · effectOfPayload / burstKindOf — chat-room.tsx:1504-1519 + EFFECT_PARTICLES
 */
object PulseSlash {

    data class SlashCommand(val cmd: String, val args: String, val help: String)

    /** The web palette's exact 25 commands (slash-palette.tsx:79-105). */
    val COMMANDS: List<SlashCommand> = listOf(
        SlashCommand("/me", "<action>", "Send an italic action line"),
        SlashCommand("/shrug", "[text]", "Append ¯\\_(ツ)_/¯"),
        SlashCommand("/tableflip", "[text]", "Append (╯°□°）╯︵ ┻━┻"),
        SlashCommand("/unflip", "[text]", "Prefix ┬─┬ ノ( ゜-゜ノ"),
        SlashCommand("/roll", "[AdM]", "Roll dice, e.g. /roll 2d6"),
        SlashCommand("/poll", "", "Open the live-poll builder"),
        SlashCommand("/schedule", "", "Schedule this message for later"),
        SlashCommand("/remind", "<message> in <time>", "Set a reminder on your next message"),
        SlashCommand("/recap", "", "AI summary of the recent chat"),
        SlashCommand("/sticker", "", "Open the sticker packs"),
        SlashCommand("/location", "", "Share a live map pin"),
        SlashCommand("/whiteboard", "", "Open the shared whiteboard"),
        SlashCommand("/redpacket", "", "Send a red packet (coins)"),
        SlashCommand("/game", "", "Start tic-tac-toe in this chat"),
        SlashCommand("/kanban", "", "Open the group board"),
        SlashCommand("/events", "", "Group events with RSVP"),
        SlashCommand("/topic", "<name>", "Create a topic and file here"),
        SlashCommand("/stage", "", "Open the live stage room"),
        SlashCommand("/space", "", "Open the spatial space"),
        SlashCommand("/tournament", "", "Start a group tournament"),
        SlashCommand("/effects confetti", "[text]", "Send with a confetti blast"),
        SlashCommand("/effects lasers", "[text]", "Send with sweeping laser beams"),
        SlashCommand("/effects echo", "[text]", "Send with expanding echo rings"),
        SlashCommand("/effects sparkles", "[text]", "Send with twinkling sparkles"),
        SlashCommand("/help", "", "Show every command"),
    )

    /**
     * Lightweight fuzzy match: every character of `needle` appears in
     * `haystack` in order (case-insensitive, whitespace squeezed) with a
     * bounded gap budget — '/ef co' → /effects confetti ✓ (web parity).
     */
    fun fuzzyMatch(haystack: String, needle: String): Boolean {
        val h = haystack.lowercase()
        val n = needle.lowercase().replace(Regex("\\s+"), "")
        if (n.isEmpty()) return true
        var i = 0
        var gap = 0
        for (char in n) {
            val idx = h.indexOf(char, i)
            if (idx < 0) return false
            gap += idx - i
            i = idx + 1
        }
        return gap <= n.length * 6
    }

    /** Palette candidates for a draft (web SlashPalette filter, top 6). */
    fun paletteMatches(draft: String, limit: Int = 6): List<SlashCommand> {
        val needle = if (draft.trim().startsWith("/")) draft.trim() else ""
        if (needle.isEmpty()) return emptyList()
        return COMMANDS.filter { fuzzyMatch("${it.cmd} ${it.args} ${it.help}", needle) }.take(limit)
    }

    /** applySlash outcomes the room handles (web chat-room.tsx:405-424 union). */
    sealed interface Outcome {
        data class Send(val content: String) : Outcome
        data class Effect(val effect: String, val content: String) : Outcome
        data class Error(val message: String) : Outcome
        /** poll | schedule | sticker | location | whiteboard | redpacket | kanban | events | game | stage | space | tournament */
        data class Sheet(val sheet: String) : Outcome
        data class Topic(val name: String) : Outcome
        data class Remind(val rest: String) : Outcome
        /** R2-A item 5 — /recap runs the AI recap request (web runPaletteCommand). */
        data object Recap : Outcome
        object Help : Outcome
    }

    val EFFECT_NAMES: Set<String> = setOf("confetti", "lasers", "echo", "sparkles")

    /** R21-a bot commands pass through as plain messages (server answers). */
    private val BOT_COMMANDS: Set<String> = setOf("math", "flip", "8ball", "rps", "dice", "time", "wallet")

    /**
     * Port of the web applySlash — leading-slash parsing into real content
     * or UI actions. Non-slash input returns `.send` verbatim; a leading
     * token that is not a \w+ word also falls through to `.send`.
     */
    fun applySlash(rawInput: String): Outcome {
        val input = rawInput.trim()
        if (!input.startsWith("/")) return Outcome.Send(input)
        val body = input.drop(1)
        val spaceIndex = body.indexOf(' ')
        val word = (if (spaceIndex < 0) body else body.take(spaceIndex)).lowercase()
        val rest = if (spaceIndex < 0) "" else body.substring(spaceIndex + 1)
        val arg = rest.trim()
        if (word.isEmpty() || !word.all { it.isLetterOrDigit() || it == '_' }) return Outcome.Send(input)
        return when (word) {
            "me" -> if (arg.isEmpty()) Outcome.Error("Usage: /me waves hello") else Outcome.Send("_" + arg.take(1998) + "_")
            "shrug" -> Outcome.Send((if (arg.isEmpty()) "" else "$arg ") + "¯\\_(ツ)_/¯")
            "tableflip" -> Outcome.Send((if (arg.isEmpty()) "" else "$arg ") + "(╯°□°）╯︵ ┻━┻")
            "unflip" -> Outcome.Send("┬─┬ ノ( ゜-゜ノ" + (if (arg.isEmpty()) "" else " $arg"))
            "roll" -> {
                if (arg.isEmpty()) {
                    Outcome.Send("Rolled **1d6**: *" + rollDice("1d6")?.total.let { it ?: "?" } + "*")
                } else {
                    val roll = rollDice(arg)
                        ?: return Outcome.Error("Usage: /roll AdM — e.g. /roll 2d6")
                    Outcome.Send(
                        "Rolled **" + arg.lowercase() + "**: " + roll.rolls.joinToString(" + ") +
                            " = *" + roll.total + "*",
                    )
                }
            }
            "poll" -> Outcome.Sheet("poll")
            "schedule" -> Outcome.Sheet("schedule")
            "remind" -> Outcome.Remind(rest)
            // R2-A item 5 — /recap executes the AI recap (web chat-room.tsx:3260-3264).
            "recap" -> Outcome.Recap
            "sticker" -> Outcome.Sheet("sticker")
            "location" -> Outcome.Sheet("location")
            "whiteboard", "redpacket", "kanban", "events", "game", "stage", "space", "tournament" ->
                Outcome.Sheet(word)
            "topic" -> if (arg.isEmpty()) Outcome.Error("Usage: /topic Design") else Outcome.Topic(arg)
            "effects" -> {
                val parts = arg.split(" ", limit = 2)
                val effectWord = parts.firstOrNull()?.lowercase().orEmpty()
                if (effectWord !in EFFECT_NAMES) {
                    Outcome.Error("Usage: /effects confetti|lasers|echo|sparkles [text]")
                } else {
                    Outcome.Effect(effectWord, parts.getOrNull(1)?.trim().orEmpty())
                }
            }
            "help" -> Outcome.Help
            else -> {
                if (word in BOT_COMMANDS) Outcome.Send(input)
                else Outcome.Error("Unknown command \"/$word\" — try /help")
            }
        }
    }

    /** "AdM" dice roll — web rollDice parity (1..12 dice clamp, 2..1000 sides). */
    data class Roll(val rolls: List<Int>, val total: Int)

    fun rollDice(spec: String): Roll? {
        val trimmed = spec.trim().lowercase()
        val match = Regex("^(\\d{1,2})d(\\d{1,3})$").find(trimmed) ?: return null
        val count = (match.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 12)
        val sides = (match.groupValues[2].toIntOrNull() ?: 0).coerceIn(2, 1000)
        val rolls = (1..count).map { kotlin.random.Random.nextInt(1, sides + 1) }
        return Roll(rolls, rolls.sum())
    }
}

/**
 * F-MS-23 / D29 — message-effect helpers. The wire stores the effect on
 * kind:"text" rows as payload { effect: "confetti" | "lasers" | "echo" |
 * "sparkles" } (messages route shape, web parseMessagePayload(m.payload).effect
 * at chat-room.tsx:1518).
 */
object PulseEffects {

    /** payload JSON → whitelisted effect name (null when absent/garbled). */
    fun effectOfPayload(payload: String?): String? =
        app.pulse.protocol.PulseWave7Logic.effectPayload(payload)?.effect

    /**
     * Effect → app-wide particle burst kind (web EFFECT_PARTICLES parity):
     * confetti→confetti · sparkles→stars · lasers/echo→burst.
     */
    fun burstKindOf(effect: String?): PulseFx.BurstKind? = when (effect) {
        "confetti" -> PulseFx.BurstKind.CONFETTI
        "sparkles" -> PulseFx.BurstKind.STARS
        "lasers", "echo" -> PulseFx.BurstKind.BURST
        else -> null
    }

    /** One-shot: decode a payload blob straight to the burst (null = no FX). */
    fun burstOfPayload(payload: String?): PulseFx.BurstKind? = burstKindOf(effectOfPayload(payload))
}

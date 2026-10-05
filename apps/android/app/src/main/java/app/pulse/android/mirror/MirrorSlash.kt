package app.pulse.android.mirror

// Pulse - slash-command palette, native port of slash-palette.tsx (web R23).
// Anchored above the composer whenever the draft starts with '/'. Fuzzy
// filtering, tap to select, dismiss on empty matches. The legacy plain-text
// parser runs on the SAME definitions - the palette is a fast-path on top
// (web header comment L1-5).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One slash row - web SlashCommandDef L46 (tone hex replaces the tailwind class). */
data class MirrorSlashCommand(
    val cmd: String,
    val args: String,
    val help: String,
    val glyph: String,
    val tone: Color,
)

private fun tone(hex: Long) = Color(hex)

/** web PULSE_SLASH_COMMANDS L93-119 - all 25 rows, verbatim copy. */
val MIRROR_SLASH_COMMANDS: List<MirrorSlashCommand> = listOf(
    MirrorSlashCommand("/me", "<action>", "Send an italic action line", "LUserRound", tone(0xFFD97706)),
    MirrorSlashCommand("/shrug", "[text]", "Append ¯\\_(ツ)_/¯", "LPenLine", tone(0xFFF97316)),
    MirrorSlashCommand("/tableflip", "[text]", "Append (╯°□°）╯︵ ┻━┻", "LArmchair", tone(0xFFF43F5E)),
    MirrorSlashCommand("/unflip", "[text]", "Prefix ┬┬ ノ( ゜-゜ノ", "LRotateCcw", tone(0xFFF59E0B)),
    MirrorSlashCommand("/roll", "[AdM]", "Roll dice, e.g. /roll 2d6", "LDices", tone(0xFF8B5CF6)),
    MirrorSlashCommand("/poll", "", "Open the live-poll builder", "LVote", tone(0xFF8B5CF6)),
    MirrorSlashCommand("/schedule", "", "Schedule this message for later", "LCalendarClock", tone(0xFFF59E0B)),
    MirrorSlashCommand("/remind", "<message> in <time>", "Set a reminder on your next message", "LBell", tone(0xFFD97706)),
    MirrorSlashCommand("/recap", "", "AI summary of the recent chat", "LSparkles", tone(0xFF8B5CF6)),
    MirrorSlashCommand("/sticker", "", "Open the sticker packs", "LSticker", tone(0xFFD97706)),
    MirrorSlashCommand("/location", "", "Share a live map pin", "LLocation", tone(0xFFF97316)),
    MirrorSlashCommand("/whiteboard", "", "Open the shared whiteboard", "LPresentation", tone(0xFFD97706)),
    MirrorSlashCommand("/redpacket", "", "Send a red packet (coins)", "LGift", tone(0xFFF43F5E)),
    MirrorSlashCommand("/game", "", "Start tic-tac-toe in this chat", "LGamepad2", tone(0xFF8B5CF6)),
    MirrorSlashCommand("/kanban", "", "Open the group board", "LSquareKanban", tone(0xFFF97316)),
    MirrorSlashCommand("/events", "", "Group events with RSVP", "LCalendarDays", tone(0xFFF59E0B)),
    MirrorSlashCommand("/topic", "<name>", "Create a topic and file here", "LMessagesSquare", tone(0xFFD97706)),
    MirrorSlashCommand("/stage", "", "Open the live stage room", "LPodcast", tone(0xFFF97316)),
    MirrorSlashCommand("/space", "", "Open the spatial space", "LMap", tone(0xFFF59E0B)),
    MirrorSlashCommand("/tournament", "", "Start a group tournament", "LTrophy", tone(0xFFF43F5E)),
    MirrorSlashCommand("/effects confetti", "[text]", "Send with a confetti blast", "LPartyPopper", tone(0xFFF43F5E)),
    MirrorSlashCommand("/effects lasers", "[text]", "Send with sweeping laser beams", "LZap", tone(0xFFF59E0B)),
    MirrorSlashCommand("/effects echo", "[text]", "Send with expanding echo rings", "LRadio", tone(0xFFD97706)),
    MirrorSlashCommand("/effects sparkles", "[text]", "Send with twinkling sparkles", "LSparkles", tone(0xFF8B5CF6)),
    MirrorSlashCommand("/help", "", "Show every command", "LCircleHelp", tone(0xFFA1A1AA)),
)

/**
 * Lightweight fuzzy match - every character of needle appears in haystack
 * in order, case-insensitive, gap-tolerant (web fuzzyMatch L125: gap <=
 * n*6 keeps ranking sane). '/ef co' matches /effects confetti.
 */
fun mirrorSlashFuzzyMatch(haystack: String, needle: String): Boolean {
    val h = haystack.lowercase()
    val n = needle.lowercase().replace(Regex("\\s+"), "")
    if (n.isEmpty()) return true
    var i = 0
    var gap = 0
    for (ch in n) {
        val idx = h.indexOf(ch, i)
        if (idx < 0) return false
        gap += idx - i
        i = idx + 1
    }
    return gap <= n.length * 6
}

/** Filtered matches for the current query (web SlashPalette useMemo L160). */
fun mirrorSlashMatches(query: String): List<MirrorSlashCommand> {
    if (!query.startsWith("/") || query.isEmpty()) return emptyList()
    return MIRROR_SLASH_COMMANDS.filter { mirrorSlashFuzzyMatch("${it.cmd} ${it.args} ${it.help}", query) }
}

// R24-b incognito alias - client mirror of the server's deterministic alias
// (chat-room.tsx L357): identical FNV-1a + word lists to the messages route,
// so the OPTIMISTIC bubble shows the exact alias the server stores.

private val ANON_ADJECTIVES = listOf("Swift", "Quiet", "Neon", "Ember", "Frost", "Lucky", "Cosmic", "Silent")
private val ANON_ANIMALS = listOf("Falcon", "Otter", "Panda", "Wolf", "Comet", "Tiger", "Raven", "Fox")

/** web anonStableHash L368 - unsigned FNV-1a. */
private fun anonStableHash(value: String): Long {
    var hash = 0x811c9dc5L
    for (ch in value) {
        hash = hash xor ch.code.toLong()
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
    }
    return hash and 0xFFFFFFFFL
}

/** web anonAliasPreview L378 - "Ember the Falcon", deterministic per (user, room). */
fun mirrorAnonAliasPreview(userId: String, conversationId: String): String {
    val hash = anonStableHash("$userId:$conversationId")
    val adjective = ANON_ADJECTIVES[(hash % ANON_ADJECTIVES.size).toInt()]
    val animal = ANON_ANIMALS[((hash / ANON_ADJECTIVES.size) % ANON_ANIMALS.size).toInt()]
    return "$adjective the $animal"
}

/**
 * The palette surface (web SlashPalette L220): glass dropdown anchored above
 * the composer, scroll cap max-h-64, active first row highlight, footer hint.
 */
@Composable
internal fun MirrorSlashPalette(
    query: String,
    onPick: (String) -> Unit,
) {
    val matches = mirrorSlashMatches(query)
    if (matches.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF21C1610))
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp)),
    ) {
        Column(
            Modifier
                .heightIn(max = 256.dp)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            for ((index, command) in matches.withIndex()) {
                val active = index == 0 // web derives the highlight; touch UI = row 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) MirrorArt.ChipActive else Color.Transparent)
                        .clickable { onPick(command.cmd) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    // glyph chip: size-8 rounded-xl tinted
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MirrorArt.White7),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon(command.glyph, tint = command.tone, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(
                                command.cmd,
                                color = MirrorArt.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (command.args.isNotEmpty()) {
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    command.args,
                                    color = MirrorArt.Faint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            command.help,
                            color = MirrorArt.Dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // footer hint strip (web L292)
        Text(
            "tap to run - the draft dispatches the same parser",
            color = MirrorArt.Faint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .background(MirrorArt.Chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

package app.pulse.feature.chat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.LocationPayload
import app.pulse.domain.model.Message
import app.pulse.domain.model.QuickPhrase
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave7Logic
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import org.json.JSONArray
import org.json.JSONObject

/**
 * R1-W2A — messaging-flow surfaces (web parity, native sheets). The Android
 * sibling of iOS MessagingSurfaces.swift:
 *  · ReactionPickerSheet — F-MS-08/D27 the web's EXACT 24-emoji picker grid
 *  · WhoReactedSheet     — F-MS-08/D27 per-member reaction list + toggle
 *  · StickerPickerSheet  — F-MS-24/D10 the web's 5 packs × 10 stickers
 *  · StickerBubble       — F-MS-24 large-emoji sticker render (plain chrome)
 *  · SlashPalette        — F-MS-22/D43 '/'-triggered command palette
 *  · QuickPhrasesRail    — F-MS-29 composer-adjacent quick-phrase chips
 */
internal val REACTION_GRID_CHOICES = listOf(
    "😀", "😂", "🥹", "😍", "😎", "🤔", "😴", "🥳",
    "👍", "🙏", "👏", "🔥", "❤️", "💜", "✨", "🎉",
    "🚀", "🌈", "☀️", "🌙", "☕", "🍕", "🎂", "⚽",
)

/**
 * F-MS-08/D27 — the web's exact 24-emoji extended reaction grid
 * (EMOJI_PICKER_CHOICES, pulse-utils.ts:146-150, 8-column grid).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionPickerSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 26.dp)) {
            Text(
                "React",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(REACTION_GRID_CHOICES) { emoji ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "React with $emoji" },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(emoji) },
                        ) {
                            Text(emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * F-MS-08/D27 — who-reacted drawer: per-member list for ONE emoji group +
 * the same toggle action (web reactionInfo drawer parity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhoReactedSheet(
    message: Message,
    conversation: Conversation?,
    emoji: String,
    viewerId: String?,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
) {
    val userIds = message.reactions.filter { it.emoji == emoji }.map { it.userId }.distinct()
    val iReacted = viewerId != null && viewerId in userIds
    val memberOf = { id: String -> conversation?.members?.firstOrNull { it.id == id } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(emoji, fontSize = 20.sp)
                Text(
                    "${userIds.size} ${if (userIds.size == 1) "reaction" else "reactions"}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                if (userIds.isEmpty()) {
                    item {
                        Text(
                            "No reactions yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(userIds, key = { it }) { userId ->
                    val member = memberOf(userId)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PulseAvatar(
                            name = member?.name ?: "Unknown",
                            colorHex = member?.color,
                            size = 34.dp,
                        )
                        Text(
                            member?.name ?: "Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (userId == viewerId) {
                            Text(
                                "(you)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                (if (iReacted) "Remove your reaction " else "React ") + emoji,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PulsePalette.Emerald)
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

// ── F-MS-24 — sticker packs (web sticker-picker.tsx:30-61 verbatim) ────────

internal data class StickerPack(
    val name: String,
    val badge: String,
    val items: List<String>,
)

internal val STICKER_PACKS: List<StickerPack> = listOf(
    StickerPack("Pulse", "⚡️", listOf("⚡️", "🔥", "💥", "🎉", "✨", "🌟", "💫", "🚀", "🎯", "🏆")),
    StickerPack("Faces", "😄", listOf("😂", "😍", "😎", "🤯", "😭", "😡", "🥳", "😴", "🤔", "🫠")),
    StickerPack("Reactions", "👍", listOf("👍", "👎", "🙏", "👏", "💪", "🤝", "😅", "🫡", "🤌", "🤗")),
    StickerPack("Love", "❤️", listOf("❤️", "🧡", "💛", "💚", "💜", "🖤", "💖", "💘", "💞", "🫶")),
    StickerPack("Critters", "🐾", listOf("🐶", "🐱", "🐼", "🦊", "🐸", "🐵", "🦄", "🐙", "🦋", "🐢")),
)

/** Parse a sticker payload blob — null when absent/garbled (web parseSticker). */
internal fun stickerOf(message: Message): Pair<String, String>? {
    val payload = PulseWave7Logic.stickerPayload(message.payload) ?: return null
    return payload.emoji to payload.pack
}

/**
 * Local recents for the sticker picker — SharedPreferences mirror of the web
 * localStorage "pulse.sticker-recents.v1" (max 12). Sent data stays real chat
 * data; recents are a local nicety only.
 */
object StickerRecents {
    private const val KEY = "pulse.sticker-recents.v1"
    private const val MAX = 12
    private const val FILE = "pulse_sticker_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<Pair<String, String>> = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length())
            .map { array.getJSONObject(it) }
            .mapNotNull { obj ->
                val emoji = obj.optString("emoji")
                val pack = obj.optString("pack", "Pulse")
                if (emoji.isEmpty()) null else emoji to pack
            }
            .take(MAX)
    }.getOrDefault(emptyList())

    fun remember(context: Context, emoji: String, pack: String) {
        runCatching {
            val next = listOf(emoji to pack) + load(context).filterNot { it.first == emoji && it.second == pack }
            val array = JSONArray()
            next.take(MAX).forEach { (e, p) ->
                array.put(JSONObject().put("emoji", e).put("pack", p))
            }
            prefs(context).edit().putString(KEY, array.toString()).apply()
        }
    }
}

/** F-MS-24 — the sticker packs sheet: recents strip + pack tabs + 3-col grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickerSheet(
    onDismiss: () -> Unit,
    onPick: (emoji: String, pack: String) -> Unit,
) {
    val context = LocalContext.current
    var packIndex by remember { mutableIntStateOf(0) }
    val recents = remember { StickerRecents.load(context) }
    val active = STICKER_PACKS[packIndex.coerceIn(0, STICKER_PACKS.lastIndex)]

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 18.dp)) {
            if (recents.isNotEmpty()) {
                Text(
                    "RECENT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recents.forEach { (emoji, pack) ->
                        StickerTile(emoji = emoji, pack = pack, size = 44.dp, fontSize = 20.sp) {
                            StickerRecents.remember(context, emoji, pack)
                            onPick(emoji, pack)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                STICKER_PACKS.forEachIndexed { index, pack ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (index == packIndex) {
                            PulsePalette.Emerald.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable { packIndex = index }
                            .semantics { contentDescription = "${pack.name} pack" },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(pack.badge, fontSize = 17.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
            ) {
                items(active.items) { emoji ->
                    StickerTile(emoji = emoji, pack = active.name, size = 84.dp, fontSize = 40.sp) {
                        StickerRecents.remember(context, emoji, active.name)
                        onPick(emoji, active.name)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${active.name} pack · tap to send — stays open for combos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StickerTile(
    emoji: String,
    pack: String,
    size: Dp,
    fontSize: TextUnit,
    onPick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape((size.value * 0.28f).dp),
        color = PulsePalette.Emerald.copy(alpha = 0.10f),
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = "Send $emoji sticker from $pack" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick),
        ) {
            Text(emoji, fontSize = fontSize)
        }
    }
}

/** F-MS-24 — sticker rows render as a LARGE emoji with no bubble chrome. */
@Composable
internal fun StickerBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val sticker = stickerOf(message)
    Box(modifier = modifier.padding(2.dp)) {
        Text(
            sticker?.first?.takeIf { it.isNotEmpty() } ?: "✨",
            fontSize = 64.sp,
            modifier = Modifier.semantics {
                contentDescription = "Sticker" + (sticker?.second?.let { " from $it" } ?: "")
            },
        )
    }
}

// ── F-MS-22/D43 — '/'-triggered command palette (web slash-palette port) ──

/**
 * Palette rows above the composer whenever the draft starts with '/'. Same
 * 25-command set + fuzzy subsequence filter as the web slash-palette.tsx,
 * capped at 6 candidates.
 */
@Composable
internal fun SlashPalette(
    draft: String,
    onPick: (PulseSlash.SlashCommand) -> Unit,
) {
    val matches = remember(draft) { PulseSlash.paletteMatches(draft) }
    if (matches.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            matches.forEach { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        command.cmd,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PulsePalette.Emerald,
                    )
                    if (command.args.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            command.args,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        command.help,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

// ── F-MS-18 — /schedule arms a delayed send (server window 30 s…30 d) ──────

/**
 * Compact delayed-send armer for the /slash palette — the three web schedule
 * presets (iOS ScheduleSheet parity). The server enforces the 30 s minimum
 * and 30-day horizon; presets land safely inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    draft: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSchedule: (iso: String) -> Unit,
) {
    val presets = remember {
        listOf(
            "In 1 hour" to 3_600_000L,
            "Tomorrow 9:00" to -1L,
            "In 1 week" to 7 * 86_400_000L,
        )
    }
    var selected by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Schedule message", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                draft.ifBlank { "(empty message)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            presets.forEachIndexed { index, (label, _) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selected = index }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (selected == index) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val iso = when (val offsetMs = presets[selected].second) {
                        -1L -> {
                            // Tomorrow 09:00 local (web remindPresets parity).
                            val zone = java.time.ZoneId.systemDefault()
                            val target = java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone)
                            java.time.OffsetDateTime.ofInstant(target.toInstant(), zone)
                                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        }
                        else -> java.time.OffsetDateTime.now()
                            .plusNanos(offsetMs * 1_000_000)
                            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    }
                    onSchedule(iso)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Schedule") }
        }
    }
}

// ── /help — the full command list (web help sheet parity) ─────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlashHelpDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Slash commands") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(PulseSlash.COMMANDS) { command ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                command.cmd,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = PulsePalette.Emerald,
                            )
                            if (command.args.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    command.args,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            command.help,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Composer-adjacent quick-phrase chips (spec F-MS-29). Tap → the phrase text
 * is inserted into the composer draft; the trailing "+" opens the manage
 * sheet. Rows come from GET /api/users/{id}/phrases (viewer-owned).
 */
@Composable
internal fun QuickPhrasesRail(
    phrases: List<QuickPhrase>,
    onUse: (String) -> Unit,
    onManage: () -> Unit,
) {
    if (phrases.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phrases.forEach { phrase ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onUse(phrase.text) },
            ) {
                Text(
                    phrase.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = PulsePalette.Emerald.copy(alpha = 0.14f),
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClick = onManage)
                .semantics { contentDescription = "Manage quick phrases" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = PulsePalette.Emerald,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** F-MS-29 — manage sheet: add (≤120 chars, server caps 12 rows) + delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhrasesSheet(
    phrases: List<QuickPhrase>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SentimentSatisfied,
                    contentDescription = null,
                    tint = PulsePalette.Emerald,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Quick phrases",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${phrases.size}/12",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "One-tap lines for the composer — synced to your account.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BasicField(
                        value = text,
                        onValueChange = { if (it.length <= 120) text = it },
                        placeholder = "Add a phrase…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (text.isBlank() || busy) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        PulsePalette.Emerald
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = text.isNotBlank() && !busy) {
                            onAdd(text.trim())
                            text = ""
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                if (phrases.isEmpty()) {
                    item {
                        Text(
                            "No quick phrases yet — add your first one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(phrases, key = { it.id }) { phrase ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            phrase.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onDelete(phrase.id) },
                            enabled = !busy,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete phrase",
                                tint = PulsePalette.Rose,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── R1-W2F — F-MD-07 location share (web location-share.tsx / iOS
//    MessagingSurfaces.swift:485 LocationShareSheet siblings) ──────────────

/** Web location-share.tsx DEFAULT_LABEL. */
internal const val LOCATION_DEFAULT_LABEL = "Current location"

/**
 * Safe-parse a `kind:"location"` payload blob {lat,lng,label} — web
 * parseLocationPayload parity: garbled/missing coordinates fail the decode →
 * null (the row degrades to its body text); a blank label gets the default.
 */
internal fun locationOf(message: Message): LocationPayload? {
    val raw = message.payload ?: return null
    val parsed = runCatching {
        PulseJson.decodeFromString(LocationPayload.serializer(), raw)
    }.getOrNull() ?: return null
    return if (parsed.label.isBlank()) parsed.copy(label = LOCATION_DEFAULT_LABEL) else parsed
}

/** Web coordText — "12.3457° N, 67.8901° W" (locale-safe, tabular digits). */
internal fun coordinateText(lat: Double, lng: Double): String =
    String.format(java.util.Locale.US, "%.4f° %s, %.4f° %s",
        Math.abs(lat), if (lat >= 0) "N" else "S",
        Math.abs(lng), if (lng >= 0) "E" else "W",
    )

/**
 * Location pin row — the tappable render for `kind:"location"` messages.
 * Tap → ACTION_VIEW on a geo:lat,lng URI (the system maps picker; a device
 * without any maps app is caught, never crashes). A garbled payload
 * degrades honestly to the row's body text (web falls back too).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocationPinBubble(
    message: Message,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pin = locationOf(message)
    if (pin == null) {
        Text(
            message.body.ifBlank { LOCATION_DEFAULT_LABEL },
            color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }
    val background = if (mine) {
        Brush.verticalGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
    }
    val accent = if (mine) Color.White else PulsePalette.Emerald
    val sub = if (mine) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = modifier) {
        Row(
            Modifier
                .background(background, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "geo:${pin.lat},${pin.lng}?q=${pin.lat},${pin.lng}(" +
                                        Uri.encode(pin.label.ifBlank { LOCATION_DEFAULT_LABEL }) + ")",
                                ),
                            ),
                        )
                    }
                })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (mine) Color.White.copy(alpha = 0.2f) else PulsePalette.Emerald.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = "Location pin", tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pin.label,
                    color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    coordinateText(pin.lat, pin.lng),
                    color = sub,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = "Open in maps",
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Location confirm sheet (web LocationShareSheet flow): one-shot fix →
 * coords + label → REAL kind:'location' message. The sheet owns NO
 * transport — onConfirm hands the confirmed pin to the room, which POSTs it.
 * Permission denial is an inline explainer + Settings hand-off (D30/D31
 * house pattern); the runtime gate itself lives at the screen layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationShareSheet(
    fix: LocationFix,
    denied: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: (lat: Double, lng: Double, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    val ready = fix.lat != null && fix.lng != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = PulsePalette.Emerald,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Share location", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            when {
                // Permission denied — honest explainer + Settings hand-off.
                denied -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Filled.LocationOff,
                        contentDescription = null,
                        tint = PulsePalette.Rose,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Location access is off — enable it in Settings to share a pin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                        TextButton(onClick = onRetry) {
                            Text("Retry", color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                // No provider / fix refused — retry re-runs the one-shot read.
                fix.failed && !ready -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Filled.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Couldn't get a fix — check that location is on, then retry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
                    }
                }
                ready -> {
                    if (fix.locating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = PulsePalette.Emerald)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "Refreshing the fix…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            coordinateText(fix.lat ?: 0.0, fix.lng ?: 0.0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulsePalette.Emerald,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    BasicField(
                        value = label,
                        onValueChange = { if (it.length <= 80) label = it },
                        placeholder = LOCATION_DEFAULT_LABEL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PulsePalette.Emerald,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onConfirm(
                                    fix.lat ?: return@clickable,
                                    fix.lng ?: return@clickable,
                                    label.trim().take(80).ifBlank { LOCATION_DEFAULT_LABEL },
                                )
                            },
                    ) {
                        Text(
                            "Send location",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sends a map pin to this chat — your coordinates ride the message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = PulsePalette.Emerald)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Finding your location…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

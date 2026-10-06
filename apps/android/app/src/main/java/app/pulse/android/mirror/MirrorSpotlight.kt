package app.pulse.android.mirror

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R75 - the Spotlight search palette, ported from the web's
 * src/components/chat/spotlight.tsx (the shell-global search the web opens
 * with Cmd/Ctrl+K or the shell overflow "Search" action). Sections are the
 * web's EXACT five, all on real data:
 *   Recents  - the last 5 queries, persisted device-locally (web localStorage)
 *   Actions  - New chat / Check in to Hub (real wallet checkin) / Switch theme
 *   Chats    - the viewer's live conversations
 *   People   - the real user directory (GET /api/users via repository.users())
 *   Messages - server-side search (repository.searchMessages, >= 2 chars, 250ms debounce)
 * People rows open a real 1:1 DM (createDm) exactly like the web's onOpenDm.
 */

private const val SPOT_RECENTS_NAME = "pulse.spotlight.recents"
private const val SPOT_RECENTS_KEY = "v1"
private const val SPOT_RECENTS_MAX = 5

private fun spotReadRecents(context: Context): List<String> = runCatching {
    val raw = context.getSharedPreferences(SPOT_RECENTS_NAME, Context.MODE_PRIVATE)
        .getString(SPOT_RECENTS_KEY, null) ?: return@runCatching emptyList()
    val parsed = JSONArray(raw)
    val out = mutableListOf<String>()
    for (i in 0 until parsed.length()) {
        val t = parsed.optString(i).trim()
        if (t.isNotEmpty()) out.add(t)
    }
    out.take(SPOT_RECENTS_MAX)
}.getOrDefault(emptyList())

private fun spotPushRecent(context: Context, query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    val next = listOf(q) + spotReadRecents(context).filter { it.lowercase() != q.lowercase() }
    runCatching {
        val arr = JSONArray()
        for (t in next.take(SPOT_RECENTS_MAX)) arr.put(t)
        context.getSharedPreferences(SPOT_RECENTS_NAME, Context.MODE_PRIVATE)
            .edit().putString(SPOT_RECENTS_KEY, arr.toString()).apply()
    }
}

private fun spotClearRecents(context: Context) {
    runCatching {
        context.getSharedPreferences(SPOT_RECENTS_NAME, Context.MODE_PRIVATE)
            .edit().remove(SPOT_RECENTS_KEY).apply()
    }
}

/** Web Highlight (spotlight.tsx:127): the matched slice turns amber semibold. */
private fun spotHighlight(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val idx = text.lowercase().indexOf(q.lowercase())
    if (idx < 0) return AnnotatedString(text)
    val end = (idx + q.length).coerceAtMost(text.length)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(color = SubPageInk.Amber400, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(idx, end))
        }
        append(text.substring(end))
    }
}

/** web formatListStamp for the Messages rows (pulse-utils). */
private fun spotStamp(iso: String): String = runCatching {
    val at = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val now = java.time.LocalDate.now()
    val pattern = if (at.toLocalDate() == now) {
        DateTimeFormatter.ofPattern("h:mma")
    } else {
        DateTimeFormatter.ofPattern("MMM d")
    }
    pattern.format(at)
}.getOrDefault("")

/** Action row model (web ActionRow, spotlight.tsx:102-111). */
private data class SpotAction(
    val key: String,
    val label: String,
    val hint: String,
    val glyph: String,
    val keepOpen: Boolean,
    val run: () -> Unit,
)

/** Chat row model (web ChatRow, spotlight.tsx:81-91). */
private data class SpotChat(
    val conv: Conversation,
    val title: String,
    val subtitle: String?,
    val unread: Int,
    val isGroup: Boolean,
)

/** Row entrance: 25ms stagger + swift-out rise (web spotlight.tsx:700-708). */
@Composable
private fun SpotStagger(index: Int, key: String, content: @Composable () -> Unit) {
    val anim = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) {
        delay((index * 25L).coerceAtMost(500L))
        anim.animateTo(
            1f,
            androidx.compose.animation.core.tween(
                320,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            ),
        )
    }
    Box(
        Modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 8.dp.toPx()
        },
    ) { content() }
}

/** Section header (web spotlight.tsx:498-500): 10.5px bold uppercase tracking. */
@Composable
private fun SpotSection(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            label.uppercase(),
            color = SubPageInk.Zinc500,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        content()
    }
}

/** 36dp glyph tile shared by recent/message/action rows (web spotlight.tsx:634/669/681). */
@Composable
private fun SpotGlyphTile(glyph: String, tint: Color, tile: Color) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(glyph, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Unread badge (web spotlight.tsx:597-600): amber pill, 9px bold white. */
@Composable
private fun SpotUnreadBadge(unread: Int) {
    Box(
        Modifier
            .heightIn(min = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(SubPageInk.Amber500)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (unread > 99) "99+" else unread.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Row model (web SpotlightRow union, spotlight.tsx:79-118). */
private sealed interface SpotRow
private data class RRecent(val text: String) : SpotRow
private data class RAction(val action: SpotAction) : SpotRow
private data class RChat(val chat: SpotChat) : SpotRow
private data class RPerson(val user: User) : SpotRow
private data class RMessage(val hit: MessageHit) : SpotRow

/** Rotating degree 0 -> 360 (the web's animate-spin loader). */
@Composable
private fun rememberInfiniteRotation(): Float {
    val t = rememberInfiniteTransition(label = "spotSpin")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spotSpinAngle",
    )
    return v
}

@Composable
internal fun MirrorSpotlightOverlay(
    conversations: List<Conversation>,
    viewerId: String,
    repository: PulseRepository,
    onOpenConversation: (String) -> Unit,
    onOpenDm: (String) -> Unit,
    onNewChat: () -> Unit,
    onCheckInToHub: () -> Unit,
    darkResolved: Boolean,
    onToggleTheme: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(spotReadRecents(context)) }
    var checkinPending by remember { mutableStateOf(false) }

    // real user directory (web spotlight.tsx:211-219, GET /api/users)
    var directory by remember { mutableStateOf<List<User>?>(null) }
    LaunchedEffect(Unit) {
        if (directory == null) {
            CoroutineScope(Dispatchers.IO).launch {
                directory = repository.users().getOrNull()
            }
        }
    }

    // 250ms debounce feeding the server message search (web spotlight.tsx:181-184)
    LaunchedEffect(query) {
        delay(250)
        debounced = query.trim()
    }

    var msgHits by remember { mutableStateOf<List<MessageHit>?>(null) }
    var msgSearching by remember { mutableStateOf(false) }
    LaunchedEffect(debounced) {
        if (debounced.length < 2) {
            msgHits = null
            msgSearching = false
            return@LaunchedEffect
        }
        msgSearching = true
        val hits = repository.searchMessages(debounced).getOrNull()
        msgHits = hits
        msgSearching = false
    }

    val q = query.trim()
    val ql = q.lowercase()

    // Actions (web spotlight.tsx:258-289) - real handlers only
    val actions = listOf(
        SpotAction(
            key = "act-new-chat",
            label = "New chat",
            hint = "Pick someone to message",
            glyph = "LSquarePen",
            keepOpen = false,
            run = {
                if (q.isNotBlank()) spotPushRecent(context, q)
                onNewChat()
            },
        ),
        SpotAction(
            key = "act-checkin",
            label = if (checkinPending) "Checking in…" else "Check in to Hub",
            hint = "Daily Pulse Coins reward",
            glyph = "LFlame",
            keepOpen = false,
            run = {
                if (!checkinPending && viewerId.isNotBlank()) {
                    checkinPending = true
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = repository.checkinWallet()
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            checkinPending = false
                            result.onSuccess { res ->
                                Toast.makeText(
                                    context,
                                    "Checked in - +${res.reward} PC · ${res.streak}-day streak",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                                onCheckInToHub()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "Check-in failed",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            },
        ),
        SpotAction(
            key = "act-theme",
            label = if (darkResolved) "Switch to light theme" else "Switch to dark theme",
            hint = "Appearance",
            glyph = if (darkResolved) "LSunMedium" else "LMoon",
            keepOpen = true,
            run = onToggleTheme,
        ),
    ).filter { a -> ql.isEmpty() || a.label.lowercase().contains(ql) || a.hint.lowercase().contains(ql) }

    // Chat rows (web spotlight.tsx:306-324): title or preview contains, top 6
    val chatRows = conversations
        .map { c -> SpotChat(c, c.title, c.lastMessagePreview, c.unreadCount, c.isGroupish) }
        .filter { sc -> ql.isEmpty() || sc.title.lowercase().contains(ql) || (sc.subtitle ?: "").lowercase().contains(ql) }
        .take(6)

    // People rows (web spotlight.tsx:326-338): directory, not me, name/handle, top 6
    val peopleRows = (directory ?: emptyList())
        .filter { it.id != viewerId }
        .filter { u -> u.name.lowercase().contains(ql) || (u.handle.isNotEmpty() && u.handle.lowercase().contains(ql)) }
        .take(6)

    // Message rows (web spotlight.tsx:340-345): server hits, top 8
    val msgRows = if (debounced.length >= 2) (msgHits ?: emptyList()).take(8) else emptyList()

    // Section model (web spotlight.tsx:292-352)
    val sections: List<Pair<String, List<SpotRow>>> = when {
        q.isEmpty() -> buildList {
            if (recents.isNotEmpty()) {
                add("Recents" to recents.map { RRecent(it) })
            }
            add("Actions" to actions.map { RAction(it) })
        }
        else -> buildList {
            if (chatRows.isNotEmpty()) add("Chats" to chatRows.map { RChat(it) })
            if (peopleRows.isNotEmpty()) add("People" to peopleRows.map { RPerson(it) })
            if (msgRows.isNotEmpty()) add("Messages" to msgRows.map { RMessage(it) })
            if (actions.isNotEmpty()) add("Actions" to actions.map { RAction(it) })
        }
    }
    val flat = sections.flatMap { it.second }
    val nothingFound = q.isNotEmpty() && flat.isEmpty() && !msgSearching
    val serverPending = debounced.length >= 2 && msgSearching && msgHits == null

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x7309090B)) // zinc-950/45 dim scrim (web backdrop)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        BoxWithConstraints(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            // R75 CI fix - capture the overlay height OUTSIDE the card Column:
            // BoxWithConstraintsScope's maxHeight is not callable through the
            // Column content's implicit receiver chain.
            val overlayHeight = maxHeight
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = maxHeight * 0.07f) // web top-[7%]
                    .fillMaxWidth(0.92f) // web w-[92%]
                    .widthIn(max = 448.dp) // web max-w-md
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xF518181B)) // zinc-900 glass card
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp)),
            ) {
                // input row (web spotlight.tsx:459-477): search glyph + borderless field
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x0DFFFFFF))
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorLucideIcon("LSearch", tint = SubPageInk.Zinc500, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "Search chats, people, messages…",
                                color = SubPageInk.Zinc500,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = SubPageInk.Zinc50,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            cursorBrush = SolidColor(SubPageInk.Amber500),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (q.length >= 2 && msgSearching) {
                        val spin = rememberInfiniteRotation()
                        MirrorLucideIcon(
                            "LLoaderCircle",
                            tint = SubPageInk.Amber500,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = spin },
                        )
                    }
                }

                // results (web spotlight.tsx:480-527): max 58vh scroll area
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = overlayHeight * 0.58f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    when {
                        nothingFound -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 44.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MirrorLucideIcon("LSearchX", tint = SubPageInk.Zinc600, modifier = Modifier.size(28.dp))
                                Text(
                                    "No results for \u201C$q\u201D",
                                    color = SubPageInk.Zinc400,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Try a different name or phrase.",
                                    color = SubPageInk.Zinc500,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        q.length == 1 -> {
                            Text(
                                "Keep typing to search inside messages…",
                                color = SubPageInk.Zinc500,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                            )
                        }
                        else -> {
                            var rowIdx = 0
                            for ((label, rows) in sections) {
                                val sectionRows = rows
                                SpotSection(label = label) {
                                    for (row in sectionRows) {
                                        val i = rowIdx++
                                        val key = when (row) {
                                            is RRecent -> "recent-${row.text}"
                                            is RAction -> row.action.key
                                            is RChat -> "chat-${row.chat.conv.id}"
                                            is RPerson -> "person-${row.user.id}"
                                            is RMessage -> "msg-${row.hit.id}"
                                        }
                                        SpotStagger(index = i, key = key) {
                                            SpotRowSurface(
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    var keep = false
                                                    when (row) {
                                                        is RRecent -> query = row.text
                                                        is RAction -> {
                                                            keep = row.action.keepOpen
                                                            if (!keep && q.isNotBlank()) spotPushRecent(context, q)
                                                            row.action.run()
                                                        }
                                                        is RChat -> {
                                                            if (q.isNotBlank()) spotPushRecent(context, q)
                                                            onOpenConversation(row.chat.conv.id)
                                                        }
                                                        is RPerson -> {
                                                            if (q.isNotBlank()) spotPushRecent(context, q)
                                                            onOpenDm(row.user.id)
                                                        }
                                                        is RMessage -> {
                                                            if (q.isNotBlank()) spotPushRecent(context, q)
                                                            onOpenConversation(row.hit.conversationId)
                                                        }
                                                    }
                                                    if (!keep) onDismiss()
                                                },
                                            ) {
                                                when (row) {
                                                    is RRecent -> SpotRecentContent(row.text)
                                                    is RAction -> SpotActionContent(row.action)
                                                    is RChat -> SpotChatContent(row.chat, q)
                                                    is RPerson -> SpotPersonContent(row.user, q)
                                                    is RMessage -> SpotMessageContent(row.hit, q)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (serverPending) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val spin = rememberInfiniteRotation()
                                    MirrorLucideIcon(
                                        "LLoaderCircle",
                                        tint = SubPageInk.Amber500,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .graphicsLayer { rotationZ = spin },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Searching messages…",
                                        color = SubPageInk.Zinc500,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                // footer (web spotlight.tsx:530-553)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x0DFFFFFF))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "\u2191\u2193 navigate   \u21B5 open",
                        color = SubPageInk.Zinc500,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (recents.isNotEmpty()) {
                        Text(
                            "Clear recents",
                            color = SubPageInk.Zinc500,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .mirrorPressClick(onClick = {
                                    spotClearRecents(context)
                                    recents = emptyList()
                                })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Row surface (web spotlight.tsx:578-581): min 46dp, rounded, press physics. */
@Composable
private fun SpotRowSurface(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .mirrorPressClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun SpotRecentContent(text: String) {
    SpotGlyphTile("LClock", SubPageInk.Zinc500, Color(0x12FFFFFF))
    Text(
        text,
        color = SubPageInk.Zinc100,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpotActionContent(action: SpotAction) {
    SpotGlyphTile(action.glyph, SubPageInk.Amber500, Color(0x1AF59E0B))
    Column(Modifier.weight(1f)) {
        Text(
            action.label,
            color = SubPageInk.Zinc50,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            action.hint,
            color = SubPageInk.Zinc500,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpotChatContent(chat: SpotChat, query: String) {
    MirrorAvatar(
        name = chat.title,
        color = chat.conv.accentColor,
        isGroup = chat.isGroup,
        groupId = chat.conv.id,
        online = false,
        showPresence = false,
        sizeDp = 36,
        cornerDp = 36,
        photo = chat.conv.avatar,
    )
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                spotHighlight(chat.title, query),
                color = SubPageInk.Zinc50,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (chat.unread > 0) SpotUnreadBadge(chat.unread)
        }
        Text(
            spotHighlight(chat.subtitle ?: "", query),
            color = SubPageInk.Zinc400,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    MirrorLucideIcon("LMessageCircle", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpotPersonContent(user: User, query: String) {
    MirrorAvatar(
        name = user.name,
        color = user.color,
        isGroup = false,
        groupId = user.id,
        online = false,
        showPresence = false,
        sizeDp = 36,
        cornerDp = 36,
        photo = user.avatar,
    )
    Column(Modifier.weight(1f)) {
        Text(
            spotHighlight(user.name, query),
            color = SubPageInk.Zinc50,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (user.handle.isNotEmpty()) {
                buildAnnotatedString {
                    append("@")
                    append(spotHighlight(user.handle, query))
                }
            } else {
                AnnotatedString("Pulse user")
            },
            color = SubPageInk.Zinc400,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    MirrorLucideIcon("LUserRound", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpotMessageContent(hit: MessageHit, query: String) {
    SpotGlyphTile("LMessagesSquare", SubPageInk.Amber400, Color(0x12FFFFFF))
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                spotHighlight(hit.conversationName, query),
                color = SubPageInk.Zinc50,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                spotStamp(hit.createdAt),
                color = SubPageInk.Zinc500,
                fontSize = 10.5.sp,
            )
        }
        val body = if (hit.isFile && hit.fileName != null && !hit.content.lowercase().contains(query.lowercase())) {
            "Document - ${hit.fileName}"
        } else {
            hit.content
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = SubPageInk.Zinc500)) {
                    append(hit.senderName + ": ")
                }
                append(spotHighlight(body, query))
            },
            color = SubPageInk.Zinc400,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

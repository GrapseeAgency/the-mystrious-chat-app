package app.pulse.android.mirror

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.Channel
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.MentionItem
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R66 - the web's zinc-900 SUB-PAGES the artboard dock and kebab open over
 * the chats scene (calls-page.tsx + channels-page.tsx, verbatim conversion):
 *   - frosted glass-deep header (40dp glass back pill + 17sp bold title +
 *     amber count chip + 11sp subtitle)
 *   - #18181B page, glass-deep rounded-3xl row containers, SectionLabel rails
 *   - Calls: Today / Yesterday / Earlier sections, rows REOPEN the chat
 *   - Channels: Subscribed / Discover sections, real Subscribe pills
 * The old in-tab Calls/Updates surfaces were mirror inventions the web never
 * rendered - they carried the ember scene and homemade rows the user flagged.
 */

// zinc + amber truth (dark values the web renders on these pages)
internal object SubPageInk {
    val Page = Color(0xFF18181B) // zinc-900
    val Zinc50 = Color(0xFFFAFAFA)
    val Zinc100 = Color(0xFFF4F4F5)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc400 = Color(0xFFA1A1AA)
    val Zinc500 = Color(0xFF71717A)
    val Zinc600 = Color(0xFF52525B)
    val Zinc800 = Color(0xFF27272A)
    val Amber400 = Color(0xFFFBBF24)
    val Amber500 = Color(0xFFF59E0B)
    val Amber600 = Color(0xFFD97706)
    val Rose400 = Color(0xFFFB7185)
    val Rose500 = Color(0xFFF43F5E)
    val Violet400 = Color(0xFFA78BFA)
    val Panel = Color(0x991E1610) // glass-deep dark: rgba(30,22,16,0.6)
    val PanelBorder = Color(0x17FFFFFF) // white/9
    val GlassPill = Color(0x991E1610)
}

/** Full-screen sub-page: #18181B + glass-deep header with back pill + count chip. */
@Composable
internal fun MirrorSubPageScaffold(
    title: String,
    subtitle: String,
    countChip: Int? = null,
    onClose: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page)
            .statusBarsPadding(),
    ) {
        // header: glass-deep glass-sheen, border-b white/10, px-3 py-2.5
        Column(
            Modifier
                .fillMaxWidth()
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 40dp glass back pill, ArrowLeft 20dp zinc-300
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SubPageInk.GlassPill)
                        .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LArrowLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            title,
                            color = SubPageInk.Zinc50,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (countChip != null && countChip > 0) {
                            // amber-500/15 chip, 10sp bold amber-400
                            Box(
                                Modifier
                                    .heightIn(min = 18.dp)
                                    .widthIn(min = 18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x26F59E0B))
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (countChip > 99) "99+" else countChip.toString(),
                                    color = SubPageInk.Amber400,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                    Text(
                        subtitle,
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onRefresh != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SubPageInk.GlassPill)
                            .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                            .clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LRefreshCw", tint = SubPageInk.Zinc300, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x1AFFFFFF)),
            )
        }

        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** SectionLabel: px-3 pb-1.5 pt-4, 10sp bold uppercase tracking 0.14em zinc-500. */
@Composable
internal fun MirrorSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = SubPageInk.Zinc500,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 16.dp),
    )
}

/** glass-deep rounded-3xl p-1.5 row container the sub-pages group rows in. */
@Composable
internal fun MirrorGlassGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
            .padding(6.dp),
    ) {
        content()
    }
}

/** Shared empty state: 64dp glass icon tile + title + copy (web sub-pages). */
@Composable
internal fun MirrorSubPageEmpty(icon: String, title: String, copy: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(icon, tint = SubPageInk.Zinc500, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            color = SubPageInk.Zinc100,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            copy,
            color = SubPageInk.Zinc500,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

// ── Calls sub-page (calls-page.tsx) ─────────────────────────────────────────

private enum class CallBucket { Today, Yesterday, Earlier }

private fun callBucketOf(iso: String): CallBucket {
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return CallBucket.Earlier
    val day = parsed.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> CallBucket.Today
        today.minusDays(1) -> CallBucket.Yesterday
        else -> CallBucket.Earlier
    }
}

private val CALL_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val CALL_DAY_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun callStamp(iso: String): String {
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val zoned = parsed.atZone(ZoneId.systemDefault())
    return if (zoned.toLocalDate() == java.time.LocalDate.now()) {
        CALL_STAMP.format(zoned)
    } else {
        CALL_DAY_STAMP.format(zoned)
    }
}

private fun callDurationLabel(durationSec: Long): String = when {
    durationSec >= 3_600 -> "${durationSec / 3_600} h ${String.format("%02d", (durationSec % 3_600) / 60)} min"
    durationSec >= 60 -> "${durationSec / 60} min"
    durationSec > 0 -> "$durationSec sec"
    else -> ""
}

/** CallsPage: live call log in Today/Yesterday/Earlier glass groups; rows reopen the chat. */
@Composable
internal fun MirrorCallsPage(
    repository: PulseRepository,
    onOpenConversation: (String) -> Unit,
    onClose: () -> Unit,
) {
    var log by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        log = repository.refreshCallLog().getOrDefault(emptyList())
    }
    // web refetchInterval 20s
    LaunchedEffect(tick) {
        kotlinx.coroutines.delay(20_000)
        log = repository.refreshCallLog().getOrDefault(emptyList())
    }

    val sections = listOf(
        CallBucket.Today to log.filter { callBucketOf(it.startedAt) == CallBucket.Today },
        CallBucket.Yesterday to log.filter { callBucketOf(it.startedAt) == CallBucket.Yesterday },
        CallBucket.Earlier to log.filter { callBucketOf(it.startedAt) == CallBucket.Earlier },
    ).filter { it.second.isNotEmpty() }

    MirrorSubPageScaffold(
        title = "Calls",
        subtitle = "Voice and video history - tap a row to reopen the chat",
        countChip = log.size,
        onClose = onClose,
        onRefresh = {
            tick++
            CoroutineScope(Dispatchers.IO).launch {
                log = repository.refreshCallLog().getOrDefault(emptyList())
            }
        },
    ) {
        if (log.isEmpty()) {
            MirrorSubPageEmpty(
                icon = "LPhone",
                title = "No calls yet",
                copy = "No calls yet - start one from any chat. Tap the phone or video icon in a DM header and the history lands here.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                for ((bucket, rows) in sections) {
                    item(key = "sec-" + bucket.name) {
                        Column {
                            MirrorSectionLabel(bucket.name + " · " + rows.size)
                            MirrorGlassGroup {
                                for (entry in rows) {
                                    MirrorCallLogRow(entry = entry, onPress = { onOpenConversation(entry.conversationId) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** CallRow: 44dp circle avatar, direction chip, verb + kind, right time/duration. */
@Composable
private fun MirrorCallLogRow(entry: CallLogEntry, onPress: () -> Unit) {
    val missed = entry.status == app.pulse.domain.model.CallStatus.MISSED ||
        entry.status == app.pulse.domain.model.CallStatus.DECLINED
    val peerName = entry.peer?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"
    val verb = when {
        missed && entry.status == app.pulse.domain.model.CallStatus.DECLINED -> "Declined"
        missed -> "Missed"
        entry.outgoing -> "Outgoing"
        else -> "Incoming"
    }
    val kindNoun = if (entry.kind == app.pulse.domain.model.CallKind.VIDEO) "video call" else "voice call"
    val accentTint = if (missed) SubPageInk.Rose400 else SubPageInk.Amber400
    val chipBg = if (missed) Color(0x1AF43F5E) else Color(0x1AF59E0B)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPress)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorAvatar(
            name = peerName,
            color = entry.peer?.color,
            isGroup = false,
            groupId = "",
            online = false,
            showPresence = false,
            sizeDp = 44,
            cornerDp = 22,
        )
        Column(Modifier.weight(1f)) {
            Text(
                peerName,
                color = SubPageInk.Zinc50,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(chipBg),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon(
                        if (entry.outgoing) "LPhoneOutgoing" else "LPhoneIncoming",
                        tint = accentTint,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    "$verb $kindNoun",
                    color = if (missed) SubPageInk.Rose400 else SubPageInk.Zinc400,
                    fontSize = 12.sp,
                    fontWeight = if (missed) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.kind == app.pulse.domain.model.CallKind.VIDEO) {
                    MirrorLucideIcon("LVideo", tint = SubPageInk.Zinc500, modifier = Modifier.size(14.dp))
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                callStamp(entry.startedAt),
                color = SubPageInk.Zinc500,
                fontSize = 11.sp,
            )
            if (!missed && entry.durationSec > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    callDurationLabel(entry.durationSec),
                    color = SubPageInk.Zinc500,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── Channels sub-page (channels-page.tsx) ───────────────────────────────────

/** ChannelsPage: Subscribed / Discover directory with real Subscribe pills. */
@Composable
internal fun MirrorChannelsPage(
    repository: PulseRepository,
    onOpenConversation: (String) -> Unit,
    onClose: () -> Unit,
) {
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    suspend fun load() {
        channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(tick) {
        kotlinx.coroutines.delay(30_000)
        channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
    }

    val subscribed = channels.filter { it.isSubscribed }
    val discover = channels.filter { !it.isSubscribed }

    fun subscribe(channel: Channel) {
        busyId = channel.id
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.subscribeChannel(channel.id) }
            channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
            busyId = null
        }
    }

    MirrorSubPageScaffold(
        title = "Channels",
        subtitle = "Broadcast spaces - only admins post",
        countChip = subscribed.size,
        onClose = onClose,
        onRefresh = {
            tick++
            CoroutineScope(Dispatchers.IO).launch {
                channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
            }
        },
    ) {
        if (channels.isEmpty()) {
            MirrorSubPageEmpty(
                icon = "LRadio",
                title = "No channels yet",
                copy = "Start one from the new-chat sheet - pick New channel. Yours shows up here, and in every subscriber's list.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "sec-sub") {
                    Column {
                        MirrorSectionLabel("Subscribed" + if (subscribed.isNotEmpty()) " · " + subscribed.size else "")
                        if (subscribed.isEmpty()) {
                            Text(
                                "Nothing yet - discover a channel below.",
                                color = SubPageInk.Zinc500,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        } else {
                            MirrorGlassGroup {
                                for (channel in subscribed) {
                                    MirrorChannelRow(
                                        channel = channel,
                                        busy = busyId == channel.id,
                                        onPress = { onOpenConversation(channel.id) },
                                        onSubscribe = { subscribe(channel) },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "sec-disc") {
                    Column {
                        MirrorSectionLabel("Discover")
                        MirrorGlassGroup {
                            for (channel in discover) {
                                MirrorChannelRow(
                                    channel = channel,
                                    busy = busyId == channel.id,
                                    onPress = { onOpenConversation(channel.id) },
                                    onSubscribe = { subscribe(channel) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ChannelRow: 44dp glass tile + Radio glyph, meta lines, Open/Subscribe pill. */
@Composable
private fun MirrorChannelRow(
    channel: Channel,
    busy: Boolean,
    onPress: () -> Unit,
    onSubscribe: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            // fallback tile: glass-deep rounded-2xl + Radio 20dp amber-400
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LRadio", tint = SubPageInk.Amber400, modifier = Modifier.size(20.dp))
            }
            if (channel.unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SubPageInk.Amber500),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                color = SubPageInk.Zinc50,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                channel.description ?: channel.preview ?: "No description yet",
                color = SubPageInk.Zinc400,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MirrorLucideIcon("LUsers", tint = SubPageInk.Zinc500, modifier = Modifier.size(12.dp))
                Text(
                    if (channel.memberCount == 1) "1 subscriber" else "${channel.memberCount} subscribers",
                    color = SubPageInk.Zinc500,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (channel.isSubscribed) {
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(SubPageInk.GlassPill)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .clickable(onClick = onPress)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Open", color = SubPageInk.Amber400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(SubPageInk.Amber500)
                    .clickable(enabled = !busy, onClick = onSubscribe)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "…" else "Subscribe",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Archived sub-page (chats-archived-page.tsx, R92) ────────────────────────

/**
 * R92 - web truth: the kebab Archived row opens the #/chats/archived FULL
 * sub-page (chats-tab.tsx:1614 openArchivedPage -> ChatsArchivedPage:2083),
 * never a bottom sheet. Same recipe as the calls page: zinc-900 page +
 * glass-deep header + count chip; rows reopen the chat, trailing ghost
 * unarchives (web row carries the same unarchive chip).
 */
@Composable
internal fun MirrorArchivedPage(
    archived: List<Conversation>,
    presence: Set<String>,
    onOpen: (Conversation) -> Unit,
    onUnarchive: (Conversation) -> Unit,
    onClose: () -> Unit,
) {
    MirrorSubPageScaffold(
        title = "Archived",
        // web chats-archived-page.tsx:86 subtitle truth
        subtitle = "Muted here - a new message moves a chat back to your inbox",
        countChip = archived.size,
        onClose = onClose,
    ) {
        if (archived.isEmpty()) {
            // web chats-archived-page.tsx:112-118 empty truth
            MirrorSubPageEmpty(
                icon = "LArchive",
                title = "No archived chats",
                copy = "Swipe left on a chat and tap Archive - it waits here. A new message brings it straight back to your inbox.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    MirrorGlassGroup {
                        for (convo in archived) {
                            MirrorArchivedRow(
                                convo = convo,
                                online = convo.otherUserId != null && presence.contains(convo.otherUserId),
                                onPress = { onOpen(convo) },
                                onUnarchive = { onUnarchive(convo) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorArchivedRow(
    convo: Conversation,
    online: Boolean,
    onPress: () -> Unit,
    onUnarchive: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPress)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorAvatar(
            name = convo.title,
            color = convo.accentColor,
            isGroup = convo.isGroupish,
            groupId = convo.id,
            online = online,
            showPresence = !convo.isGroupish,
            sizeDp = 44,
            cornerDp = if (convo.isGroupish) 14 else 22,
        )
        Column(Modifier.weight(1f)) {
            Text(
                convo.title,
                color = SubPageInk.Zinc50,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                convo.lastMessagePreview ?: "No messages yet",
                color = SubPageInk.Zinc400,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 34dp ghost unarchive (web swipe chip equivalent)
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onUnarchive),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LArchiveRestore", tint = SubPageInk.Zinc400, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Mentions sub-page (mentions-page.tsx, R92) ──────────────────────────────

/**
 * R92 - web truth: the kebab Mentions row opens the #/mentions FULL sub-page
 * (chats-tab.tsx:1642 openMentionsPage -> MentionsPage:2113), never a bottom
 * sheet. Real GET /api/mentions feed, 30s refetch (web refetchInterval),
 * rows: circular author avatar + semibold name + snippet with the @Me token
 * highlighted (web mark bg-amber-500/15 text-amber-300, mentions-page.tsx:85)
 * + @where row + stamp/chevron column. Error state with retry is honest.
 */
@Composable
internal fun MirrorMentionsPage(
    repository: PulseRepository,
    onOpenConv: (String) -> Unit,
    onClose: () -> Unit,
) {
    var items by remember { mutableStateOf<List<MentionItem>?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun load() {
        CoroutineScope(Dispatchers.IO).launch {
            repository.mentions().fold(
                onSuccess = {
                    items = it
                    errorText = null
                },
                onFailure = {
                    if (items == null) items = emptyList()
                    errorText = it.message ?: "Something went wrong."
                },
            )
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(tick) {
        if (tick > 0) load()
        // web refetchInterval 30_000
        kotlinx.coroutines.delay(30_000)
        tick++
    }

    val list = items
    MirrorSubPageScaffold(
        title = "Mentions",
        // web mentions-page.tsx:204 subtitle truth
        subtitle = "Messages that mention you - tap a row to jump in",
        countChip = list?.size ?: 0,
        onClose = onClose,
        onRefresh = { tick++ },
    ) {
        when {
            list == null -> Column(Modifier.padding(top = 16.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SubPageInk.Panel),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SubPageInk.Panel),
                )
            }
            list.isEmpty() && errorText == null -> MirrorSubPageEmpty(
                icon = "LAtSign",
                title = "No mentions yet",
                copy = "No mentions yet - when someone @-names you it lands here",
            )
            errorText != null && list.isEmpty() -> MirrorSubPageEmpty(
                icon = "LAtSign",
                title = "Could not load mentions",
                copy = errorText ?: "Something went wrong.",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    MirrorGlassGroup {
                        for (mention in list) {
                            MirrorMentionRow(
                                mention = mention,
                                onPress = { onOpenConv(mention.conversationId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * web MentionRow (mentions-page.tsx:94-150): circular 44dp author avatar,
 * 14.5sp semibold author, 13sp snippet with the @Me token in amber, 11sp
 * @where row, right column stamp + chevron.
 */
@Composable
private fun MirrorMentionRow(mention: MentionItem, onPress: () -> Unit) {
    val authorName = mention.authorName.ifBlank { "Unknown" }
    val where = mention.conversationName ?: "Direct message"

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPress)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // people are CIRCLES (web:127) - never squircles
        MirrorAvatar(
            name = authorName,
            color = null,
            isGroup = false,
            groupId = "",
            online = false,
            showPresence = false,
            sizeDp = 44,
            cornerDp = 22,
        )
        Column(Modifier.weight(1f)) {
            Text(
                authorName,
                color = SubPageInk.Zinc50,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // snippet with the FIRST @AuthorName token highlighted (web:70-91)
            Text(
                buildAnnotatedString {
                    val token = "@" + authorName
                    val idx = mention.snippet.indexOf(token, ignoreCase = true)
                    if (idx < 0) {
                        append(mention.snippet)
                    } else {
                        val end = idx + token.length
                        append(mention.snippet.substring(0, idx))
                        withStyle(SpanStyle(color = SubPageInk.Amber400, fontWeight = FontWeight.SemiBold)) {
                            append(mention.snippet.substring(idx, end))
                        }
                        append(mention.snippet.substring(end))
                    }
                },
                color = SubPageInk.Zinc400,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MirrorLucideIcon("LAtSign", tint = SubPageInk.Amber400.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                Text(
                    where,
                    color = SubPageInk.Zinc500,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                mentionStamp(mention.createdAt),
                color = SubPageInk.Zinc500,
                fontSize = 11.sp,
            )
            MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
        }
    }
}

/** web formatListStamp: today HH:mm, yesterday "Yesterday", else "MMM d". */
private fun mentionStamp(iso: String): String = runCatching {
    val at = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val pattern = when (at.toLocalDate()) {
        today -> DateTimeFormatter.ofPattern("HH:mm")
        today.minusDays(1) -> null
        else -> DateTimeFormatter.ofPattern("MMM d")
    }
    pattern?.format(at) ?: "Yesterday"
}.getOrDefault("")

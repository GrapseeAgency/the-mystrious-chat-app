package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.UserProfile
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R72 - the web #/user/:id route page (user-route-page.tsx) as a native
 * sub-page: palette cover (photo when set), 96dp avatar (photo/initials),
 * presence pill, name + registered badge, tap-to-copy handle chip, status
 * glyph + text, about, real activity stamps, SHARED GROUPS computed from
 * the live conversations cache, Continue-direct-chat dedupe, Message action
 * (createDm), copy account ID, block/unblock + report panel. Every value
 * rides the real repository - zero mocks.
 */

/** "Active 5m ago" - web activeAgoLabel verbatim (refreshes while open). */
private fun userActiveAgoLabel(iso: String, now: Long): String {
    val t = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return "Active a while ago"
    val seconds = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "Active just now"
        seconds < 3_600 -> "Active ${seconds / 60}m ago"
        seconds < 86_400 -> "Active ${seconds / 3_600}h ago"
        seconds < 7 * 86_400 -> "Active ${seconds / 86_400}d ago"
        else -> "Active " + DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(t))
    }
}

@Composable
internal fun MirrorUserPage(
    userId: String,
    viewerId: String,
    repository: PulseRepository,
    conversations: List<Conversation>,
    presence: Set<String>,
    onDismiss: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var blockBusy by remember { mutableStateOf(false) }
    var blockArmed by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf<String?>(null) }
    var reportDetails by remember { mutableStateOf("") }
    var reportBusy by remember { mutableStateOf(false) }
    var reportDone by remember { mutableStateOf(false) }
    var dmBusy by remember { mutableStateOf(false) }
    var handleCopied by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current

    // the web refreshes the relative stamps every 30s while the page is open
    LaunchedEffect(userId) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(30_000)
        }
    }
    LaunchedEffect(userId) {
        loadFailed = false
        val user = runCatching { repository.userProfile(userId) }.getOrNull()?.getOrNull()
        profile = user
        if (user == null) loadFailed = true
        blocked = runCatching { repository.blockState(userId) }.getOrNull()?.getOrNull() ?: false
    }

    val user = profile
    val online = presence.contains(userId)
    // groups where BOTH the viewer and this person are members (real overlap)
    val sharedGroups = conversations
        .filter { it.isGroupish && it.members.any { m -> m.id == userId } }
        .take(6)
    // existing 1:1 DM with this person, when one is already on the wire
    val existingDm = conversations.firstOrNull { convo ->
        !convo.isGroupish && !convo.isSelf && convo.members.any { it.id == userId }
    }
    val isSelf = userId == viewerId

    Box(Modifier.fillMaxSize().background(MirrorArt.Bg)) {
        when {
            // not found - web UserX state
            loadFailed -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(24.dp))
                            .background(Color(0x1AF43F5E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LUserRoundMinus", tint = MirrorArt.Red, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Profile unavailable", color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "This Pulse member does not exist (or is no longer here).",
                        color = MirrorArt.Dim, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    MirrorUserBackButton(onDismiss)
                }
            }
            user == null -> {
                // loading skeleton - web skeleton shape
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(176.dp).background(MirrorArt.White7))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.size(96.dp).clip(CircleShape).background(MirrorArt.White7))
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth(0.4f).height(22.dp).clip(RoundedCornerShape(8.dp)).background(MirrorArt.White7))
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(0.6f).height(32.dp).clip(RoundedCornerShape(16.dp)).background(MirrorArt.White7))
                        Spacer(Modifier.height(22.dp))
                        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(24.dp)).background(MirrorArt.White7))
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(24.dp)).background(MirrorArt.White7))
                    }
                }
            }
            else -> {
                val firstName = user.name.trim().split(" ").firstOrNull() ?: user.name
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // cover: photo over the palette gradient (web h-44)
                    Box(Modifier.fillMaxWidth().height(176.dp)) {
                        Box(Modifier.fillMaxSize().background(MirrorArt.avatarBrush(user.color, false, user.id)))
                        val coverIso = user.coverImage
                        if (!coverIso.isNullOrBlank()) {
                            AsyncImage(
                                model = mirrorUploadHttp(coverIso),
                                contentDescription = "Cover picture of ${user.name}",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        // avatar overlapping the cover + presence pill
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MirrorAvatar(
                                name = user.name,
                                color = user.color,
                                isGroup = false,
                                groupId = user.id,
                                online = online,
                                showPresence = true,
                                sizeDp = 96,
                                cornerDp = 48,
                                photo = user.avatar,
                                modifier = Modifier.offset(x = 0.dp, y = (-48).dp),
                            )
                            Row(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                    .background(MirrorArt.White7).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(if (online) MirrorArt.PresenceOnline else MirrorArt.PresenceOffline),
                                )
                                Text(
                                    text = run {
                                        val seenIso = user.lastSeenIso
                                        when {
                                            online -> "Online now"
                                            !seenIso.isNullOrBlank() -> userActiveAgoLabel(seenIso, nowTick)
                                            else -> "Last seen hidden"
                                        }
                                    },
                                    color = MirrorArt.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // name + registered badge
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                user.name,
                                color = MirrorArt.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 260.dp),
                            )
                            MirrorLucideIcon("LBadgeCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        // handle chip - tap to copy (web copyHandle)
                        if (!user.handle.isNullOrBlank()) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(MirrorArt.White7)
                                    .clickable {
                                        handleCopied = true
                                        mirrorCopyText(context, "@${user.handle}", "@${user.handle} copied")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (handleCopied) {
                                    MirrorLucideIcon("LCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                                    Text("Copied", color = MirrorArt.Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("@${user.handle}", color = MirrorArt.Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    MirrorLucideIcon("LCopy", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                                }
                            }
                        } else {
                            Text("No handle yet", color = MirrorArt.Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        // status glyph + text
                        if (!user.statusEmoji.isNullOrBlank() || !user.statusText.isNullOrBlank()) {
                            Row(
                                Modifier.padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                mirrorStatusGlyphName(user.statusEmoji)?.let { glyph ->
                                    MirrorLucideIcon(glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                }
                                val statusValue = user.statusText
                                if (!statusValue.isNullOrBlank()) {
                                    Text(statusValue, color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        // about
                        Text(
                            text = user.about?.trim()?.ifBlank { null } ?: "No bio yet",
                            color = MirrorArt.Dim, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        // activity stamps
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(MirrorArt.White7).padding(6.dp),
                        ) {
                            MirrorUserStampRow("LCalendarDays", "Member since", memberSinceLabel(user))
                            Box(Modifier.padding(horizontal = 12.dp).height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
                            val seenIso2 = user.lastSeenIso
                            MirrorUserStampRow(
                                "LWaves", "Last seen",
                                when {
                                    online -> "Online now"
                                    !seenIso2.isNullOrBlank() -> userActiveAgoLabel(seenIso2, nowTick)
                                    else -> "Hidden"
                                },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // shared groups (real membership overlap)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(MirrorArt.White7).padding(6.dp),
                        ) {
                            Text(
                                "SHARED GROUPS",
                                color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                            if (sharedGroups.isEmpty()) {
                                Text(
                                    "No shared groups yet - say hello in a room you both joined.",
                                    color = MirrorArt.Faint, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp),
                                )
                            } else {
                                for (room in sharedGroups) {
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                            .clickable { onOpenConversation(room.id) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MirrorAvatar(
                                            name = room.title,
                                            color = room.accentColor,
                                            isGroup = true,
                                            groupId = room.id,
                                            online = false,
                                            showPresence = false,
                                            sizeDp = 36,
                                            cornerDp = 18,
                                            photo = room.avatar,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(room.title, color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val n = room.members.size
                                            Text("$n ${if (n == 1) "member" else "members"}", color = MirrorArt.Faint, fontSize = 11.sp)
                                        }
                                        MirrorLucideIcon("LChevronRight", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            if (existingDm != null) {
                                Box(Modifier.padding(horizontal = 10.dp).height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                        .clickable { onOpenConversation(existingDm.id) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape).background(MirrorArt.Accent.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MirrorLucideIcon("LMessageCircle", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                    }
                                    Text("Continue direct chat", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    MirrorLucideIcon("LChevronRight", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        // actions: Message + copy ID (web actions row)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp))
                                    .background(MirrorArt.Accent)
                                    .clickable(enabled = !isSelf && !dmBusy) {
                                        dmBusy = true
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val target = existingDm ?: runCatching {
                                                repository.createDm(userId).getOrNull()
                                            }.getOrNull()
                                            withContext(Dispatchers.Main) {
                                                dmBusy = false
                                                if (target != null) onOpenConversation(target.id)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            ) {
                                MirrorLucideIcon("LMessageCircle", tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("Message $firstName", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MirrorArt.White7)
                                    .clickable {
                                        mirrorCopyText(context, user.id, "Account ID copied")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon("LCopy", tint = MirrorArt.TextSoft, modifier = Modifier.size(16.dp))
                            }
                        }
                        // block / unblock + report (web danger rows)
                        if (!isSelf) {
                            val blockLabel = if (blocked) "Unblock $firstName" else "Block $firstName"
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0x40F43F5E), RoundedCornerShape(16.dp))
                                    .background(Color(0x0FF43F5E))
                                    .clickable {
                                        // 2-tap confirm before blocking (destructive guard)
                                        if (blocked || blockArmed) {
                                            blockArmed = false
                                            blockBusy = true
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val r = runCatching {
                                                    if (blocked) repository.unblock(userId) else repository.block(userId)
                                                }.isSuccess
                                                withContext(Dispatchers.Main) {
                                                    blockBusy = false
                                                    if (r) blocked = !blocked
                                                }
                                            }
                                        } else {
                                            blockArmed = true
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            ) {
                                MirrorLucideIcon("LBan", tint = MirrorArt.Red, modifier = Modifier.size(16.dp))
                                Text(
                                    if (blockArmed && !blocked) "Tap again to block" else blockLabel,
                                    color = MirrorArt.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                                    .background(MirrorArt.White7)
                                    .clickable { reportOpen = !reportOpen }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            ) {
                                MirrorLucideIcon("LFlag", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                Text("Report $firstName", color = MirrorArt.Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            if (reportOpen) {
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 8.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(MirrorArt.White7).padding(10.dp),
                                ) {
                                    if (reportDone) {
                                        Text("Report sent. Thank you for keeping Pulse safe.", color = MirrorArt.Dim, fontSize = 12.sp)
                                    } else {
                                        Text(
                                            "Why are you reporting $firstName?",
                                            color = MirrorArt.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        // web ReportPanel REASONS verbatim
                                        for (reason in listOf(
                                            "spam" to "Spam",
                                            "harassment" to "Harassment or bullying",
                                            "impersonation" to "Impersonation",
                                            "inappropriate" to "Inappropriate content",
                                            "scam" to "Scam or fraud",
                                            "other" to "Something else",
                                        )) {
                                            val active = reportReason == reason.first
                                            Row(
                                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                                    .background(if (active) MirrorArt.ChipActive else Color.Transparent)
                                                    .clickable { reportReason = reason.first }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(reason.second, color = if (active) MirrorArt.Text else MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                                            }
                                        }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = reportDetails,
                                            onValueChange = { if (it.length <= 500) reportDetails = it },
                                            textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 12.sp),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent2),
                                            decorationBox = { inner ->
                                                Box(
                                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                                        .background(MirrorArt.White10)
                                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                                ) {
                                                    if (reportDetails.isBlank()) {
                                                        Text("Extra details (optional)", color = MirrorArt.Faint, fontSize = 12.sp)
                                                    }
                                                    inner()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().padding(top = 8.dp).height(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (reportReason != null && !reportBusy) MirrorArt.Accent else MirrorArt.White10)
                                                .clickable(enabled = reportReason != null && !reportBusy) {
                                                    val chosen = reportReason ?: return@clickable
                                                    reportBusy = true
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        val ok = runCatching {
                                                            repository.report(userId, chosen, reportDetails.trim().ifBlank { null })
                                                        }.isSuccess
                                                        withContext(Dispatchers.Main) {
                                                            reportBusy = false
                                                            if (ok) {
                                                                reportDone = true
                                                                reportOpen = true
                                                            }
                                                        }
                                                    }
                                                }
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                        ) {
                                            Text(
                                                if (reportBusy) "Sending..." else "Send report",
                                                color = if (reportReason != null) Color.White else MirrorArt.Faint,
                                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                // floating back - glass pill over the cover
                MirrorUserBackButton(
                    onDismiss,
                    Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MirrorUserBackButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x9918181B))
            .border(1.dp, MirrorArt.Hairline, CircleShape)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon("LChevronLeft", tint = MirrorArt.Text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MirrorUserStampRow(glyph: String, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorLucideIcon(glyph, tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
        Text(label, color = MirrorArt.Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            value,
            color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Web formatMemberSince: "MMM yyyy" from the real createdAt stamp. */
private fun memberSinceLabel(user: UserProfile): String {
    val iso = user.createdAtIso ?: return ""
    return runCatching {
        DateTimeFormatter.ofPattern("MMM yyyy").withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    }.getOrDefault("")
}

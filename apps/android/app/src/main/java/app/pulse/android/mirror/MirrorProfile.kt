package app.pulse.android.mirror

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.repository.PulseRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Profile surface: cover ridges, ringed avatar, pills, stats, SAVED / ACCOUNT (reference 1). */
@Composable
internal fun MirrorProfile(
    viewerId: String,
    repository: PulseRepository,
    iAmOnline: Boolean,
    onSignOut: () -> Unit,
    onCopyId: (String) -> Unit,
) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var stats by remember { mutableStateOf<UserStats?>(null) }
    var coins by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewerId) {
        profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
        stats = runCatching { repository.userStats(viewerId) }.getOrNull()?.getOrNull()
        // Wallet coins render in the stats card (web parity - the hub wallet).
        runCatching { repository.wallet() }.getOrNull()?.getOrNull()?.let { coins = it.wallet.coins }
    }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            // R60 - the profile starts below the status bar like the web's
            // safe-area padding (the audited build tucked the cover under the clock).
            .statusBarsPadding()
            .verticalScroll(scroll)
            .padding(bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            MirrorCoverHeader(profile?.coverImage != null)

            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    // overlapping avatar: gradient ring + carbon gap + 84dp disc
                    Box(
                        Modifier
                            .offset(y = (-44).dp)
                            .size(92.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        MirrorArt.avatarGradient(profile?.color),
                                    ),
                                )
                                .padding(2.5.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1610))
                                    .padding(2.5.dp),
                            ) {
                                MirrorAvatar(
                                    name = profile?.name ?: "...",
                                    color = profile?.color,
                                    isGroup = false,
                                    groupId = "",
                                    online = iAmOnline,
                                    showPresence = true,
                                    sizeDp = 84,
                                    cornerDp = 42,
                                )
                            }
                        }
                    }

                    // name + quiet trust mark
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.name ?: viewerId,
                            color = MirrorArt.Text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        MirrorPhosphorIcon("PSeal", tint = MirrorArt.SealAmber, modifier = Modifier.size(16.dp))
                    }

                    Spacer(Modifier.height(10.dp))
                    // @handle chip
                    Row(
                        Modifier
                            .height(32.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Chip)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "@" + (profile?.handle?.takeIf { it.isNotBlank() } ?: (profile?.name ?: "").lowercase().replace("\\s+".toRegex(), "")),
                            color = MirrorArt.Accent2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (!profile?.about.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(profile?.about.orEmpty(), color = MirrorArt.TextSoft, fontSize = 14.sp, lineHeight = 18.sp)
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Edit profile - ember pill, dark ink text (artboard truth)
                        Row(
                            Modifier
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(MirrorArt.FabGradient)
                                .clickable { }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MirrorPhosphorIcon("PEdit", tint = Color(0xFF241304), modifier = Modifier.size(16.dp))
                            Text("Edit profile", color = Color(0xFF241304), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        // Share - glass pill
                        Row(
                            Modifier
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(MirrorArt.Chip)
                                .border(1.dp, MirrorArt.Hairline, CircleShape)
                                .clickable { }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MirrorPhosphorIcon("PShare", tint = MirrorArt.Text, modifier = Modifier.size(16.dp))
                            Text("Share", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    MirrorStatsCard(
                        messages = stats?.messages ?: 0,
                        rooms = stats?.chats ?: 0,
                        coins = coins,
                        since = MirrorMonthYear(stats?.joinedAtIso),
                    )
                }
            }

            MirrorSectionLabel("SAVED")
            Column(Modifier.padding(horizontal = 12.dp)) {
                MirrorAccountCard {
                    MirrorAccountRow(
                        phosphor = "PStar",
                        iconTint = Color(0xFFE5A33C),
                        circleTint = Color(0x14E5A33C),
                        title = "Saved messages",
                        subtitle = "Long-press any message in a chat, then Save",
                        titleTint = MirrorArt.Text,
                    )
                }
            }

            MirrorSectionLabel("ACCOUNT")
            Column(Modifier.padding(horizontal = 12.dp)) {
                MirrorAccountCard {
                    MirrorAccountRow(
                        phosphor = "PFingerprint",
                        iconTint = MirrorArt.TextSoft,
                        circleTint = MirrorArt.Chip,
                        title = "Copy account ID",
                        subtitle = viewerId,
                        titleTint = MirrorArt.Text,
                        onClick = { onCopyId(viewerId) },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MirrorArt.Hairline),
                    )
                    MirrorAccountRow(
                        phosphor = "PSignOut",
                        iconTint = MirrorArt.Red,
                        circleTint = Color(0x1FFF453A),
                        title = "Sign out",
                        subtitle = "Return to the welcome screen - nothing is deleted",
                        titleTint = MirrorArt.Red,
                        onClick = onSignOut,
                    )
                }
            }
        }
    }
}

/** Cover band: 132dp warm gradient with the fine horizontal ridge texture + Add cover pill. */
@Composable
private fun MirrorCoverHeader(hasCover: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // base vertical wash (#2B1C10 -> #191009)
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MirrorArt.SceneTop, Color(0xFF191009))))
            // horizontal ridges: 1px every 6px, white 5%
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0x0DFFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
                y += 6f
            }
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // corner kebab (three dots) - 40dp glass circle
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.Chip)
                    .border(1.dp, MirrorArt.Hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LKebab", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .offset(y = (-8).dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color(0x59000000))
                    .border(1.dp, Color(0x40FFFFFF), CircleShape)
                    .clickable { }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MirrorPhosphorIcon("PPhoto", tint = Color.White, modifier = Modifier.size(14.dp))
                Text("Add cover", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 4 column stats: MESSAGES / ROOMS / COINS / SINCE (coins in ember). */
@Composable
private fun MirrorStatsCard(messages: Long, rooms: Long, coins: Long?, since: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MirrorArt.Panel)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MirrorStatCell(messages.toString(), "MESSAGES", MirrorArt.Text, Modifier.weight(1f))
        MirrorStatCell(rooms.toString(), "ROOMS", MirrorArt.Text, Modifier.weight(1f))
        MirrorStatCell((coins ?: 0).toString(), "COINS", MirrorArt.Accent, Modifier.weight(1f))
        MirrorStatCell(since, "SINCE", MirrorArt.Text, Modifier.weight(1.2f), small = true)
    }
}

@Composable
private fun RowScope.MirrorStatCell(
    value: String,
    label: String,
    tint: Color,
    weight: Modifier,
    small: Boolean = false,
) {
    Column(
        weight
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = tint,
            fontSize = if (small) 15.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = MirrorArt.Dim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MirrorSectionLabel(text: String) {
    Text(
        text,
        color = MirrorArt.Faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun MirrorAccountCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MirrorArt.Panel)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(20.dp)),
    ) {
        content()
    }
}

@Composable
private fun MirrorAccountRow(
    phosphor: String,
    iconTint: Color,
    circleTint: Color,
    title: String,
    subtitle: String,
    titleTint: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(circleTint),
            contentAlignment = Alignment.Center,
        ) {
            MirrorPhosphorIcon(phosphor, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = titleTint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = MirrorArt.Dim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MirrorLucideIcon("LChevronRight", tint = MirrorArt.Faint, modifier = Modifier.size(18.dp))
    }
}

/** Aug 2026 - the SINCE cell format. */
private fun MirrorMonthYear(iso: String?): String {
    val parsed = iso?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return ""
    val month = DateTimeFormatter.ofPattern("MMM").withZone(ZoneId.systemDefault()).format(parsed)
    val year = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneId.systemDefault()).format(parsed)
    return "$month $year"
}

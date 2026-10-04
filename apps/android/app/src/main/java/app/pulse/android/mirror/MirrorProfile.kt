package app.pulse.android.mirror

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.repository.PulseRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Profile surface (web profile-tab.tsx R26-d parity): cover ridges, ringed
 * avatar, handle chip, status line, bio, Edit profile / Share pills, the
 * real stats instrument row, SAVED + ACCOUNT sections. The corner kebab
 * opens the web ProfileMoreMenu (Hub / Settings / Saved messages); Add
 * cover rides the real upload pipeline (POST /api/uploads + PATCH
 * coverImage); Share fires the OS share sheet with clipboard fallback;
 * sign-out carries the web's confirm dialog. Zero dead controls.
 */
@Composable
internal fun MirrorProfile(
    viewerId: String,
    repository: PulseRepository,
    iAmOnline: Boolean,
    onSignOut: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSaved: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var stats by remember { mutableStateOf<UserStats?>(null) }
    var coins by remember { mutableStateOf<Long?>(null) }
    var kebabOpen by remember { mutableStateOf(false) }
    var signOutOpen by remember { mutableStateOf(false) }
    var coverBusy by remember { mutableStateOf(false) }

    fun reloadProfile() {
        CoroutineScope(Dispatchers.IO).launch {
            profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
        }
    }

    LaunchedEffect(viewerId) {
        profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
        stats = runCatching { repository.userStats(viewerId) }.getOrNull()?.getOrNull()
        // Wallet coins render in the stats card (web parity - the hub wallet).
        runCatching { repository.wallet() }.getOrNull()?.getOrNull()?.let { coins = it.wallet.coins }
    }

    // R39 - cover picture pipeline: pick -> compress -> POST /api/uploads ->
    // PATCH coverImage (web coverMutation verbatim flow).
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            coverBusy = true
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val dataUrl = mirrorProfileUriToDataUrl(context, uri, maxSide = 1280)
                    val path = repository.uploadMedia(dataUrl).getOrThrow()
                    repository.patchProfile(ProfilePatch(coverImage = path))
                }
                coverBusy = false
                reloadProfile()
            }
        }
    }
    val removeCover = {
        coverBusy = true
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.patchProfile(ProfilePatch(coverImage = "")) }
            coverBusy = false
            reloadProfile()
        }
        kotlin.Unit
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
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
                MirrorCoverHeader(
                    hasCover = profile?.coverImage != null,
                    coverBusy = coverBusy,
                    onKebab = { kebabOpen = true },
                    onAddCover = {
                        pickCover.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onRemoveCover = removeCover,
                )

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
                        // @handle chip - tap to copy (web copyHandle)
                        Row(
                            Modifier
                                .height(32.dp)
                                .clip(CircleShape)
                                .background(MirrorArt.Chip)
                                .border(1.dp, MirrorArt.Hairline, CircleShape)
                                .clickable {
                                    val handle = profile?.handle
                                    if (!handle.isNullOrBlank()) {
                                        mirrorCopyText(context, "@$handle", "Handle copied")
                                    } else {
                                        onEditProfile()
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MirrorPhosphorIcon("PAt", tint = MirrorArt.Accent2, modifier = Modifier.size(13.dp))
                            Text(
                                if (!profile?.handle.isNullOrBlank()) "@${profile?.handle}" else "Set your handle",
                                color = MirrorArt.Accent2,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // status glyph + text (web parity row)
                        if (!profile?.statusEmoji.isNullOrBlank() || !profile?.statusText.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                mirrorStatusGlyphName(profile?.statusEmoji)?.let { glyph ->
                                    MirrorLucideIcon(glyph, tint = MirrorArt.Accent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    profile?.statusText.orEmpty(),
                                    color = MirrorArt.TextSoft,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                                    .clickable(onClick = onEditProfile)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MirrorPhosphorIcon("PEdit", tint = Color(0xFF241304), modifier = Modifier.size(16.dp))
                                Text("Edit profile", color = Color(0xFF241304), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            // Share - glass pill, OS share sheet + clipboard fallback
                            Row(
                                Modifier
                                    .height(40.dp)
                                    .clip(CircleShape)
                                    .background(MirrorArt.Chip)
                                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                                    .clickable {
                                        val handle = profile?.handle
                                        if (handle.isNullOrBlank()) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Claim a handle first - it is how people find you",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                            onEditProfile()
                                        } else {
                                            val text = "Find me on Pulse - @$handle"
                                            runCatching {
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                }
                                                context.startActivity(Intent.createChooser(send, "Share your profile"))
                                            }.onFailure {
                                                mirrorCopyText(context, text, "Share text copied")
                                            }
                                        }
                                    }
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
                            onClick = onOpenSaved,
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
                            onClick = { mirrorCopyText(context, viewerId, "Account ID copied") },
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
                            onClick = { signOutOpen = true },
                        )
                    }
                }
            }
        }

        if (kebabOpen) {
            MirrorProfileMoreMenu(
                onOpenHub = onOpenHub,
                onOpenSettings = onOpenSettings,
                onOpenSaved = onOpenSaved,
                onDismiss = { kebabOpen = false },
            )
        }
        if (signOutOpen) {
            MirrorSignOutDialog(
                onConfirm = {
                    signOutOpen = false
                    onSignOut()
                },
                onDismiss = { signOutOpen = false },
            )
        }
    }
}

/** Cover band: 132dp warm gradient with the fine horizontal ridge texture + kebab + cover pills. */
@Composable
private fun MirrorCoverHeader(
    hasCover: Boolean,
    coverBusy: Boolean,
    onKebab: () -> Unit,
    onAddCover: () -> Unit,
    onRemoveCover: () -> Unit,
) {
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
        // the signal line - hairline bright edge grounding the cover
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xCCFFFFFF), Color.Transparent),
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // corner kebab (three dots) - 40dp glass circle (web ProfileMoreMenu trigger)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.Chip)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .clickable(onClick = onKebab),
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
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color(0x59000000))
                    .border(1.dp, Color(0x40FFFFFF), CircleShape)
                    .clickable(enabled = !coverBusy, onClick = onAddCover)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (coverBusy) {
                    MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(14.dp))
                } else {
                    MirrorPhosphorIcon("PPhoto", tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Text(
                    if (hasCover) "Change" else "Add cover",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (hasCover) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x59000000))
                        .border(1.dp, Color(0x40FFFFFF), CircleShape)
                        .clickable(enabled = !coverBusy, onClick = onRemoveCover)
                        .padding(5.dp),
                ) {
                    MirrorLucideIcon("LTrash2", tint = Color.White, modifier = Modifier.size(13.dp))
                }
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

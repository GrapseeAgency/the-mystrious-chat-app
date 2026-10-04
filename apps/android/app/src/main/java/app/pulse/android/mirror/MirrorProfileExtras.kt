package app.pulse.android.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.Message
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.model.SavedItem
import app.pulse.domain.model.UserProfile
import app.pulse.domain.repository.PulseRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R71 - the profile surfaces the web renders around the Profile tab
 * (profile-tab.tsx): the corner kebab dropdown (Hub / Settings / Saved
 * messages), the real saved-messages library drawer, the full Edit-profile
 * sub-page (real PATCH /api/users/:id + handle check) and the sign-out
 * confirm. Every action rides the gateway - zero dead controls.
 */

// Profile corner kebab (three-dot) menu

/** Profile kebab row: Hub / Settings / Saved messages (web ProfileMoreMenu). */
@Composable
internal fun MirrorProfileMoreMenu(
    onOpenHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 62.dp, end = 12.dp)
                .widthIn(min = 232.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(6.dp)
                .clickable(enabled = false) {},
        ) {
            MirrorProfileMoreItem("PHub", "Hub") {
                onDismiss()
                onOpenHub()
            }
            MirrorProfileMoreItem("PGearSix", "Settings") {
                onDismiss()
                onOpenSettings()
            }
            Spacer(
                Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(MirrorArt.Hairline),
            )
            MirrorProfileMoreItem("PStar", "Saved messages") {
                onDismiss()
                onOpenSaved()
            }
        }
    }
}

@Composable
private fun MirrorProfileMoreItem(glyph: String, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorPhosphorIcon(glyph, tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
        Text(label, color = MirrorArt.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

// Saved messages library (real flow)

/** The starred-message library: GET /api/users/:id/saved via the repository cache. */
@Composable
internal fun MirrorSavedSheet(
    repository: PulseRepository,
    viewerId: String,
    onOpenConversation: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<SavedItem>?>(null) }
    LaunchedEffect(viewerId) {
        items = runCatching { repository.refreshSavedLibrary() }.getOrNull()
    }
    val list = items ?: emptyList()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MirrorPhosphorIcon("PStar", tint = MirrorArt.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        items == null -> "Loading"
                        list.size == 1 -> "1 saved message"
                        else -> "${list.size} saved messages"
                    },
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (items == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MirrorArt.Chip),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MirrorArt.Chip),
                )
            } else if (list.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x14E5A33C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorPhosphorIcon("PStar", tint = MirrorArt.Accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Long-press a message in any chat and choose Save message.",
                        color = MirrorArt.Dim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (item in list) {
                        MirrorSavedRow(
                            item = item,
                            viewerId = viewerId,
                            onPress = {
                                onDismiss()
                                onOpenConversation(item.conversationId, item.message.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorSavedRow(item: SavedItem, viewerId: String, onPress: () -> Unit) {
    val senderIsMe = item.message.authorId == viewerId
    val preview = when {
        item.message.isDeleted -> "Message deleted"
        item.message.imagePath != null -> "(photo)"
        item.message.audioPath != null -> "(voice message)"
        else -> item.message.body.replace(Regex("\\s+"), " ").trim()
    }
    val date = formatSavedStamp(item.savedAt)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MirrorArt.Chip)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onPress)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MirrorAvatar(
                name = item.message.authorName,
                color = item.message.senderColor,
                isGroup = false,
                groupId = "",
                online = false,
                showPresence = false,
                sizeDp = 22,
                cornerDp = 11,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (senderIsMe) "You" else item.message.authorName,
                color = MirrorArt.Accent2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                " in ${item.conversationName ?: "chat"}",
                color = MirrorArt.Faint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(date, color = MirrorArt.Faint, fontSize = 10.sp)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            if (item.message.imagePath != null) {
                MirrorPhosphorIcon("PPhoto", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            if (item.message.audioPath != null) {
                MirrorLucideIcon("LMic", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                preview.ifBlank { "(media)" },
                color = MirrorArt.TextSoft,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatSavedStamp(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val zoned = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format(zoned)
}

// Edit profile sub-page

/** Web STATUS_GLYPH_CHOICES: id + label + lucide glyph, verbatim. */
private val STATUS_CHOICES = listOf(
    Triple("flame", "On fire", "LFlame"),
    Triple("sparkles", "Sparkles", "LSparkles"),
    Triple("target", "Focused", "LTarget"),
    Triple("coffee", "Coffee break", "LCoffee"),
    Triple("headphones", "Listening", "LHeadphones"),
    Triple("moon", "Night owl", "LMoon"),
    Triple("bulb", "Ideas", "LLightbulb"),
    Triple("rocket", "Shipping", "LRocket"),
    Triple("sleep", "Sleeping", "LBedDouble"),
    Triple("food", "Eating", "LUtensils"),
    Triple("vacation", "On vacation", "LPlane"),
)

/** Stored status id -> lucide glyph (status-glyph.tsx GLYPH_BY_ID parity). */
internal fun mirrorStatusGlyphName(value: String?): String? = when (value) {
    "flame" -> "LFlame"
    "sparkles" -> "LSparkles"
    "target" -> "LTarget"
    "coffee" -> "LCoffee"
    "headphones" -> "LHeadphones"
    "moon" -> "LMoon"
    "bulb" -> "LLightbulb"
    "rocket" -> "LRocket"
    "sleep" -> "LBedDouble"
    "food" -> "LUtensils"
    "vacation" -> "LPlane"
    else -> null
}

private val AVATAR_COLORS = listOf("emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan")

private const val EDIT_NAME_MAX = 32
private const val EDIT_ABOUT_MAX = 140
private const val EDIT_STATUS_MAX = 48

@Composable
internal fun MirrorEditProfilePage(
    repository: PulseRepository,
    viewerId: String,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("emerald") }
    var statusGlyph by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var handleOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewerId) {
        val loaded = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
        profile = loaded
        name = loaded?.name ?: ""
        about = loaded?.about ?: ""
        color = loaded?.color ?: "emerald"
        statusGlyph = loaded?.statusEmoji ?: ""
        statusText = loaded?.statusText ?: ""
    }

    val base = profile
    val dirty = base != null && (
        name.trim() != base.name.trim() ||
            about.trim() != (base.about ?: "").trim() ||
            color != (base.color ?: "emerald") ||
            statusGlyph.trim() != (base.statusEmoji ?: "") ||
            statusText.trim() != (base.statusText ?: "")
        )
    val canSave = dirty && name.trim().isNotBlank() && !saving

    fun save() {
        if (!canSave) return
        saving = true
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                repository.patchProfile(
                    ProfilePatch(
                        name = name.trim(),
                        about = about.trim(),
                        color = color,
                        statusEmoji = statusGlyph.trim(),
                        statusText = statusText.trim(),
                    ),
                )
            }
            withContext(Dispatchers.Main) {
                saving = false
                if (result.isSuccess) {
                    android.widget.Toast.makeText(context, "Profile updated", android.widget.Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        result.exceptionOrNull()?.message ?: "Could not save your profile",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // Avatar photo pick: compress -> POST /api/uploads -> PATCH avatar (web AvatarPhotoEditor).
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val dataUrl = mirrorProfileUriToDataUrl(context, uri, maxSide = 640)
                    val path = repository.uploadMedia(dataUrl).getOrThrow()
                    repository.patchProfile(ProfilePatch(avatar = path))
                    withContext(Dispatchers.Main) {
                        profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
                        android.widget.Toast.makeText(context, "Profile photo updated", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .statusBarsPadding()
            .imePadding(),
    ) {
        // sub-header: back pill + title + Save (when dirty)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(SubPageInk.GlassPill)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MirrorLucideIcon("LChevronLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(16.dp))
                Text("Profile", color = SubPageInk.Zinc300, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Edit profile",
                color = SubPageInk.Zinc50,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (canSave) MirrorArt.Accent else MirrorArt.Chip)
                        .clickable(enabled = canSave) { save() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MirrorLucideIcon(
                        if (saving) "LLoaderCircle" else "LCheck",
                        tint = if (canSave) Color(0xFF241304) else SubPageInk.Zinc500,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "Save",
                        color = if (canSave) Color(0xFF241304) else SubPageInk.Zinc500,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // avatar editor block
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 8.dp)) {
                    MirrorAvatar(
                        name = if (name.isNotBlank()) name else viewerId,
                        color = color,
                        isGroup = false,
                        groupId = "",
                        online = false,
                        showPresence = false,
                        sizeDp = 84,
                        cornerDp = 42,
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Accent)
                            .clickable {
                                pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorPhosphorIcon("PPhoto", tint = Color(0xFF241304), modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    "Tap the camera to change your profile photo",
                    color = SubPageInk.Zinc600,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            EditGroup("Identity") {
                EditFieldLabel("Display name")
                EditInput(value = name, max = EDIT_NAME_MAX, singleLine = true) {
                    name = it
                }
                Spacer(Modifier.height(12.dp))
                EditFieldLabel("Bio")
                EditInput(
                    value = about,
                    max = EDIT_ABOUT_MAX,
                    singleLine = false,
                    placeholder = "Hey there! I'm using Pulse.",
                ) {
                    about = it
                }
            }

            EditGroup("Status") {
                // 11 glyph choices in the web's 6 + 5 wrap (radiogroup parity).
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in STATUS_CHOICES.take(6)) {
                            StatusChip(
                                choice = choice,
                                selected = statusGlyph == choice.first,
                                modifier = Modifier.weight(1f),
                                onToggle = { statusGlyph = if (statusGlyph == choice.first) "" else choice.first },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in STATUS_CHOICES.drop(6)) {
                            StatusChip(
                                choice = choice,
                                selected = statusGlyph == choice.first,
                                modifier = Modifier.weight(1f),
                                onToggle = { statusGlyph = if (statusGlyph == choice.first) "" else choice.first },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                EditInput(
                    value = statusText,
                    max = EDIT_STATUS_MAX,
                    singleLine = true,
                    placeholder = "What's happening? (optional)",
                ) {
                    statusText = it
                }
            }

            EditGroup("Avatar") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (c in AVATAR_COLORS) {
                        val selected = color == c
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Brush.verticalGradient(MirrorArt.avatarGradient(c)))
                                .border(
                                    2.dp,
                                    if (selected) MirrorArt.Accent else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            EditGroup("Handle") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { handleOpen = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x14E5A33C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LAtSign", tint = SubPageInk.Amber400, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Your handle", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!profile?.handle.isNullOrBlank()) "@${profile?.handle}" else "Claim yours - friends can find you by it",
                            color = SubPageInk.Zinc500,
                            fontSize = 12.sp,
                        )
                    }
                    MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (canSave) MirrorArt.Accent else MirrorArt.Chip)
                    .clickable(enabled = canSave) { save() }
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save changes",
                    color = if (canSave) Color(0xFF241304) else SubPageInk.Zinc500,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Changes appear everywhere instantly - profile, chats and mentions.",
                color = SubPageInk.Zinc600,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
    }

    if (handleOpen) {
        MirrorHandleDialog(
            repository = repository,
            current = profile?.handle.orEmpty(),
            onDismiss = { handleOpen = false },
            onSaved = {
                handleOpen = false
                CoroutineScope(Dispatchers.IO).launch {
                    profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
                }
            },
        )
    }
}

@Composable
private fun StatusChip(
    choice: Triple<String, String, String>,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x29FF7A3D) else MirrorArt.Chip)
            .border(
                1.dp,
                if (selected) MirrorArt.Accent else MirrorArt.Hairline,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(
            choice.third,
            tint = if (selected) MirrorArt.Accent2 else MirrorArt.Dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun EditGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(
            title.uppercase(),
            color = SubPageInk.Zinc500,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(20.dp))
                .padding(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun EditFieldLabel(text: String) {
    Text(
        text,
        color = SubPageInk.Zinc400,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 5.dp),
    )
}

@Composable
private fun EditInput(
    value: String,
    max: Int,
    singleLine: Boolean,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.take(max)) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        textStyle = TextStyle(color = SubPageInk.Zinc50, fontSize = 14.sp, lineHeight = 19.sp),
        cursorBrush = SolidColor(MirrorArt.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isBlank() && placeholder.isNotBlank()) {
                    Text(placeholder, color = SubPageInk.Zinc600, fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

/** Claim-a-handle dialog: real checkHandle + patchProfile(username). */
@Composable
private fun MirrorHandleDialog(
    repository: PulseRepository,
    current: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var handle by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("Your handle", color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Friends can find you by it. Letters, numbers and underscores.",
                color = MirrorArt.Dim,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("@", color = MirrorArt.Accent2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = handle,
                    onValueChange = { raw ->
                        handle = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(24)
                        error = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(MirrorArt.Accent),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (handle.isBlank()) Text("yourname", color = MirrorArt.Faint, fontSize = 14.sp)
                            inner()
                        }
                    },
                )
            }
            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = MirrorArt.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (handle.isNotBlank() && !busy) MirrorArt.Accent else MirrorArt.Chip)
                        .clickable(enabled = handle.isNotBlank() && !busy) {
                            busy = true
                            CoroutineScope(Dispatchers.IO).launch {
                                val check = runCatching { repository.checkHandle(handle) }.getOrNull()
                                val available = check?.getOrNull()?.available == true
                                if (!available) {
                                    withContext(Dispatchers.Main) {
                                        busy = false
                                        error = "That handle is taken - try another."
                                    }
                                    return@launch
                                }
                                val saved = runCatching {
                                    repository.patchProfile(ProfilePatch(username = handle))
                                }.isSuccess
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    if (saved) {
                                        android.widget.Toast.makeText(context, "Handle saved", android.widget.Toast.LENGTH_SHORT).show()
                                        onSaved()
                                    } else {
                                        error = "Could not save the handle - try again."
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "Checking" else "Save",
                        color = if (handle.isNotBlank() && !busy) Color(0xFF241304) else MirrorArt.Dim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// Sign-out confirm

/** Web AlertDialog parity: "Sign out of this account?" - destructive confirm. */
@Composable
internal fun MirrorSignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("Sign out of this account?", color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "You'll return to the welcome screen. Nothing is deleted - you can always create or join back in later.",
                color = MirrorArt.Dim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.Red)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Sign out", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Compress the picked image to a base64 data URL (cover/avatar size budget). */
internal suspend fun mirrorProfileUriToDataUrl(context: Context, uri: Uri, maxSide: Int): String =
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("Could not decode the image")
        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxSide) {
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$base64"
    }

package app.pulse.android.mirror

import android.content.Context
import android.net.Uri
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
                // web ProfileMoreMenu GlassMenu w-[232px] - fixed card width,
                // the fillMaxWidth rows must not stretch the menu edge to edge.
                .width(232.dp)
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
    val epoch = repository.attachmentSessionKey()
    val account = remember(repository, epoch) { repository.boundToCurrentAccount() }
    val syncStatusFlow = remember(account) { account.observeSavedSyncStatus() }
    val syncStatus by syncStatusFlow.collectAsStateWithLifecycle(initialValue = null)
    var cachedPage by remember(epoch) { mutableStateOf(false) }
    var cursor by remember(epoch) { mutableStateOf<String?>(null) }
    var next by remember(epoch) { mutableStateOf<String?>(null) }
    var previous by remember(epoch) { mutableStateOf(emptyList<String?>()) }
    var selected by remember(epoch) { mutableStateOf<List<String>?>(null) }
    val libraryFlow = remember(account, selected) { account.observeSavedPage(selected) }
    val list by libraryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var loading by remember(epoch) { mutableStateOf(true) }
    var error by remember(epoch) { mutableStateOf<String?>(null) }
    var retry by remember(epoch) { mutableStateOf(0) }
    var loadGeneration by remember(epoch) { mutableStateOf(0L) }
    var refreshAfterLoad by remember(epoch) { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(account, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                if (cursor == null && !loading) retry++
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
    LaunchedEffect(account) {
        account.events().collect { event ->
            if (event is app.pulse.domain.repository.PulseEvent.PrivateFeedsChanged) {
                loadGeneration++; selected = emptyList(); retry++
            }
            if (event is app.pulse.domain.repository.PulseEvent.SavedChanged && cursor == null) {
                if (loading) refreshAfterLoad = true else retry++
            }
        }
    }
    LaunchedEffect(repository, viewerId, retry, cursor, epoch) {
        val ticket = ++loadGeneration
        loading = true
        error = null
        try {
            val page = account.savedLibraryPage(cursor).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repository.attachmentSessionKey() != epoch || ticket != loadGeneration) return@LaunchedEffect
            selected = page.items.map { it.message.id }; next = page.nextCursor; cachedPage = page.cached
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (repository.attachmentSessionKey() == epoch && ticket == loadGeneration) {
                if ((failure as? app.pulse.domain.model.PulseApiException)?.status in listOf(401, 403, 404)) selected = emptyList()
                error = failure.message ?: "Saved messages could not be refreshed"
            }
        } finally {
            if (repository.attachmentSessionKey() == epoch && ticket == loadGeneration) {
                loading = false
                if (refreshAfterLoad) { refreshAfterLoad = false; retry++ }
            }
        }
    }

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
                        loading && list.isEmpty() -> "Loading"
                        list.size == 1 -> "1 saved message"
                        else -> "${list.size} saved messages"
                    },
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            syncStatus?.let { Text(it, color = MirrorArt.Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            if (cachedPage) Text("Cached saved messages · reopen to refresh online", color = MirrorArt.Dim, fontSize = 12.sp)
            app.pulse.feature.chat.FeedPageButtons(previous.isNotEmpty(), next != null, loading,
                onPrevious = { if (loading || previous.isEmpty()) return@FeedPageButtons; loading = true; selected = emptyList(); cursor = previous.last(); previous = previous.dropLast(1) },
                onNext = { if (loading) return@FeedPageButtons; next?.let { value -> loading = true; selected = emptyList(); previous = previous + listOf(cursor); cursor = value } })
            if (error != null) {
                androidx.compose.material3.TextButton(onClick = { retry++ }) {
                    Text("Could not refresh · Retry", color = MirrorArt.Dim)
                }
            }
            if (loading && list.isEmpty()) {
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
                        error ?: "Long-press a message in any chat and choose Save message.",
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

internal fun clipProfileText(text: String, maximum: Int): String {
    val limit = maximum.coerceAtLeast(0)
    if (text.length <= limit) return text
    if (limit == 0) return ""
    val breaks = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    breaks.setText(text)
    return text.substring(0, breaks.preceding(limit + 1).coerceAtLeast(0))
}

internal data class MirrorProfileDraft(val name: String, val about: String, val color: String, val statusGlyph: String, val statusText: String) {
    fun patch(base: UserProfile) = ProfilePatch(
        name = name.trim().takeIf { it != base.name.trim() },
        about = about.trim().takeIf { it != base.about.orEmpty().trim() },
        color = color.takeIf { it != (base.color ?: "emerald") },
        statusEmoji = statusGlyph.trim().takeIf { it != base.statusEmoji.orEmpty() },
        statusText = statusText.trim().takeIf { it != base.statusText.orEmpty() })

    fun confirmedBaseline(base: UserProfile, confirmed: UserProfile): UserProfile {
        require(base.id == confirmed.id) { "Profile response did not match this account" }
        val sent = patch(base)
        return base.copy(name = if (sent.name != null) confirmed.name else base.name,
            about = if (sent.about != null) confirmed.about else base.about,
            color = if (sent.color != null) confirmed.color else base.color,
            statusEmoji = if (sent.statusEmoji != null) confirmed.statusEmoji else base.statusEmoji,
            statusText = if (sent.statusText != null) confirmed.statusText else base.statusText)
    }
}

@Composable
internal fun MirrorEditProfilePage(
    repository: PulseRepository,
    viewerId: String,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
    onProfileConfirmed: (UserProfile, app.pulse.domain.model.ProfilePatch) -> Unit = { _, _ -> },
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

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val gate = remember(repository, viewerId) { app.pulse.domain.sync.RoomControlGate { repository.attachmentSessionKey() } }
    var pendingPhoto by remember(gate) { mutableStateOf<app.pulse.domain.sync.RoomControlGate.Ticket?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadAttempt by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(gate) { onDispose { gate.invalidate() } }
    fun form() = MirrorProfileDraft(name, about, color, statusGlyph, statusText)

    LaunchedEffect(viewerId, loadAttempt) {
        val ticket = gate.beginRead() ?: return@LaunchedEffect
        loading = true
        try {
            val loaded = repository.userProfile(viewerId).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!gate.isCurrent(ticket)) return@LaunchedEffect
            check(loaded.id == viewerId) { "Profile response did not match this account" }
            profile = loaded
            name = loaded.name; about = loaded.about.orEmpty(); color = loaded.color ?: "emerald"
            statusGlyph = loaded.statusEmoji.orEmpty(); statusText = loaded.statusText.orEmpty()
            notice = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
            if (gate.isCurrent(ticket)) notice = error.message ?: "Could not load the profile"
        } finally { if (gate.isCurrent(ticket)) loading = false }
    }

    val base = profile
    val dirty = base != null && (
        name.trim() != base.name.trim() ||
            about.trim() != (base.about ?: "").trim() ||
            color != (base.color ?: "emerald") ||
            statusGlyph.trim() != (base.statusEmoji ?: "") ||
            statusText.trim() != (base.statusText ?: "")
        )
    val canSave = dirty && name.trim().isNotBlank() && !saving && !photoBusy && !loading && !handleOpen

    fun save() {
        if (!canSave) return
        val original = profile ?: return
        val ticket = gate.beginWrite() ?: return
        val submitted = form()
        val patch = submitted.patch(original)
        saving = true
        scope.launch {
            try {
                if (!gate.isCurrent(ticket)) return@launch
                val confirmed = repository.patchProfile(patch).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!gate.isCurrent(ticket)) return@launch
                check(confirmed.id == viewerId) { "Profile response did not match this account" }
                profile = submitted.confirmedBaseline(original, confirmed)
                onProfileConfirmed(confirmed, patch)
                if (form() == submitted) onSaved()
                else notice = "Submitted changes saved. Your newer edits are still here."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (gate.isCurrent(ticket)) notice = error.message ?: "Could not confirm the profile update"
            } finally { if (gate.isCurrent(ticket)) { gate.finishWrite(ticket); saving = false } }
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val ticket = pendingPhoto; pendingPhoto = null
        if (ticket != null && gate.isCurrent(ticket)) {
            if (uri == null) { gate.finishWrite(ticket); photoBusy = false }
            else scope.launch {
                try {
                    val dataUrl = mirrorProfileUriToDataUrl(context, uri)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!gate.isCurrent(ticket)) return@launch
                    val path = repository.uploadMedia(dataUrl).getOrThrow()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!gate.isCurrent(ticket)) return@launch
                    val confirmed = repository.patchProfile(ProfilePatch(avatar = path)).getOrThrow()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!gate.isCurrent(ticket)) return@launch
                    check(confirmed.id == viewerId) { "Profile response did not match this account" }
                    profile = profile?.copy(avatar = confirmed.avatar)
                    notice = "Profile photo updated"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                    if (gate.isCurrent(ticket)) notice = error.message ?: "Could not confirm the photo update"
                } finally { if (gate.isCurrent(ticket)) { gate.finishWrite(ticket); photoBusy = false } }
            }
        } else if (uri != null) notice = "Selection was interrupted. Choose the photo again."
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
            notice?.let { Text(it, color = MirrorArt.Accent2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            if (profile == null) {
                Text(if (loading) "Loading profile…" else "Tap to retry loading your profile", color = MirrorArt.Dim,
                    modifier = Modifier.clickable(enabled = !loading) { loadAttempt++ }.padding(vertical = 16.dp))
                return@Column
            }
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
                        photo = profile?.avatar,
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Accent)
                            .clickable(enabled = !saving && !photoBusy && !handleOpen) {
                                gate.beginWrite()?.let { ticket ->
                                    pendingPhoto = ticket; photoBusy = true
                                    try { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                    catch (_: Exception) { pendingPhoto = null; photoBusy = false; gate.finishWrite(ticket); notice = "Photo picker is unavailable" }
                                }
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
                        .clickable(enabled = !saving && !photoBusy) { handleOpen = true }
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
                "Confirmed changes appear in your profile, chats and mentions.",
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
                val ticket = gate.beginRead()
                if (ticket != null) scope.launch {
                    try {
                        val fresh = repository.userProfile(viewerId).getOrThrow()
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (gate.isCurrent(ticket) && fresh.id == viewerId) profile = profile?.copy(handle = fresh.handle)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                        if (gate.isCurrent(ticket)) notice = error.message ?: "Could not refresh the confirmed handle"
                    }
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
        onValueChange = { raw -> onChange(clipProfileText(raw, max)) },
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val owner = remember(repository) { repository.attachmentSessionKey() }
    fun owned() = owner != null && repository.attachmentSessionKey() == owner

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
                            val candidate = handle.trim()
                            scope.launch {
                                try {
                                    if (!owned()) return@launch
                                    val verdict = repository.checkHandle(candidate).getOrThrow()
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    if (!owned()) return@launch
                                    check(verdict.available || candidate == current) { "That handle is taken - try another." }
                                    repository.patchProfile(ProfilePatch(username = candidate)).getOrThrow()
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    if (owned()) onSaved()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                                    if (owned()) error = failure.message ?: "Could not confirm the handle update"
                                } finally { if (owned()) busy = false }
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
internal suspend fun mirrorProfileUriToDataUrl(context: Context, uri: Uri): String {
    require(uri.scheme == "content") { "Choose a photo from the system picker" }
    return app.pulse.ui.media.ImagePreparation.imageToDataUrl(context, uri).getOrThrow()
}

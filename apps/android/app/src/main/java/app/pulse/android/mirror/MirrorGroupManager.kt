package app.pulse.android.mirror

// R70-b - the web's CLASSIC group manager (src/components/chat/group-info-sheet.tsx)
// + the full leaderboard drawer (leaderboard-sheet.tsx), rebuilt 1:1 as native
// Compose over the REAL PulseRepository gateway:
//   - hero gradient card (initials avatar, member/admin counts, announcements
//     badge + explainer for broadcast rooms),
//   - action tiles (admin Add member / Rename, always Leave group),
//   - Webhooks section (list + copy URL + admin create/delete on the real
//     /api/webhooks contract, honest loading/error/empty states),
//   - Leaderboard section (top-3 preview + full drawer with XP bars and
//     per-row mini-stats, 20s refresh while open),
//   - Tournament section (season list + running/finished pills + launcher for
//     the existing MirrorTournamentSheet surface),
//   - Members card (admins first, presence, @handle enrichment, kebab with
//     promote/demote/remove) and the rename/add/leave/promote/demote/remove
//     confirm dialogs,
//   - DM fallback body (About card) per the web DmInfoBody.
// All copy is verbatim web truth; every repository call rides Dispatchers.IO
// and UI state flips on Main. Version frozen surfaces (release APK) untouched.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.GroupLeave
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.User
import app.pulse.domain.model.Webhook
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.LeaderboardRowDto
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Private ink - mirrors the shared artboard tokens without touching MirrorArt.
private object MgrInk {
    val Glass = Color(0x14FFFFFF) // glass-deep card fill
    val GlassTile = Color(0x0FFFFFFF) // inner row fill
    val Press = Color(0x12FFFFFF) // white 7% press tint
    val AmberSoft = Color(0x1AFFB86B) // amber 10% pill fill
    val Rose = Color(0xFFF8717B) // rose-400 copy
    val RoseStrong = Color(0xFFF43F5E) // rose-500 destructive
    val Rose10 = Color(0x1AF43F5E) // rose-500/10 fills
    val Bronze = Color(0xFFFFB454) // leaderboard rank 1
    val Orange = Color(0xFFFB923C) // leaderboard rank 3
    val Panel = Color(0xFF1C1610) // modal card fill
    val Scrim = Color(0x80000000) // dialog scrim
    val White15 = Color(0x26FFFFFF)
    val White25 = Color(0x40FFFFFF)
    val White40 = Color(0x66FFFFFF)
    val White85 = Color(0xD9FFFFFF)
    val White75 = Color(0xBFFFFFFF)
    val Black10 = Color(0x1A000000)
    val Black25 = Color(0x40000000)
}

private val MGR_HM = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val MGR_DAY_MONTH = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** web formatListStamp (pulse-utils.ts), en-US truth. */
private fun mgrStamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val at = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()) }.getOrNull() ?: return ""
    val now = ZonedDateTime.now()
    return when (at.toLocalDate()) {
        now.toLocalDate() -> MGR_HM.format(at)
        now.minusDays(1).toLocalDate() -> "Yesterday"
        else -> MGR_DAY_MONTH.format(at)
    }
}

private const val GROUP_NAME_MAX = 48 // web GROUP_NAME_MAX (serializers.ts)
private const val WEBHOOK_NAME_MAX = 32 // web WEBHOOK_NAME_MAX (webhooks route)

/** Modal identity - mirrors the web SheetModalKind discriminator. */
private sealed interface MgrModal {
    data object None : MgrModal
    data object Rename : MgrModal
    data object Add : MgrModal
    data object Leave : MgrModal
    data class Promote(val userId: String) : MgrModal
    data class Demote(val userId: String) : MgrModal
    data class Remove(val userId: String) : MgrModal
}

@Composable
internal fun MirrorGroupManagerSheet(
    conversationId: String,
    viewerId: String,
    viewerName: String,
    repository: PulseRepository,
    presence: Set<String>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    // R72 - the web #/user/:id page (member row tap = openProfileForUser)
    onOpenUser: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // live server truth - groupMeta on open, after every mutation, 30s beat
    var meta by remember { mutableStateOf<GroupMeta?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf<Map<String, User>>(emptyMap()) }
    var modal by remember { mutableStateOf<MgrModal>(MgrModal.None) }
    var memberAction by remember { mutableStateOf<app.pulse.domain.model.ConversationMember?>(null) }
    var note by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    var leaderboardsOpen by remember { mutableStateOf(false) }
    var tournamentOpen by remember { mutableStateOf(false) }

    val conversations by repository.observeConversations("").collectAsState(initial = emptyList<Conversation>())
    val liveConvo = conversations.firstOrNull { it.id == conversationId }
    // groupMeta upserts the Room cache on every refresh, so the members list
    // (roles included) rides the conversations flow - no extra fetch.
    val members = liveConvo?.members ?: emptyList()

    fun refresh(work: suspend () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.groupMeta(conversationId).fold(
                    onSuccess = { m ->
                        meta = m
                        loadError = false
                    },
                    onFailure = { loadError = true },
                )
                repository.users().onSuccess { list ->
                    directory = list.associateBy { it.id }
                }
                work()
            }
            withContext(Dispatchers.Main) { onChanged() }
        }
    }

    LaunchedEffect(conversationId) {
        refresh()
        while (true) {
            delay(30_000)
            refresh()
        }
    }

    // inline notes auto-clear (web toast parity)
    LaunchedEffect(note) {
        if (note.isNotEmpty()) {
            delay(2_500)
            note = ""
        }
    }
    LaunchedEffect(actionError) {
        if (actionError.isNotEmpty()) {
            delay(3_500)
            actionError = ""
        }
    }

    fun act(work: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { work() }
            withContext(Dispatchers.Main) { onChanged() }
            refresh()
        }
    }

    val isGroup = meta?.isGroup ?: (liveConvo?.isGroupish ?: true)
    val isChannel = meta?.broadcastMode ?: (liveConvo?.isChannel ?: false)
    val isAdmin = meta?.isAdmin ?: false
    val title = if (isGroup) {
        liveConvo?.title?.takeIf { it.isNotBlank() } ?: "Group"
    } else {
        otherNameOf(members, viewerId) ?: "Chat"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MirrorArt.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // header - web GroupInfoSheet header
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MgrIconButton("Back to chat", onClick = { onDismiss() }) {
                    MirrorLucideIcon("LChevronLeft", tint = MirrorArt.TextSoft, modifier = Modifier.size(22.dp))
                }
                Text(
                    if (isGroup) "Group info" else "Chat info",
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (meta == null && !loadError) {
                MgrSkeleton()
            } else if (loadError && meta == null) {
                MgrLoadError(onRetry = { refresh() })
            } else if (!isGroup) {
                MgrDmBody(members, directory, viewerId)
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    MgrHero(
                        name = title.ifBlank { "Group" },
                        memberCount = members.size,
                        adminCount = members.count { it.role == "admin" },
                        isChannel = isChannel,
                        gradientId = conversationId,
                    )
                    Spacer(Modifier.height(12.dp))
                    MgrActionTiles(
                        isAdmin = isAdmin,
                        onAdd = { modal = MgrModal.Add },
                        onRename = { modal = MgrModal.Rename },
                        onLeave = { modal = MgrModal.Leave },
                    )
                    Spacer(Modifier.height(12.dp))
                    MgrWebhooksSection(
                        repository = repository,
                        conversationId = conversationId,
                        isAdmin = isAdmin,
                        clipboard = clipboard,
                        onNote = { note = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    MgrLeaderboardSection(
                        repository = repository,
                        conversationId = conversationId,
                        viewerId = viewerId,
                        onOpenFull = { leaderboardsOpen = true },
                    )
                    Spacer(Modifier.height(12.dp))
                    MgrTournamentSection(
                        repository = repository,
                        conversationId = conversationId,
                        onStart = { tournamentOpen = true },
                    )
                    Spacer(Modifier.height(12.dp))
                    MgrMembersCard(
                        members = members,
                        directory = directory,
                        presence = presence,
                        viewerId = viewerId,
                        isAdmin = isAdmin,
                        onManage = { memberAction = it },
                        onOpenUser = onOpenUser,
                    )
                    Spacer(Modifier.height(24.dp))
                    if (note.isNotEmpty()) {
                        Text(
                            note,
                            color = MirrorArt.Accent2,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }

        // confirm + input modals (web SheetModal layer)
        val currentMeta = meta
        if (currentMeta != null && isGroup) {
            when (val m = modal) {
                is MgrModal.Rename -> MgrRenameModal(
                    currentName = title,
                    pending = false,
                    onClose = { modal = MgrModal.None },
                    onConfirm = { name ->
                        scope.launch(Dispatchers.IO) {
                            val result = repository.renameGroup(conversationId, name)
                            withContext(Dispatchers.Main) {
                                result.fold(
                                    onSuccess = {
                                        note = "Group renamed"
                                        modal = MgrModal.None
                                        actionError = ""
                                    },
                                    onFailure = { e -> actionError = e.message ?: "Could not rename the group" },
                                )
                            }
                            refresh()
                        }
                    },
                )
                is MgrModal.Add -> MgrAddModal(
                    repository = repository,
                    conversationId = conversationId,
                    existingIds = members.map { it.id }.toSet(),
                    onClose = { modal = MgrModal.None },
                    onAdded = { count ->
                        scope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.Main) {
                                note = if (count == 1) "1 member added" else "$count members added"
                                modal = MgrModal.None
                            }
                            refresh()
                        }
                    },
                )
                is MgrModal.Leave -> MgrConfirmModal(
                    title = "Leave \"${title.ifBlank { "this group" }}\"?",
                    body = "You'll stop receiving messages. History stays on your device. If you're the last admin, the longest-standing member is promoted automatically.",
                    confirmLabel = "Leave group",
                    destructive = true,
                    pending = false,
                    onClose = { modal = MgrModal.None },
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            val result: Result<GroupLeave> = repository.leaveGroup(conversationId)
                            withContext(Dispatchers.Main) {
                                result.fold(
                                    onSuccess = { left ->
                                        note = left.promotedUserId?.let { promoted ->
                                            val name = members.firstOrNull { it.id == promoted }?.name
                                            if (name != null) "You left the group - $name is now an admin" else "You left the group"
                                        } ?: "You left the group"
                                        modal = MgrModal.None
                                        onDismiss()
                                    },
                                    onFailure = { e -> actionError = e.message ?: "Could not leave the group" },
                                )
                            }
                            refresh()
                        }
                    },
                )
                is MgrModal.Promote -> {
                    val target = members.firstOrNull { it.id == m.userId }
                    MgrConfirmModal(
                        title = "Make ${target?.name ?: "member"} an admin?",
                        body = "Admins can rename the group, add and remove members, and toggle announcement mode.",
                        confirmLabel = "Promote",
                        destructive = false,
                        pending = false,
                        onClose = { modal = MgrModal.None },
                        onConfirm = {
                            modal = MgrModal.None
                            act { repository.setMemberRole(conversationId, m.userId, true) }
                        },
                    )
                }
                is MgrModal.Demote -> {
                    val target = members.firstOrNull { it.id == m.userId }
                    MgrConfirmModal(
                        title = "Demote ${target?.name ?: "member"}?",
                        body = "They lose admin powers. A group always keeps at least one admin.",
                        confirmLabel = "Demote",
                        destructive = false,
                        pending = false,
                        onClose = { modal = MgrModal.None },
                        onConfirm = {
                            modal = MgrModal.None
                            act { repository.setMemberRole(conversationId, m.userId, false) }
                        },
                    )
                }
                is MgrModal.Remove -> {
                    val target = members.firstOrNull { it.id == m.userId }
                    MgrConfirmModal(
                        title = "Remove ${target?.name ?: "member"} from the group?",
                        body = "They lose access to this chat. They can be added back anytime.",
                        confirmLabel = "Remove",
                        destructive = true,
                        pending = false,
                        onClose = { modal = MgrModal.None },
                        onConfirm = {
                            modal = MgrModal.None
                            act { repository.kickMember(conversationId, m.userId) }
                        },
                    )
                }
                MgrModal.None -> Unit
            }
        }
        if (actionError.isNotEmpty()) {
            Text(
                actionError,
                color = MgrInk.Rose,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

        // member kebab actions ride the established sheet chrome (web dropdown parity)
        memberAction?.let { target ->
            val targetIsAdmin = target.role == "admin"
            MirrorSheet(title = "Manage ${target.name}", onDismiss = { memberAction = null }) {
                Column {
                    if (!targetIsAdmin) {
                        MgrSheetAction("LCrown", "Promote to admin", MirrorArt.Accent2) {
                            memberAction = null
                            modal = MgrModal.Promote(target.id)
                        }
                        MgrSheetAction("LLogOut", "Remove from group", MgrInk.RoseStrong) {
                            memberAction = null
                            modal = MgrModal.Remove(target.id)
                        }
                    } else {
                        MgrSheetAction("LUsersRound", "Demote to member", MirrorArt.TextSoft) {
                            memberAction = null
                            modal = MgrModal.Demote(target.id)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // nested sheets
        if (leaderboardsOpen) {
            MgrLeaderboardDrawer(
                repository = repository,
                conversationId = conversationId,
                viewerId = viewerId,
                onDismiss = { leaderboardsOpen = false },
            )
        }
        if (tournamentOpen) {
            MirrorTournamentSheet(
                repository = repository,
                conversationId = conversationId,
                viewerId = viewerId,
                onDismiss = { tournamentOpen = false },
            )
        }
    }
}

private fun otherNameOf(
    members: List<app.pulse.domain.model.ConversationMember>,
    viewerId: String,
): String? {
    val other = members.firstOrNull { it.id != viewerId } ?: members.firstOrNull() ?: return null
    return other.name
}

// hero =====================================================================

@Composable
private fun MgrHero(
    name: String,
    memberCount: Int,
    adminCount: Int,
    isChannel: Boolean,
    gradientId: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(MirrorArt.groupGradient(gradientId)))
            .padding(20.dp),
    ) {
        // decorative blur circles (web -right-10 -top-12 / -bottom-14 -left-8)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(120.dp)
                .clip(CircleShape)
                .background(MgrInk.White15),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(110.dp)
                .clip(CircleShape)
                .background(MgrInk.Black10),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(2.dp, MgrInk.White40, RoundedCornerShape(22.dp))
                    .background(MgrInk.Black10),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    MirrorArt.initials(name),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$memberCount ${if (memberCount == 1) "member" else "members"} · $adminCount ${if (adminCount == 1) "admin" else "admins"}",
                    color = MgrInk.White85,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (isChannel) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .border(1.dp, MgrInk.White25, CircleShape)
                            .background(MgrInk.Black25)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MirrorLucideIcon("LMegaphone", tint = Color.White, modifier = Modifier.size(10.dp))
                        Text(
                            "Announcements only",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                        )
                    }
                }
            }
        }
        if (isChannel) {
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MirrorLucideIcon("LMegaphone", tint = MgrInk.White75, modifier = Modifier.size(13.dp))
                Text(
                    "Only admins can send messages while announcement mode is on.",
                    color = MgrInk.White75,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

// action tiles =============================================================

@Composable
private fun MgrActionTiles(
    isAdmin: Boolean,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isAdmin) {
            MgrTile(label = "Add member", onClick = onAdd, destructive = false) {
                MirrorLucideIcon("LUserRoundPlus", tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp))
            }
            MgrTile(label = "Rename", onClick = onRename, destructive = false) {
                MirrorLucideIcon("LPencilLine", tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp))
            }
        }
        MgrTile(label = "Leave group", onClick = onLeave, destructive = true, wide = !isAdmin) {
            MirrorLucideIcon("LLogOut", tint = MgrInk.RoseStrong, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RowScope.MgrTile(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean,
    wide: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .weight(if (wide) 1.4f else 1f)
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    destructive -> MgrInk.Rose10
                    pressed -> MgrInk.Press
                    else -> MgrInk.GlassTile
                },
            )
            .border(1.dp, if (destructive) Color(0x40F43F5E) else MirrorArt.Hairline, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (destructive) MgrInk.RoseStrong else MirrorArt.TextSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

// webhooks =================================================================

@Composable
private fun MgrWebhooksSection(
    repository: PulseRepository,
    conversationId: String,
    isAdmin: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onNote: (String) -> Unit,
) {
    var webhooks by remember { mutableStateOf<List<Webhook>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Webhook?>(null) }
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val result = repository.webhooks(conversationId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        webhooks = it
                        loadError = false
                    },
                    onFailure = { loadError = true },
                )
            }
        }
    }
    LaunchedEffect(conversationId) { load() }

    Column(Modifier.fillMaxWidth()) {
        MgrSectionHeader(icon = "LWebhook", label = "Webhooks", count = webhooks?.size?.toString() ?: "")
        when {
            webhooks == null && !loadError -> MgrSkeletonRows(rows = 2, circle = true)
            loadError -> MgrInlineError("Could not load webhooks.", onRetry = { load() })
            webhooks!!.isEmpty() -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MirrorLucideIcon("LWebhook", tint = MirrorArt.Faint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text("No webhooks yet", color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (isAdmin) "Create one to let outside services post into this chat." else "Admins can add Discord-style integrations here.",
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                )
            }
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MgrInk.Glass)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(6.dp),
            ) {
                for (webhook in webhooks!!) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MirrorArt.avatarBrush(webhook.avatarColor)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                webhook.name,
                                color = MirrorArt.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "/api/webhooks/${webhook.token} · added ${mgrStamp(webhook.createdAtIso)}".trimEnd(),
                                color = MirrorArt.Faint,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MgrIconButton(
                            "Copy ${webhook.name} webhook URL",
                            onClick = {
                                val url = webhook.url
                                val origin = PulseEndpoints.gatewayHttpUrl.trimEnd('/')
                                val absolute = when {
                                    origin.isBlank() -> url
                                    url.startsWith("http://") || url.startsWith("https://") -> url
                                    else -> origin.removeSuffix("/api") + url
                                }
                                clipboard.setText(AnnotatedString(absolute))
                                onNote("Webhook URL copied")
                            },
                        ) {
                            MirrorLucideIcon("LCopy", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                        }
                        if (isAdmin) {
                            MgrIconButton("Delete ${webhook.name}", onClick = { deleteTarget = webhook }) {
                                MirrorLucideIcon("LTrash2", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        if (isAdmin) {
            Spacer(Modifier.height(8.dp))
            MgrWideButton(label = "Create webhook", leading = "LPlus", onClick = { createOpen = true })
        }
    }

    if (createOpen) {
        MgrCreateWebhookModal(
            pending = pending,
            onClose = { createOpen = false },
            onConfirm = { name ->
                pending = true
                scope.launch(Dispatchers.IO) {
                    val result = repository.createWebhook(conversationId, name)
                    withContext(Dispatchers.Main) {
                        pending = false
                        result.fold(
                            onSuccess = { created ->
                                onNote("Webhook \"${created.name}\" created")
                                createOpen = false
                            },
                            onFailure = { e -> onNote(e.message ?: "Could not create the webhook") },
                        )
                    }
                    load()
                }
            },
        )
    }
    deleteTarget?.let { target ->
        MgrConfirmModal(
            title = "Delete \"${target.name}\"?",
            body = "Its URL stops working immediately. Messages it already posted stay in the chat.",
            confirmLabel = "Delete",
            destructive = true,
            pending = pending,
            onClose = { deleteTarget = null },
            onConfirm = {
                pending = true
                scope.launch(Dispatchers.IO) {
                    val result = repository.deleteWebhook(target.token)
                    withContext(Dispatchers.Main) {
                        pending = false
                        result.fold(
                            onSuccess = {
                                onNote("Webhook deleted")
                                deleteTarget = null
                            },
                            onFailure = { e -> onNote(e.message ?: "Could not delete the webhook") },
                        )
                    }
                    load()
                }
            },
        )
    }
}

@Composable
private fun MgrCreateWebhookModal(
    pending: Boolean,
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= WEBHOOK_NAME_MAX
    MgrModalShell(onClose = onClose) {
        Text("Create webhook", color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Outside services POST to its URL and drop messages into this chat as a named sender.",
            color = MirrorArt.Faint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        MgrTextField(
            value = name,
            onValue = { if (it.length <= WEBHOOK_NAME_MAX) name = it },
            placeholder = "e.g. Deploys · CI · Weather",
        )
        Text(
            "${trimmed.length}/$WEBHOOK_NAME_MAX",
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MgrModalButton(label = "Cancel", destructive = false, enabled = true, pending = false, onClick = onClose, modifier = Modifier.weight(1f))
            MgrModalButton(
                label = "Create",
                destructive = false,
                enabled = valid && !pending,
                pending = pending,
                onClick = { onConfirm(trimmed) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// leaderboard ==============================================================

@Composable
private fun MgrLeaderboardSection(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    onOpenFull: () -> Unit,
) {
    var rows by remember { mutableStateOf<List<LeaderboardRowDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val result = repository.leaderboard(conversationId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        rows = it.rows
                        loadError = false
                    },
                    onFailure = { loadError = true },
                )
            }
        }
    }
    LaunchedEffect(conversationId) { load() }

    Column(Modifier.fillMaxWidth()) {
        MgrSectionHeader(icon = "LBarChart3", label = "Leaderboard", count = rows?.size?.toString() ?: "")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MgrInk.Glass)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            when {
                rows == null && !loadError -> MgrSkeletonRows(rows = 3, circle = true)
                loadError -> Text(
                    "Could not load the leaderboard.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                rows!!.isEmpty() -> Text(
                    "No ranked members yet.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                else -> for ((index, row) in rows!!.take(3).withIndex()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            color = when (index) {
                                0 -> MgrInk.Bronze
                                1 -> MirrorArt.Dim
                                else -> MgrInk.Orange
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(24.dp),
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MirrorArt.avatarBrush(row.color)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                row.name.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            row.name + if (row.userId == viewerId) " · you" else "",
                            color = MirrorArt.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${row.xp} XP",
                            color = MirrorArt.Accent2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MgrWideButton(label = "View full leaderboard", leading = "LBarChart3", onClick = onOpenFull)
    }
}

/** web leaderboard-sheet.tsx - the full drawer with XP bars + mini-stats. */
@Composable
private fun MgrLeaderboardDrawer(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<LeaderboardPageDto?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val result = repository.leaderboard(conversationId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        page = it
                        loadError = false
                    },
                    onFailure = { loadError = true },
                )
            }
        }
    }
    LaunchedEffect(conversationId) {
        load()
        while (true) {
            delay(20_000)
            load()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MgrInk.Scrim)
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LTrophy", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Leaderboard", color = MirrorArt.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Points - wins - XP · live every 20s", color = MirrorArt.Faint, fontSize = 11.sp)
            }
            MgrIconButton("Close leaderboard", onClick = onDismiss) {
                MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        val rows = page?.rows
        when {
            page == null && !loadError -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Faint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ranking the room…", color = MirrorArt.Faint, fontSize = 13.sp)
            }
            loadError -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load the leaderboard.", color = MirrorArt.TextSoft, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                MgrPillButton(label = "Try again", amber = false) { load() }
            }
            rows != null && rows.isEmpty() -> Text(
                "No members to rank yet.",
                color = MirrorArt.Faint,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                textAlign = TextAlign.Center,
            )
            rows != null -> {
                val maxXp = rows!!.maxOfOrNull { it.xp } ?: 0L
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rows!!.forEachIndexed { index, row ->
                        MgrLeaderboardRow(row, index, maxXp, viewerId)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun MgrLeaderboardRow(row: LeaderboardRowDto, index: Int, maxXp: Long, viewerId: String) {
    val isMe = row.userId == viewerId
    val pct = if (maxXp > 0) ((row.xp * 100L) / maxXp).toInt().coerceIn(4, 100) else 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isMe) MgrInk.AmberSoft else MgrInk.Glass)
            .border(1.dp, if (isMe) Color(0x99FFB454) else MirrorArt.Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (index < 3) "${index + 1}" else "${index + 1}",
                color = when (index) {
                    0 -> MgrInk.Bronze
                    1 -> MirrorArt.Dim
                    2 -> MgrInk.Orange
                    else -> MirrorArt.Faint
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.avatarBrush(row.color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.name.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        row.name,
                        color = MirrorArt.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isMe) {
                        Text(
                            "You",
                            color = MirrorArt.Accent2,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x33FFB454))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${row.xp}", color = MirrorArt.Accent2, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text("XP", color = MirrorArt.Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${row.messageCount} msg · ${row.gameWins} win${if (row.gameWins == 1L) "" else "s"} · ${row.tournamentPoints} pt${if (row.tournamentPoints == 1L) "" else "s"}",
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 34.dp),
        )
    }
}

// tournaments ==============================================================

@Composable
private fun MgrTournamentSection(
    repository: PulseRepository,
    conversationId: String,
    onStart: () -> Unit,
) {
    var seasons by remember { mutableStateOf<List<app.pulse.protocol.TournamentSummaryDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val result = repository.tournaments(conversationId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        seasons = it.tournaments
                        loadError = false
                    },
                    onFailure = { loadError = true },
                )
            }
        }
    }
    LaunchedEffect(conversationId) { load() }

    Column(Modifier.fillMaxWidth()) {
        MgrSectionHeader(icon = "LTrophy", label = "Tournament", count = seasons?.size?.toString() ?: "")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MgrInk.Glass)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            when {
                seasons == null && !loadError -> MgrSkeletonRows(rows = 2, circle = true)
                loadError -> Text(
                    "Could not load tournaments.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                seasons!!.isEmpty() -> Text(
                    "No seasons yet - start one below.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
                else -> for (season in seasons!!.take(5)) {
                    val running = season.status == "running"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LTrophy", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                season.name,
                                color = MirrorArt.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Tic-tac-toe · ${season.playerCount ?: 0} ${if ((season.playerCount ?: 0) == 1) "player" else "players"} · ${mgrStamp(season.createdAt)}".trimEnd(),
                                color = MirrorArt.Faint,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(if (running) MgrInk.AmberSoft else Color(0x1A9B8C7B))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (running) {
                                val pulse = rememberInfiniteTransition(label = "seasonPulse")
                                val dot by pulse.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.35f,
                                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                                    label = "seasonDot",
                                )
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .alpha(dot)
                                        .clip(CircleShape)
                                        .background(MirrorArt.Accent),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MirrorArt.Dim),
                                )
                            }
                            Text(
                                if (running) "Running" else "Finished",
                                color = if (running) MirrorArt.Accent2 else MirrorArt.Dim,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MgrWideButton(label = "Start tournament", leading = "LPlus", onClick = onStart)
    }
}

// members ==================================================================

@Composable
private fun MgrMembersCard(
    members: List<app.pulse.domain.model.ConversationMember>,
    directory: Map<String, User>,
    presence: Set<String>,
    viewerId: String,
    isAdmin: Boolean,
    onManage: (app.pulse.domain.model.ConversationMember) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val sorted = remember(members) {
        members.sortedWith(
            compareBy<app.pulse.domain.model.ConversationMember> { if (it.role == "admin") 0 else 1 }
                .thenBy { it.name.lowercase(Locale.US) },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Members",
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f),
            )
            Text("${members.size}", color = MirrorArt.Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MgrInk.Glass)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (sorted.isEmpty()) {
                Text(
                    "No members yet.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            for (member in sorted) {
                val isMe = member.id == viewerId
                val user = directory[member.id]
                MgrMemberRow(
                    member = member,
                    handle = user?.handle?.takeIf { it.isNotBlank() },
                    lastSeenIso = user?.lastSeen,
                    isMe = isMe,
                    online = presence.contains(member.id),
                    isAdmin = isAdmin,
                    isTargetAdmin = member.role == "admin",
                    onOpenUser = { onOpenUser(member.id) },
                    onManage = { onManage(member) },
                )
            }
        }
    }
}

@Composable
private fun MgrMemberRow(
    member: app.pulse.domain.model.ConversationMember,
    handle: String?,
    lastSeenIso: String?,
    isMe: Boolean,
    online: Boolean,
    isAdmin: Boolean,
    isTargetAdmin: Boolean,
    onOpenUser: () -> Unit,
    onManage: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenUser)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorAvatar(
            name = member.name,
            color = member.color,
            isGroup = false,
            groupId = "",
            online = online,
            showPresence = true,
            sizeDp = 44,
            cornerDp = 22,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    member.name,
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isMe) {
                    Text("you", color = MirrorArt.Accent2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                if (isTargetAdmin) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MgrInk.AmberSoft)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MirrorLucideIcon("LCrown", tint = MirrorArt.Accent2, modifier = Modifier.size(10.dp))
                        Text(
                            "Admin",
                            color = MirrorArt.Accent2,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!handle.isNullOrBlank()) {
                    MirrorLucideIcon("LAtSign", tint = MirrorArt.Faint, modifier = Modifier.size(11.dp))
                    Text(handle, color = MirrorArt.Faint, fontSize = 11.sp)
                    Text("·", color = Color(0x33FFFFFF), fontSize = 11.sp)
                }
                Text(
                    if (lastSeenIso.isNullOrBlank()) {
                        "Last seen hidden"
                    } else {
                        "active ${mgrStamp(lastSeenIso)}"
                    },
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                )
            }
        }
        if (isAdmin && !isMe) {
            MgrIconButton("Manage ${member.name}", onClick = onManage) {
                MirrorLucideIcon("LKebab", tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MgrSheetAction(icon: String, label: String, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorLucideIcon(icon, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, color = if (tint == MgrInk.RoseStrong) tint else MirrorArt.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

// DM body ==================================================================

@Composable
private fun MgrDmBody(
    members: List<app.pulse.domain.model.ConversationMember>,
    directory: Map<String, User>,
    viewerId: String,
) {
    val other = members.firstOrNull { it.id != viewerId } ?: members.firstOrNull()
    if (other == null) {
        Text(
            "This chat has no other members yet.",
            color = MirrorArt.TextSoft,
            fontSize = 13.sp,
            modifier = Modifier.padding(32.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    val user = directory[other.id]
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(MirrorArt.avatarGradient(other.color)))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MirrorAvatar(
                    name = other.name,
                    color = other.color,
                    isGroup = false,
                    groupId = "",
                    online = false,
                    showPresence = false,
                    sizeDp = 72,
                    cornerDp = 22,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        other.name,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!user?.handle.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            MirrorLucideIcon("LAtSign", tint = MgrInk.White85, modifier = Modifier.size(12.dp))
                            Text(user?.handle.orEmpty(), color = MgrInk.White85, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (!user?.statusText.isNullOrBlank()) {
                        Text(user?.statusText.orEmpty(), color = MgrInk.White75, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MgrInk.Glass)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Text(
                "About",
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                user?.bio?.takeIf { it.isNotBlank() } ?: "-",
                color = MirrorArt.TextSoft,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (user?.lastSeen.isNullOrBlank()) "Last seen hidden" else "Last active ${mgrStamp(user?.lastSeen)}",
                color = MirrorArt.Faint,
                fontSize = 11.sp,
            )
        }
    }
}

// shared chrome ============================================================

@Composable
private fun MgrIconButton(
    label: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (pressed) MgrInk.Press else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MgrSectionHeader(icon: String, label: String, count: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MirrorLucideIcon(icon, tint = MirrorArt.Faint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(Locale.US),
            color = MirrorArt.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        Text(count, color = MirrorArt.Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MgrWideButton(label: String, leading: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) MgrInk.AmberSoft else Color.Transparent)
            .border(1.dp, Color(0x66FFB454), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MirrorLucideIcon(leading, tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = MirrorArt.Accent2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MgrPillButton(label: String, amber: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (amber) Color(0xFF20150C) else MirrorArt.Text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (amber) MirrorArt.Accent else Color(0x1AFFFFFF))
            .border(1.dp, if (amber) Color.Transparent else MirrorArt.Hairline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun MgrTextField(value: String, onValue: (String) -> Unit, placeholder: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MgrInk.GlassTile)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = MirrorArt.Faint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(MirrorArt.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MgrModalShell(onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MgrInk.Scrim)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MgrInk.Panel)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(20.dp))
                .clickable(enabled = false) {}
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun MgrConfirmModal(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    pending: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    MgrModalShell(onClose = onClose) {
        Text(title, color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MirrorArt.Faint, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MgrModalButton(label = "Cancel", destructive = false, enabled = true, pending = false, onClick = onClose, modifier = Modifier.weight(1f))
            MgrModalButton(label = confirmLabel, destructive = destructive, enabled = !pending, pending = pending, onClick = onConfirm, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MgrModalButton(
    label: String,
    destructive: Boolean,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0x0FFFFFFF)
            destructive -> MgrInk.RoseStrong
            else -> MirrorArt.Accent
        },
        label = "mgrModalBtn",
    )
    val ink = if (destructive) Color.White else Color(0xFF20150C)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (pending) {
            MirrorLucideIcon("LLoaderCircle", tint = ink, modifier = Modifier.size(14.dp))
        } else {
            Text(
                label,
                color = if (enabled) ink else MirrorArt.Faint,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MgrAddModal(
    repository: PulseRepository,
    conversationId: String,
    existingIds: Set<String>,
    onClose: () -> Unit,
    onAdded: (Int) -> Unit,
) {
    var users by remember { mutableStateOf<List<User>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val result = repository.users()
        withContext(Dispatchers.Main) {
            result.fold(
                onSuccess = { users = it },
                onFailure = { loadError = true },
            )
        }
    }

    MgrModalShell(onClose = onClose) {
        Text("Add members", color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick people already on this Pulse. They start with no unread backlog.",
            color = MirrorArt.Faint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        MgrTextField(value = query, onValue = { if (it.length <= 40) query = it }, placeholder = "Search people…")
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MgrInk.Glass)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val q = query.trim().lowercase(Locale.US)
            val candidates = (users ?: emptyList())
                .filter { it.id !in existingIds }
                .filter { u ->
                    q.isEmpty() || u.name.lowercase(Locale.US).contains(q) || u.handle.lowercase(Locale.US).contains(q)
                }
            when {
                users == null && !loadError -> MgrSkeletonRows(rows = 4, circle = true)
                loadError -> Text(
                    "Could not load people. Close and try again.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
                candidates.isEmpty() -> Text(
                    if (q.isNotEmpty()) "No one matches that search." else "Everyone on this Pulse is already here.",
                    color = MirrorArt.TextSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
                else -> for (user in candidates) {
                    val checked = user.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selected = if (checked) selected - user.id else selected + user.id
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (checked) MirrorArt.Accent else Color.Transparent)
                                .border(1.dp, if (checked) MirrorArt.Accent else Color(0x33FFFFFF), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) {
                                MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                        MirrorAvatar(
                            name = user.name,
                            color = user.color,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 36,
                            cornerDp = 18,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                user.name,
                                color = MirrorArt.Text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (user.handle.isNotBlank()) {
                                Text("@${user.handle}", color = MirrorArt.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MgrInk.Rose, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MgrModalButton(label = "Cancel", destructive = false, enabled = true, pending = false, onClick = onClose, modifier = Modifier.weight(1f))
            MgrModalButton(
                label = if (selected.isEmpty()) "Add" else "Add ${selected.size}",
                destructive = false,
                enabled = selected.isNotEmpty() && !pending,
                pending = pending,
                onClick = {
                    pending = true
                    error = ""
                    scope.launch(Dispatchers.IO) {
                        val result = repository.addGroupMembers(conversationId, selected.toList())
                        withContext(Dispatchers.Main) {
                            pending = false
                            result.fold(
                                onSuccess = { added -> onAdded(added.size) },
                                onFailure = { e -> error = e.message ?: "Could not add members" },
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun MgrRenameModal(
    currentName: String,
    pending: Boolean,
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= GROUP_NAME_MAX && trimmed != currentName
    MgrModalShell(onClose = onClose) {
        Text("Rename group", color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Visible to every member. 1-$GROUP_NAME_MAX characters.",
            color = MirrorArt.Faint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        MgrTextField(value = name, onValue = { if (it.length <= GROUP_NAME_MAX) name = it }, placeholder = "Group name")
        Text(
            "${trimmed.length}/$GROUP_NAME_MAX",
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MgrModalButton(label = "Cancel", destructive = false, enabled = true, pending = false, onClick = onClose, modifier = Modifier.weight(1f))
            MgrModalButton(
                label = "Save name",
                destructive = false,
                enabled = valid && !pending,
                pending = pending,
                onClick = { onConfirm(trimmed) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// skeletons + errors =======================================================

@Composable
private fun MgrInlineError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MgrInk.Rose10)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        MgrPillButton(label = "Try again", amber = false, onClick = onRetry)
    }
}

@Composable
private fun MgrSkeleton() {
    val pulse = rememberInfiniteTransition(label = "mgrSkeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mgrSkeletonAlpha",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .alpha(alpha)
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MgrInk.Glass),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.alpha(alpha).fillMaxWidth(0.66f).height(20.dp).clip(RoundedCornerShape(8.dp)).background(MgrInk.Glass))
                Box(Modifier.alpha(alpha).fillMaxWidth(0.33f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MgrInk.Glass))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .alpha(alpha)
                        .weight(1f)
                        .height(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MgrInk.Glass),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        repeat(5) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.alpha(alpha).size(44.dp).clip(CircleShape).background(MgrInk.Glass))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.alpha(alpha).fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MgrInk.Glass))
                    Box(Modifier.alpha(alpha).fillMaxWidth(0.33f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MgrInk.Glass))
                }
            }
        }
    }
}

@Composable
private fun MgrSkeletonRows(rows: Int, circle: Boolean) {
    val pulse = rememberInfiniteTransition(label = "mgrRows")
    val alpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mgrRowsAlpha",
    )
    Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .alpha(alpha)
                        .size(if (circle) 30.dp else 36.dp)
                        .clip(if (circle) CircleShape else RoundedCornerShape(10.dp))
                        .background(MgrInk.Glass),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.alpha(alpha).fillMaxWidth(0.5f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(MgrInk.Glass))
                    Box(Modifier.alpha(alpha).fillMaxWidth(0.33f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MgrInk.Glass))
                }
            }
        }
    }
}

@Composable
private fun MgrLoadError(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MirrorLucideIcon("LUsersRound", tint = MirrorArt.Faint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text("Could not load the conversation info.", color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        MgrPillButton(label = "Try again", amber = false, onClick = onRetry)
    }
}

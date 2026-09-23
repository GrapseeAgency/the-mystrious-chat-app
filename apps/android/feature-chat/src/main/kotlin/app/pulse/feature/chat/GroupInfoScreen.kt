package app.pulse.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Group info — the native rebuild of web group-info-sheet.tsx / room-info-page.tsx
 * (REM-A P1): members with role chips, admin-only add/promote/demote/kick,
 * rename + announcement mode, the disappearing-TTL picker (F-MS-19), slow mode,
 * the admin-only invite create/regenerate + copy (pulse://invite/<code>) and
 * Leave group with confirm (last-admin succession is server-side).
 */
@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val repo: PulseRepository,
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    /** THIS viewer (prefs-sourced) — drives the (you) tag + self-row guard. */
    val viewerId: String? get() = repo.viewerId

    data class UiState(
        val loading: Boolean = false,
        /** Live server meta (roles/TTL/broadcast/slow mode/invite). */
        val meta: GroupMeta? = null,
        /** Roster picker rows (directory minus existing members). */
        val directory: List<User> = emptyList(),
        val notice: String? = null,
        val noticeError: Boolean = false,
        /** Set once THIS viewer successfully left — the screen pops. */
        val left: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val meta = runCatching { repo.groupMeta(conversationId).getOrNull() }.getOrNull()
            _state.value = _state.value.copy(loading = false, meta = meta)
        }
    }

    private fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = text, noticeError = isError)
    }

    fun consumeNotice() {
        if (_state.value.notice != null) {
            _state.value = _state.value.copy(notice = null, noticeError = false)
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repo.renameGroup(conversationId, name) }
                .onSuccess {
                    notify("Group renamed")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't rename the group", isError = true) }
        }
    }

    fun setBroadcast(on: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setGroupBroadcast(conversationId, on) }
                .onSuccess {
                    notify(if (on) "Announcement mode on — only admins can post" else "Announcement mode off")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't update announcement mode", isError = true) }
        }
    }

    /** F-MS-19 — server presets 0 · 86 400 · 604 800 · 2 592 000 (route truth). */
    fun setTtl(ttlSeconds: Int) {
        viewModelScope.launch {
            repo.setDisappearingTtl(conversationId, ttlSeconds)
                .onSuccess { resolved ->
                    notify(if (resolved > 0) "New messages disappear (${ttlLabel(resolved)})" else "Disappearing messages off")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't update disappearing messages", isError = true) }
        }
    }

    /** R44 slow mode — admin-only; presets 0/5/10/30/60/300. */
    fun setSlowMode(seconds: Int) {
        viewModelScope.launch {
            runCatching { repo.setSlowMode(conversationId, seconds) }
                .onSuccess {
                    notify(if (seconds > 0) "Slow mode: one message every ${slowLabel(seconds)}" else "Slow mode off")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't update slow mode", isError = true) }
        }
    }

    fun addMembers(userIds: List<String>) {
        viewModelScope.launch {
            repo.addGroupMembers(conversationId, userIds)
                .onSuccess { added ->
                    notify(if (added.isEmpty()) "They're already in the group" else "Added ${added.size} ${if (added.size == 1) "member" else "members"}")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't add members", isError = true) }
        }
    }

    fun setRole(userId: String, promote: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setMemberRole(conversationId, userId, promote) }
                .onSuccess {
                    notify(if (promote) "Promoted to admin" else "Demoted to member")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't update the role", isError = true) }
        }
    }

    fun kick(userId: String) {
        viewModelScope.launch {
            runCatching { repo.kickMember(conversationId, userId) }
                .onSuccess {
                    notify("Removed from the group")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't remove the member", isError = true) }
        }
    }

    fun createInvite(regenerate: Boolean) {
        viewModelScope.launch {
            runCatching { repo.createGroupInvite(conversationId, regenerate) }
                .onSuccess { code ->
                    notify(if (regenerate) "Invite link regenerated — the old one is dead" else "Invite link ready")
                    load()
                }
                .onFailure { notify(it.message ?: "Couldn't create the invite link", isError = true) }
        }
    }

    fun leave() {
        viewModelScope.launch {
            repo.leaveGroup(conversationId)
                .onSuccess { outcome ->
                    _state.value = _state.value.copy(left = true)
                    if (outcome.promotedUserId != null) {
                        notify("You left — a new admin was promoted")
                    }
                }
                .onFailure { notify(it.message ?: "Couldn't leave the group", isError = true) }
        }
    }

    fun loadDirectory() {
        viewModelScope.launch {
            val users = runCatching { repo.users().getOrDefault(emptyList()) }.getOrDefault(emptyList())
            val existing = conversation.value?.memberIds?.toSet() ?: emptySet()
            _state.value = _state.value.copy(directory = users.filter { it.id !in existing && it.id != repo.viewerId })
        }
    }

    companion object {
        /** Web room-info TTL labels (chat-room.tsx header chip parity). */
        fun ttlLabel(seconds: Int): String = when (seconds) {
            86_400 -> "24h"
            604_800 -> "7d"
            2_592_000 -> "30d"
            else -> "${seconds}s"
        }

        fun slowLabel(seconds: Int): String = when (seconds) {
            60 -> "1m"
            300 -> "5m"
            else -> "${seconds}s"
        }
    }
}

/** Server TTL presets (disappearing/route.ts — off · 24h · 7d · 30d). */
private val TTL_PRESETS = listOf(0, 86_400, 604_800, 2_592_000)

/** Server slow-mode presets (slow-mode/route.ts — off · 5 · 10 · 30 · 60 · 300). */
private val SLOW_MODE_PRESETS = listOf(0, 5, 10, 30, 60, 300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    /** Fired after a successful leave — the host pops back to the chats list. */
    onLeft: () -> Unit,
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var renameOpen by remember { mutableStateOf(false) }
    var addMembersOpen by remember { mutableStateOf(false) }
    var leaveConfirmOpen by remember { mutableStateOf(false) }
    var kickTarget by remember { mutableStateOf<ConversationMember?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.left) { if (state.left) onLeft() }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice, withDismissAction = false)
        viewModel.consumeNotice()
    }

    val meta = state.meta
    val isAdmin = meta?.isAdmin == true
    val myId = viewModel.viewerId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && conversation == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── identity card ────────────────────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PulseAvatar(
                            name = conversation?.title ?: "Group",
                            colorHex = conversation?.accentColor,
                            size = 56.dp,
                            isGroup = true,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                conversation?.title ?: "Group",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${conversation?.memberIds?.size ?: 0} members" +
                                    if (meta?.broadcastMode == true) " · announcement" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isAdmin) {
                            IconButton(onClick = { renameOpen = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename group")
                            }
                        }
                    }
                }
            }

            // ── admin: invite link ───────────────────────────────
            if (isAdmin) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Invite link", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                if (meta?.inviteCode != null) {
                                    TextButton(onClick = { viewModel.createInvite(regenerate = true) }) {
                                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Regenerate", fontSize = 12.sp)
                                    }
                                } else {
                                    Button(onClick = { viewModel.createInvite(regenerate = false) }) {
                                        Text("Create", fontSize = 12.sp)
                                    }
                                }
                            }
                            val code = meta?.inviteCode
                            if (code != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "pulse://invite/$code",
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = {
                                            clipboard.setText(AnnotatedString("pulse://invite/$code"))
                                            scope.launch { snackbar.showSnackbar("Invite link copied", withDismissAction = false) }
                                        }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Copy", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── F-MS-19 disappearing TTL picker (any participant) ──
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Disappearing messages", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Text(
                            "New messages are erased after the window passes. Applies to messages sent after the change.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TTL_PRESETS.forEach { preset ->
                                val active = (meta?.ttlSeconds ?: 0) == preset
                                AssistChip(
                                    onClick = { viewModel.setTtl(preset) },
                                    label = {
                                        Text(
                                            if (preset == 0) "Off" else GroupInfoViewModel.ttlLabel(preset),
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        )
                                    },
                                    leadingIcon = if (active) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }

            // ── admin: announcement + slow mode ─────────────────
            if (isAdmin) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Announcement mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Only admins can post", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = meta?.broadcastMode == true, onCheckedChange = { viewModel.setBroadcast(it) })
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Slow mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Members wait between sends", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SLOW_MODE_PRESETS.forEach { preset ->
                                    val active = (meta?.slowModeSeconds ?: 0) == preset
                                    AssistChip(
                                        onClick = { viewModel.setSlowMode(preset) },
                                        label = {
                                            Text(
                                                if (preset == 0) "Off" else GroupInfoViewModel.slowLabel(preset),
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            )
                                        },
                                        leadingIcon = if (active) {
                                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── members ─────────────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Members · ${conversation?.memberIds?.size ?: 0}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    if (isAdmin) {
                        TextButton(onClick = {
                            viewModel.loadDirectory()
                            addMembersOpen = true
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", fontSize = 12.sp)
                        }
                    }
                }
            }
            items(conversation?.members ?: emptyList(), key = { it.id }) { member ->
                val isMe = member.id == myId
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseAvatar(name = member.name, colorHex = member.color, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                member.name + if (isMe) " (you)" else "",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (member.role == "admin") {
                                Text("Admin", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        // Admin-only governance on OTHERS — hidden by role (web parity).
                        if (isAdmin && !isMe) {
                            if (member.role == "admin") {
                                TextButton(onClick = { viewModel.setRole(member.id, promote = false) }) {
                                    Text("Demote", fontSize = 12.sp)
                                }
                            } else {
                                TextButton(onClick = { viewModel.setRole(member.id, promote = true) }) {
                                    Text("Promote", fontSize = 12.sp)
                                }
                                IconButton(onClick = { kickTarget = member }) {
                                    Icon(
                                        Icons.Filled.PersonRemove,
                                        contentDescription = "Remove ${member.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                }
            }

            // ── leave ───────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { leaveConfirmOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Leave group")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── dialogs ─────────────────────────────────────────────────

    if (renameOpen) {
        var name by remember { mutableStateOf(conversation?.title ?: "") }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Group name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        if (name.isNotBlank()) viewModel.rename(name.trim())
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }

    if (addMembersOpen) {
        val picked = remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { addMembersOpen = false },
            title = { Text("Add members") },
            text = {
                if (state.directory.isEmpty()) {
                    Text("No contacts left to add.")
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(state.directory, key = { it.id }) { user ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    picked.value = picked.value.toMutableSet().apply {
                                        if (!add(user.id)) remove(user.id)
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PulseAvatar(name = user.name, colorHex = user.color, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(user.name, fontSize = 14.sp)
                                    if (user.handle.isNotBlank()) {
                                        Text("@${user.handle}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Checkbox(checked = user.id in picked.value, onCheckedChange = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        addMembersOpen = false
                        if (picked.value.isNotEmpty()) viewModel.addMembers(picked.value.toList())
                    },
                    enabled = picked.value.isNotEmpty(),
                ) { Text("Add (${picked.value.size})") }
            },
            dismissButton = { TextButton(onClick = { addMembersOpen = false }) { Text("Cancel") } },
        )
    }

    if (leaveConfirmOpen) {
        AlertDialog(
            onDismissRequest = { leaveConfirmOpen = false },
            title = { Text("Leave group?") },
            text = {
                Text(
                    if (isAdmin) {
                        "You are an admin. If no one else holds the flag, the longest-standing member is promoted automatically."
                    } else {
                        "You'll stop receiving messages from this group. History stays put."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirmOpen = false
                    viewModel.leave()
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaveConfirmOpen = false }) { Text("Cancel") } },
        )
    }

    kickTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text("Remove ${member.name}?") },
            text = { Text("They can rejoin later with an invite link.") },
            confirmButton = {
                TextButton(onClick = {
                    kickTarget = null
                    viewModel.kick(member.id)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text("Cancel") } },
        )
    }
}

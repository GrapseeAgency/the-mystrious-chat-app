package app.pulse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Channel
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.InviteJoinOutcome
import app.pulse.domain.model.InvitePreview
import app.pulse.domain.model.MentionItem
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ── Mentions (F-SM-03) ───────────────────────────────────────────────────

@HiltViewModel
class MentionsViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val items: List<MentionItem> = emptyList(),
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = repo.mentions()
            _state.value = State(loading = false, items = result.getOrElse { emptyList() }, error = result.isFailure)
        }
    }
}

/** Mentions feed — the 14-day server feed IS the badge count (no clear-on-open; no ack API exists, web parity). */
@Composable
fun MentionsScreen(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: MentionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Mentions", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "Messages that mention you — tap a row to jump in",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }

        when {
            state.loading && state.items.isEmpty() -> Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

            state.error && state.items.isEmpty() -> Column(Modifier.padding(20.dp)) {
                Text("Could not load mentions", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = viewModel::refresh) { Text("Try again") }
            }

            state.items.isEmpty() -> Column(Modifier.padding(20.dp)) {
                Text("No mentions yet", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "No mentions yet — when someone @mentions you, it shows up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.messageId }) { item ->
                    MentionRow(item = item, onClick = { onOpenRoom(item.conversationId) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MentionRow(item: MentionItem, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulseAvatar(name = item.authorName.ifBlank { "?" }, colorHex = null, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.authorName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(999.dp), color = PulsePalette.Emerald.copy(alpha = 0.16f)) {
                        Text(
                            "@you",
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = PulsePalette.Emerald,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    item.snippet,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    footerLabel(item) + " · " + stampOf(item.createdAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun footerLabel(item: MentionItem): String =
    if (item.conversationName.isNullOrBlank()) "Direct message" else item.conversationName ?: "Direct message"

private fun stampOf(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault("")

// ── Channels (F-CH-01…03) ────────────────────────────────────────────────

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val subscribed: List<Channel> = emptyList(),
        val discover: List<Channel> = emptyList(),
        val busyId: String? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val all = repo.channels(mineOnly = false).getOrElse { emptyList() }
            _state.value = State(
                loading = false,
                subscribed = all.filter { it.isSubscribed },
                discover = all.filterNot { it.isSubscribed },
            )
        }
    }

    fun subscribe(channel: Channel) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = channel.id, notice = null)
            repo.subscribeChannel(channel.id)
                .onSuccess { already ->
                    refresh()
                    _state.value = _state.value.copy(busyId = null, notice = if (already) "Already subscribed" else "Subscribed")
                }
                .onFailure {
                    refresh()
                    _state.value = _state.value.copy(busyId = null, notice = "Could not subscribe — try again")
                }
        }
    }

    fun unsubscribe(channel: Channel) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = channel.id, notice = null)
            repo.unsubscribeChannel(channel.id)
                .onSuccess {
                    refresh()
                    _state.value = _state.value.copy(busyId = null, notice = "You left " + channel.name)
                }
                .onFailure { e ->
                    refresh()
                    // The last-admin 403 copy is surfaced verbatim (web parity).
                    _state.value = _state.value.copy(busyId = null, notice = e.message ?: "Could not leave — try again")
                }
        }
    }

    fun create(name: String, description: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(notice = null)
            repo.createChannel(name, description.ifBlank { null }, null)
                .onSuccess { channel ->
                    refresh()
                    onReady(channel.id)
                    _state.value = _state.value.copy(notice = "Channel \"$name\" created")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(notice = e.message ?: "Could not create the channel — try again")
                }
        }
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2600)
            viewModel.consumeNotice()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Channels", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Broadcast spaces — only admins post",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                TextButton(onClick = { createOpen = true }) { Text("New channel", color = PulsePalette.Emerald) }
            }

            if (state.loading && state.subscribed.isEmpty() && state.discover.isEmpty()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp)) {
                    if (state.subscribed.isNotEmpty()) {
                        item {
                            SectionLabel("Subscribed · ${state.subscribed.size}")
                        }
                        items(state.subscribed, key = { "s" + it.id }) { channel ->
                            ChannelRow(
                                channel = channel,
                                busy = state.busyId == channel.id,
                                actionLabel = "Open",
                                onAction = { onOpenRoom(channel.id) },
                                onLeave = { viewModel.unsubscribe(channel) },
                            )
                        }
                    }
                    if (state.discover.isNotEmpty()) {
                        item {
                            SectionLabel("Discover · ${state.discover.size}")
                        }
                        items(state.discover, key = { "d" + it.id }) { channel ->
                            ChannelRow(
                                channel = channel,
                                busy = state.busyId == channel.id,
                                actionLabel = "Subscribe",
                                onAction = { viewModel.subscribe(channel) },
                                onLeave = null,
                            )
                        }
                    }
                    if (state.subscribed.isEmpty() && state.discover.isEmpty()) {
                        item {
                            Column(Modifier.padding(24.dp)) {
                                Text("No channels yet", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Broadcast spaces you subscribe to — or create — land here.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.notice?.let { notice ->
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(18.dp).clip(RoundedCornerShape(999.dp)),
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    notice,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 13.sp,
                )
            }
        }
    }

    if (createOpen) {
        CreateChannelSheet(
            onDismiss = { createOpen = false },
            onCreate = { name, description ->
                createOpen = false
                viewModel.create(name, description, onReady = onOpenRoom)
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChannelRow(
    channel: Channel,
    busy: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onLeave: (() -> Unit)?,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(name = channel.name, colorHex = null, size = 42.dp, isGroup = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel.unread) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(PulsePalette.Emerald))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(channel.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${channel.memberCount} subscriber" + if (channel.memberCount == 1) "" else "s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    channel.description ?: channel.preview ?: "No description yet",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onAction, enabled = !busy) { Text(actionLabel) }
                if (onLeave != null) {
                    TextButton(onClick = onLeave, enabled = !busy) {
                        Text("Leave", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateChannelSheet(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Text("New channel", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Channel name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 200) description = it },
                label = { Text("Description (optional)") },
                supportingText = { Text("${description.length}/200") },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    when {
                        name.trim().length < 2 -> error = "Name needs at least 2 characters."
                        name.trim().length > 40 -> error = "Keep the name under 41 characters."
                        else -> onCreate(name.trim(), description.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create channel") }
        }
    }
}

// ── Folders manage sheet (F-FD-01…03) ────────────────────────────────────

@HiltViewModel
class FoldersManageViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {
    data class State(
        val folders: List<FolderSummary> = emptyList(),
        val conversations: List<Conversation> = emptyList(),
        val busy: Boolean = false,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val folders = repo.folders().getOrElse { emptyList() }
            val conversations = repo.observeConversations().first()
            _state.value = State(folders = folders, conversations = conversations)
        }
    }

    fun create(name: String, emoji: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.createFolder(name, emoji)
                .onSuccess {
                    refresh()
                    _state.value = _state.value.copy(busy = false, notice = "$emoji Folder \"$name\" created")
                }
                .onFailure { _state.value = _state.value.copy(busy = false, notice = "Could not create the folder — try again") }
        }
    }

    fun rename(folderId: String, name: String, emoji: String, position: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.updateFolder(folderId, name.ifBlank { null }, emoji.ifBlank { null }, position)
                .onSuccess {
                    refresh()
                    _state.value = _state.value.copy(busy = false, notice = "Renamed to \"$name\"")
                }
                .onFailure { _state.value = _state.value.copy(busy = false, notice = "Could not rename — try again") }
        }
    }

    fun move(folderId: String, position: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.updateFolder(folderId, null, null, position)
                .onSuccess { refresh() }
                .onFailure { }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun delete(folderId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.deleteFolder(folderId)
                .onSuccess {
                    refresh()
                    _state.value = _state.value.copy(busy = false, notice = "Folder deleted — chats stay in your list")
                }
                .onFailure { _state.value = _state.value.copy(busy = false) }
        }
    }

    fun saveMembership(folderId: String, conversationIds: List<String>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.setFolderConversations(folderId, conversationIds)
                .onSuccess {
                    refresh()
                    val label = if (conversationIds.size == 1) "1 chat saved to the folder" else "${conversationIds.size} chats saved to the folder"
                    _state.value = _state.value.copy(busy = false, notice = label)
                }
                .onFailure { _state.value = _state.value.copy(busy = false, notice = "Could not save — try again") }
        }
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}

private val FOLDER_EMOJI_PRESETS = listOf("📂", "💼", "🎮", "❤️", "🔥", "🎯", "🎵", "🧠")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersManageSheet(
    onDismiss: () -> Unit,
    viewModel: FoldersManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    var editing by remember { mutableStateOf<FolderSummary?>(null) }
    var createName by remember { mutableStateOf("") }
    var createEmoji by remember { mutableStateOf("📂") }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2400)
            viewModel.consumeNotice()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Text("Chat folders", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))

            // Create row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FOLDER_EMOJI_PRESETS.forEach { emoji ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (createEmoji == emoji) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable { createEmoji = emoji },
                        ) {
                            Text(emoji, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { if (it.length <= 24) createName = it },
                    placeholder = { Text("Folder name…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (createName.isNotBlank()) {
                            viewModel.create(createName.trim(), createEmoji)
                            createName = ""
                        }
                    },
                    enabled = createName.isNotBlank() && !state.busy,
                ) { Text("Create") }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Folders filter your chat list — membership is managed per folder.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))

            state.folders.forEach { folder ->
                FolderManageRow(
                    folder = folder,
                    busy = state.busy,
                    onEdit = { editing = folder },
                    onMoveUp = { viewModel.move(folder.id, 0) },
                    onDeleteAsk = { confirmingDelete = folder.id },
                    deleteArmed = confirmingDelete == folder.id,
                    onDelete = {
                        confirmingDelete = null
                        viewModel.delete(folder.id)
                    },
                    onDeleteDisarm = { confirmingDelete = null },
                )
            }

            state.notice?.let { Text(it, fontSize = 12.sp, color = PulsePalette.Emerald) }
        }
    }

    editing?.let { folder ->
        FolderMembershipSheet(
            folder = folder,
            conversations = state.conversations,
            busy = state.busy,
            onDismiss = { editing = null },
            onSave = { ids ->
                editing = null
                viewModel.saveMembership(folder.id, ids)
            },
            onRename = { name, emoji ->
                editing = null
                viewModel.rename(folder.id, name, emoji, null)
            },
        )
    }
}

@Composable
private fun FolderManageRow(
    folder: FolderSummary,
    busy: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onDeleteAsk: () -> Unit,
    deleteArmed: Boolean,
    onDelete: () -> Unit,
    onDeleteDisarm: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${folder.emoji} ${folder.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    "${folder.conversationIds.size} chat" + if (folder.conversationIds.size == 1) "" else "s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onEdit) { Text("Chats") }
                TextButton(onClick = onMoveUp, enabled = !busy) { Text("Top") }
                if (deleteArmed) {
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Confirm", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onDeleteDisarm) { Text("Keep") }
                } else {
                    TextButton(onClick = onDeleteAsk) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text(
                "Created from the rail — chats stay in your list when a folder is deleted.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderMembershipSheet(
    folder: FolderSummary,
    conversations: List<Conversation>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onRename: (String, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selected by remember { mutableStateOf(folder.conversationIds.toSet()) }
    var name by remember { mutableStateOf(folder.name) }
    var renameOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${folder.emoji} ${folder.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { renameOpen = true }) { Text("Rename") }
            }
            Text(
                "Pick the chats that belong to this folder — Save replaces the membership.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(320.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (conversation.id in selected) selected - conversation.id else selected + conversation.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = conversation.id in selected, onCheckedChange = {
                            selected = if (it) selected + conversation.id else selected - conversation.id
                        })
                        PulseAvatar(name = conversation.title, colorHex = null, size = 30.dp, isGroup = conversation.kind == app.pulse.domain.model.Conversation.Kind.GROUP)
                        Spacer(Modifier.width(10.dp))
                        Text(conversation.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSave(conversations.map { it.id }.filter { it in selected }) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    if (renameOpen) {
        val renameState = rememberModalBottomSheetState()
        var newName by remember { mutableStateOf(folder.name) }
        var newEmoji by remember { mutableStateOf(folder.emoji) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename folder") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { if (it.length <= 24) name = it }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FOLDER_EMOJI_PRESETS.forEach { emoji ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (newEmoji == emoji) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(end = 6.dp).clickable { newEmoji = emoji },
                            ) { Text(emoji, Modifier.padding(8.dp)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(name.trim(), newEmoji.ifBlank { "📂" })
                    renameOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
}

// ── Invite join sheet (F-DL / web ?join= parity) ─────────────────────────

@HiltViewModel
class JoinInviteViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val preview: InvitePreview? = null,
        val invalid: Boolean = false,
        val joining: Boolean = false,
        val joined: InviteJoinOutcome? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(code: String) {
        viewModelScope.launch {
            _state.value = State(loading = true)
            repo.invitePreview(code)
                .onSuccess { preview -> _state.value = State(loading = false, preview = preview) }
                .onFailure { _state.value = State(loading = false, invalid = true) }
        }
    }

    fun join(code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(joining = true, error = null)
            repo.joinInvite(code)
                .onSuccess { outcome -> _state.value = _state.value.copy(joining = false, joined = outcome) }
                .onFailure { e -> _state.value = _state.value.copy(joining = false, error = e.message ?: "Could not join — try again") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinInviteSheet(
    code: String,
    onDismiss: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: JoinInviteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(code) { viewModel.load(code) }
    LaunchedEffect(state.joined) {
        state.joined?.let {
            onDismiss()
            onOpenRoom(it.conversationId)
        }
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                state.invalid -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Link not valid", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This invite was reset or never existed. Ask the group admin for a fresh one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) { Text("Not now") }
                }
                else -> {
                    val preview = state.preview
                    PulseAvatar(name = preview?.name ?: "Group", colorHex = null, size = 56.dp, isGroup = true)
                    Spacer(Modifier.height(8.dp))
                    Text(preview?.name ?: "Group", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "${preview?.memberCount ?: 0} member" + if ((preview?.memberCount ?: 0) == 1) "" else "s",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (preview?.alreadyMember == true) {
                            "You are already in this group — jump back in?"
                        } else {
                            "You were invited to join this group on Pulse."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (preview?.alreadyMember == true) {
                                preview.conversationId.let { id -> onDismiss(); onOpenRoom(id) }
                            } else {
                                viewModel.join(code)
                            }
                        },
                        enabled = !state.joining,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.joining) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (preview?.alreadyMember == true) "Open chat" else if (state.joining) "Joining…" else "Join group")
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    TextButton(onClick = onDismiss) { Text("Not now") }
                }
            }
        }
    }
}

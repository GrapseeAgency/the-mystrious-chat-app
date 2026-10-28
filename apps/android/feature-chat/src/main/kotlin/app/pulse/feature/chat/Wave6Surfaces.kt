package app.pulse.feature.chat

import app.pulse.ui.PulseIcons
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
import app.pulse.protocol.FOLDER_ICON_DEFAULT
import app.pulse.protocol.FOLDER_ICON_IDS
import app.pulse.protocol.folderIconId
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import app.pulse.ui.pulseFolderGlyph
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// Mentions (F-SM-03)

@HiltViewModel
class MentionsViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val items: List<MentionItem> = emptyList(),
        val error: Boolean = false,
        val previous: Boolean = false,
        val next: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loading: kotlinx.coroutines.Job? = null
    private var cursor: String? = null
    private var nextCursor: String? = null
    private val previous = mutableListOf<String?>()
    fun page(older: Boolean) {
        if (_state.value.loading) return
        if (older) { val next = nextCursor ?: return; previous.add(cursor); cursor = next }
        else { if (previous.isEmpty()) return; cursor = previous.removeAt(previous.lastIndex) }
        _state.value = _state.value.copy(items = emptyList())
        refresh()
    }
    private var generation = 0L
    private var feedEpoch: String? = null
    fun canOpen(item: MentionItem): Boolean = feedEpoch != null && feedEpoch == repo.attachmentSessionKey() && item.isVisible()
    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _state.value = if (feedEpoch != repo.attachmentSessionKey()) State(loading = false)
                    else _state.value.copy(items = _state.value.items.filter { it.isVisible() })
            }
        }
    }

    fun refresh() {
        val epoch = repo.attachmentSessionKey()
        val account = repo.boundToCurrentAccount()
        if (feedEpoch != epoch) { _state.value = State(); feedEpoch = epoch; cursor = null; nextCursor = null; previous.clear() }
        val request = ++generation
        loading?.cancel()
        _state.value = _state.value.copy(loading = true, previous = previous.isNotEmpty(), error = false)
        loading = viewModelScope.launch {
            val result = account.mentionsPage(cursor)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (request != generation || epoch == null || repo.attachmentSessionKey() != epoch) return@launch
            if ((result.exceptionOrNull() as? app.pulse.domain.model.PulseApiException)?.status == 400) {
                cursor = null; nextCursor = null; previous.clear()
            }
            val page = result.getOrNull()
            nextCursor = page?.nextCursor
            _state.value = State(loading = false, items = page?.items.orEmpty().filter { it.isVisible() }, error = result.isFailure,
                previous = previous.isNotEmpty(), next = page?.hasMore == true)
        }
    }

}

/** Mentions feed - the paginated all-history server feed supplies the badge (no clear-on-open; no ack API exists, web parity). */
@Composable
fun MentionsScreen(
    onBack: () -> Unit,
    onOpenRoom: (String, String) -> Unit,
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
            IconButton(onClick = onBack) { Icon(PulseIcons.ChevronLeft, contentDescription = "Back") }
            Column {
                Text("Mentions", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "Messages that mention you - tap a row to jump in",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::refresh) { Icon(PulseIcons.Refresh, contentDescription = "Refresh") }
        }

        FeedPageButtons(state.previous, state.next, state.loading, { viewModel.page(false) }, { viewModel.page(true) })
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
                    if (state.next) "No matches in this window. Continue searching older history." else "No mentions in this window.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.messageId }) { item ->
                    MentionRow(item = item, onClick = { if (viewModel.canOpen(item)) onOpenRoom(item.conversationId, item.messageId) })
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

// Channels (F-CH-01…03)

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
                    _state.value = _state.value.copy(busyId = null, notice = "Could not subscribe - try again")
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
                    _state.value = _state.value.copy(busyId = null, notice = e.message ?: "Could not leave - try again")
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
                    _state.value = _state.value.copy(notice = e.message ?: "Could not create the channel - try again")
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
                IconButton(onClick = onBack) { Icon(PulseIcons.ChevronLeft, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Channels", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Broadcast spaces - only admins post",
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
                                    "Broadcast spaces you subscribe to - or create - land here.",
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

// Folders manage sheet (F-FD-01…03)

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

    private var readVersion = 0L
    init { refresh() }

    fun refresh() {
        if (_state.value.busy) return
        val owner = repo.viewerId ?: return
        val ticket = ++readVersion
        viewModelScope.launch {
            try {
                val folders = repo.folders().getOrThrow()
                val conversations = repo.observeConversations().first()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.viewerId == owner && ticket == readVersion) {
                    _state.value = _state.value.copy(folders = folders, conversations = conversations)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.viewerId == owner && ticket == readVersion) _state.value = _state.value.copy(notice = "Could not refresh folders - try again")
            }
        }
    }

    private fun mutate(notice: String, confirmed: () -> Unit = {}, action: suspend () -> Result<*>) {
        if (_state.value.busy) return
        val owner = repo.viewerId ?: return
        ++readVersion
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                action().getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repo.viewerId != owner) return@launch
                confirmed()
                _state.value = _state.value.copy(notice = notice)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (repo.viewerId == owner) _state.value = _state.value.copy(notice = error.message ?: "Could not save the folder - try again")
            } finally {
                if (repo.viewerId == owner) { _state.value = _state.value.copy(busy = false); refresh() }
            }
        }
    }
    fun create(name: String, emoji: String, confirmed: () -> Unit = {}) =
        mutate("Folder \"$name\" created", confirmed) { repo.createFolder(name, emoji) }

    fun rename(folderId: String, name: String, emoji: String, position: Int?, confirmed: () -> Unit = {}) =
        mutate("Folder updated", confirmed) { repo.updateFolder(folderId, name.ifBlank { null }, emoji.ifBlank { null }, position) }

    fun move(folderId: String, position: Int) =
        mutate("Folder moved") { repo.updateFolder(folderId, null, null, position) }

    fun delete(folderId: String) = mutate("Folder deleted - chats stay in your list") { repo.deleteFolder(folderId) }

    fun saveMembership(folderId: String, conversationIds: List<String>, confirmed: () -> Unit = {}) =
        mutate("${conversationIds.size} chats saved to the folder", confirmed) { repo.setFolderConversations(folderId, conversationIds) }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}

/**
 * R18 icon-id contract - folder presets are stable registry ids (web
 * icon-ids.ts FOLDER_ICON_IDS); glyphs render via [pulseFolderGlyph] and
 * stale/unknown stored values normalize to the default.
 */
private val FOLDER_ICON_PRESETS = FOLDER_ICON_IDS

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
    var createEmoji by remember { mutableStateOf(FOLDER_ICON_DEFAULT) }
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
                    FOLDER_ICON_PRESETS.forEach { id ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (createEmoji == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable { createEmoji = id },
                        ) {
                            Icon(
                                pulseFolderGlyph(id),
                                contentDescription = null,
                                tint = if (createEmoji == id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp).size(16.dp),
                            )
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
                            val draft = createName
                            viewModel.create(draft.trim(), createEmoji) { if (createName == draft) createName = "" }
                        }
                    },
                    enabled = createName.isNotBlank() && !state.busy,
                ) { Text("Create") }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Folders filter your chat list - membership is managed per folder.",
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
                viewModel.saveMembership(folder.id, ids) { if (editing?.id == folder.id) editing = null }
            },
            onRename = { name, emoji ->
                viewModel.rename(folder.id, name, emoji, null) { if (editing?.id == folder.id) editing = null }
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
                Icon(
                    pulseFolderGlyph(folder.emoji),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(folder.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    "${folder.conversationIds.size} chat" + if (folder.conversationIds.size == 1) "" else "s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onEdit, enabled = !busy) { Text("Chats") }
                TextButton(onClick = onMoveUp, enabled = !busy) { Text("Top") }
                if (deleteArmed) {
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Confirm", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onDeleteDisarm) { Text("Keep") }
                } else {
                    TextButton(onClick = onDeleteAsk) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text(
                "Created from the rail - chats stay in your list when a folder is deleted.",
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
                Icon(
                    pulseFolderGlyph(folder.emoji),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(folder.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { renameOpen = true }, enabled = !busy) { Text("Rename") }
            }
            Text(
                "Pick the chats that belong to this folder - Save replaces the membership.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(320.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            selected = if (conversation.id in selected) selected - conversation.id else selected + conversation.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = conversation.id in selected, enabled = !busy, onCheckedChange = {
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
                onClick = { onSave((conversations.map { it.id }.filter { it in selected } + selected.filter { id -> conversations.none { it.id == id } }.sorted()).distinct()) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    if (renameOpen) {
        val renameState = rememberModalBottomSheetState()
        var newName by remember { mutableStateOf(folder.name) }
        var newEmoji by remember { mutableStateOf(folderIconId(folder.emoji)) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename folder") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { if (!busy && it.length <= 24) name = it }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FOLDER_ICON_PRESETS.forEach { id ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (newEmoji == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(end = 6.dp).clickable(enabled = !busy) { newEmoji = id },
                            ) {
                                Icon(
                                    pulseFolderGlyph(id),
                                    contentDescription = null,
                                    tint = if (newEmoji == id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(name.trim(), newEmoji.ifBlank { FOLDER_ICON_DEFAULT })
                }, enabled = !busy && name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
}

// Invite join sheet (F-DL / web ?join= parity)

@HiltViewModel
class JoinInviteViewModel @Inject constructor(private val repo: PulseRepository) : ViewModel() {
    data class State(
        val code: String? = null,
        val loading: Boolean = true,
        val preview: InvitePreview? = null,
        val invalid: Boolean = false,
        val joining: Boolean = false,
        val joined: InviteJoinOutcome? = null,
        val error: String? = null,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var lease = 0L
    private var loadRevision = 0L
    private var boundCode: String? = null
    private var bound: PulseRepository? = null
    private var owner: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var joinJob: kotlinx.coroutines.Job? = null

    fun bind(code: String): Long {
        loadJob?.cancel(); joinJob?.cancel()
        lease++
        boundCode = code
        bound = repo.boundToCurrentAccount()
        owner = bound?.viewerId
        load(code)
        return lease
    }

    fun release(ticket: Long) {
        if (ticket != lease) return
        lease++
        loadJob?.cancel(); joinJob?.cancel()
        boundCode = null; bound = null; owner = null
        _state.value = State()
    }

    private fun owns(ticket: Long, code: String, target: PulseRepository): Boolean =
        ticket == lease && boundCode == code && bound === target && target.viewerId == owner

    fun load(code: String) {
        val target = bound ?: return
        if (boundCode != code || _state.value.joining) return
        loadJob?.cancel()
        val ticket = lease
        val revision = ++loadRevision
        _state.value = State(code = code)
        loadJob = viewModelScope.launch {
            try {
                if (!owns(ticket, code, target)) return@launch
                val result = target.invitePreview(code)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!owns(ticket, code, target) || revision != loadRevision) return@launch
                result.fold(
                    onSuccess = { preview ->
                        _state.value = if (preview.conversationId.isBlank()) State(code = code, loading = false,
                            error = "The server didn't identify this invite's group. Retry loading it.")
                        else State(code = code, loading = false, preview = preview)
                    },
                    onFailure = { error ->
                        val status = (error as? app.pulse.domain.model.PulseApiException)?.status
                        _state.value = State(code = code, loading = false, invalid = status in listOf(400, 404, 410),
                            error = error.message ?: "Couldn't load this invite. Check the connection and retry.")
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (owns(ticket, code, target) && revision == loadRevision) _state.value = State(code = code, loading = false,
                    error = "Couldn't load this invite. Check the connection and retry.")
            }
        }
    }

    fun join(code: String) {
        val target = bound ?: return
        if (boundCode != code || _state.value.joining || _state.value.loading || owner.isNullOrBlank() || target.viewerId != owner) return
        val ticket = lease
        val preview = _state.value.preview ?: return
        _state.value = _state.value.copy(joining = true, joined = null, error = null)
        joinJob = viewModelScope.launch {
            try {
                if (!owns(ticket, code, target)) return@launch
                val result = if (preview.alreadyMember) Result.success(InviteJoinOutcome(preview.conversationId, true)) else target.joinInvite(code)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!owns(ticket, code, target)) return@launch
                result.fold(
                    onSuccess = { outcome ->
                        _state.value = if (outcome.conversationId.isNotBlank() && outcome.conversationId == preview.conversationId)
                            _state.value.copy(joining = false, joined = outcome)
                        else _state.value.copy(joining = false, error = "The joined chat didn't match the preview. Refresh Chats before trying again.")
                    },
                    onFailure = { error -> _state.value = _state.value.copy(joining = false, error = error.message ?: "Could not join - try again") },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (owns(ticket, code, target)) _state.value = _state.value.copy(joining = false, error = "Couldn't confirm joining. Refresh Chats before retrying.")
            }
        }
    }

    fun consumeJoined(code: String, expected: InviteJoinOutcome): Boolean {
        val target = bound ?: return false
        if (!owns(lease, code, target) || _state.value.joined !== expected) return false
        _state.value = _state.value.copy(joined = null)
        return true
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

    androidx.compose.runtime.DisposableEffect(viewModel, code) {
        val lease = viewModel.bind(code)
        onDispose { viewModel.release(lease) }
    }
    LaunchedEffect(code, state.joined) {
        state.joined?.let {
            if (viewModel.consumeJoined(code, it)) {
                onDismiss()
                onOpenRoom(it.conversationId)
            }
        }
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                state.code != code || state.loading -> CircularProgressIndicator(Modifier.size(24.dp))
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
                state.preview == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "Couldn't load this invite", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.load(code) }) { Text("Retry") }
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
                            "You are already in this group - jump back in?"
                        } else {
                            "You were invited to join this group on Pulse."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.join(code)
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

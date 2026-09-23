package app.pulse.feature.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * R2-A item 1 — the "New chat" composer VM (web new-chat-sheet.tsx parity):
 * searchable people directory + create DM / create group / create channel
 * through the REAL POST /api/conversations + /api/channels paths. The created
 * rows land in the Room cache via the repo, so the open chats list refreshes
 * itself through the existing observeConversations flow.
 */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class UiState(
        val users: List<User> = emptyList(),
        val loading: Boolean = false,
        val creating: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot created-conversation signal — the shell navigates into the room. */
    private val _openedConversation = MutableStateFlow<String?>(null)
    val openedConversation: StateFlow<String?> = _openedConversation.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeOpened() {
        _openedConversation.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun notify(text: String) {
        _notice.value = text
    }

    /** Directory refresh — the web sheet queries GET /api/users on open. */
    fun loadUsers() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = repo.users()
            _state.value = result.fold(
                onSuccess = { users -> _state.value.copy(users = users, loading = false) },
                onFailure = { _state.value.copy(loading = false, error = it.message) },
            )
        }
    }

    fun createDm(otherUserId: String) {
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            val result: kotlin.Result<app.pulse.domain.model.Conversation> = repo.createDm(otherUserId)
            result
                .onSuccess { conversation ->
                    _state.value = _state.value.copy(creating = false)
                    notify("Chat ready")
                    _openedConversation.value = conversation.id
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(creating = false)
                    notify(failure.message ?: "Could not start that conversation")
                }
        }
    }

    fun createGroup(name: String, memberIds: List<String>) {
        if (_state.value.creating || memberIds.isEmpty()) return
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            val result: kotlin.Result<app.pulse.domain.model.Conversation> = repo.createGroup(name, memberIds)
            result
                .onSuccess { conversation ->
                    _state.value = _state.value.copy(creating = false)
                    notify("Group “${conversation.title}” created")
                    _openedConversation.value = conversation.id
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(creating = false)
                    notify(failure.message ?: "Could not start that conversation")
                }
        }
    }

    fun createChannel(name: String, description: String) {
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            val result: kotlin.Result<app.pulse.domain.model.Channel> = repo.createChannel(
                name = name,
                description = description.ifBlank { null },
                photo = null,
            )
            result
                .onSuccess { channel ->
                    _state.value = _state.value.copy(creating = false)
                    notify("Channel “${channel.name}” created")
                    // The wire's channel row id IS the created conversation id
                    // (channels/route.ts: id: conv.id) — open it directly.
                    _openedConversation.value = channel.id
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(creating = false)
                    notify(failure.message ?: "Could not create the channel")
                }
        }
    }
}

private const val CHANNEL_NAME_MIN = 2
private const val CHANNEL_NAME_MAX = 40
private const val CHANNEL_DESCRIPTION_MAX = 200
private const val GROUP_NAME_MAX = 48

/**
 * R2-A item 1 — the "New chat" bottom sheet (web new-chat-sheet.tsx): a
 * Direct/Group/Channel segmented mode switch, searchable people rows (DM
 * tap-through, group multi-select with live member count), and the footer
 * create actions against the REAL conversation/channel endpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    viewerId: String?,
    onDismiss: () -> Unit,
    /** Fired after a successful create — the shell navigates into the room. */
    onConversationOpened: (String) -> Unit,
    /** Create outcomes (web toast parity) — surfaced by the host's snackbar. */
    onNotice: (String) -> Unit = {},
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(NewChatMode.DIRECT) }
    var search by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var channelName by remember { mutableStateOf("") }
    var channelDescription by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadUsers() }
    // Create outcomes ride the host's snackbar (web toast parity) — the sheet
    // itself is closing on success, so it cannot host its own transient UI.
    LaunchedEffect(Unit) {
        viewModel.notice.collect { notice ->
            if (notice != null) {
                onNotice(notice)
                viewModel.consumeNotice()
            }
        }
    }
    LaunchedEffect(viewModel.openedConversation) {
        viewModel.openedConversation.collect { id ->
            if (id != null) {
                viewModel.consumeOpened()
                onConversationOpened(id)
            }
        }
    }

    val others = remember(state.users, viewerId, search) {
        val q = search.trim().lowercase()
        state.users
            .filter { it.id != viewerId }
            .filter { u -> q.isEmpty() || u.name.lowercase().contains(q) }
    }

    val totalMembers = selectedIds.size + 1
    val groupValid = totalMembers >= 3
    val channelValid = channelName.trim().length in CHANNEL_NAME_MIN..CHANNEL_NAME_MAX
    val creating = state.creating

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New chat", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Start a direct message or build a new group",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            // segmented control (web :213-253 — three-way mode switch)
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    NewChatMode.entries.forEach { seg ->
                        val active = mode == seg
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { mode = seg }
                                .padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                seg.icon,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (active) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                seg.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            when (mode) {
                NewChatMode.DIRECT -> OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search people…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                NewChatMode.GROUP -> Column {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { if (it.length <= GROUP_NAME_MAX) groupName = it },
                        placeholder = { Text("Group of $totalMembers") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (groupValid) {
                            "$totalMembers ${if (totalMembers == 1) "member" else "members"} selected · include yourself plus at least 2 people"
                        } else {
                            "$totalMembers of 3+ members picked · include yourself plus at least 2 people"
                        },
                        fontSize = 11.sp,
                        color = if (groupValid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            PulsePalette.Emerald
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                NewChatMode.CHANNEL -> Column {
                    OutlinedTextField(
                        value = channelName,
                        onValueChange = { if (it.length <= CHANNEL_NAME_MAX) channelName = it },
                        placeholder = { Text("Channel name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = channelDescription,
                        onValueChange = { if (it.length <= CHANNEL_DESCRIPTION_MAX) channelDescription = it },
                        placeholder = { Text("Description — what is this channel about? (optional)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        if (channelDescription.isNotEmpty()) {
                            "${CHANNEL_DESCRIPTION_MAX - channelDescription.length} characters left"
                        } else {
                            "You will be the admin — only admins can post in a channel."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // people list (hidden on the channel step — web :384-445)
            if (mode != NewChatMode.CHANNEL) {
                Box(Modifier.fillMaxWidth().height(280.dp).padding(top = 10.dp)) {
                    when {
                        state.loading && state.users.isEmpty() -> Column(Modifier.fillMaxWidth()) {
                            repeat(4) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Box(Modifier.fillMaxWidth(0.4f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                        Spacer(Modifier.height(5.dp))
                                        Box(Modifier.fillMaxWidth(0.6f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                    }
                                }
                            }
                        }
                        others.isEmpty() -> Column(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                            Text(
                                "No one else has joined yet. Open a second browser tab and create another account.",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(others, key = { it.id }) { person ->
                                val checked = person.id in selectedIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (mode == NewChatMode.DIRECT) {
                                                viewModel.createDm(person.id)
                                            } else {
                                                selectedIds = if (checked) selectedIds - person.id else selectedIds + person.id
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PulseAvatar(name = person.name, colorHex = person.color, size = 40.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            person.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val bioText = person.bio
                                        if (!bioText.isNullOrBlank()) {
                                            Text(
                                                bioText,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (mode == NewChatMode.GROUP) {
                                        Box(
                                            Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .border(
                                                    width = 2.dp,
                                                    color = if (checked) PulsePalette.Emerald else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = CircleShape,
                                                )
                                                .background(if (checked) PulsePalette.Emerald else androidx.compose.ui.graphics.Color.Transparent),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (checked) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // footer action (web :448-482)
            when (mode) {
                NewChatMode.GROUP -> Button(
                    onClick = { viewModel.createGroup(groupName.trim(), selectedIds.toList()) },
                    enabled = groupValid && !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Creating group…")
                    } else {
                        Text("Create group · $totalMembers ${if (totalMembers == 1) "member" else "members"}")
                    }
                }
                NewChatMode.CHANNEL -> Button(
                    onClick = { viewModel.createChannel(channelName.trim(), channelDescription.trim()) },
                    enabled = channelValid && !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Creating channel…")
                    } else {
                        Text("Create channel")
                    }
                }
                NewChatMode.DIRECT -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The web sheet's three-way segmented control (Direct / Group / Channel). */
internal enum class NewChatMode(val label: String, val icon: ImageVector) {
    DIRECT("Direct", Icons.Filled.ChatBubble),
    GROUP("Group", Icons.Filled.Groups),
    CHANNEL("Channel", Icons.Filled.Radio),
}

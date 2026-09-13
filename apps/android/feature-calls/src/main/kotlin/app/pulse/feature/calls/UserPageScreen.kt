package app.pulse.feature.calls

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.model.SafetyState
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.model.Conversation
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val REPORT_REASONS = listOf("spam", "harassment", "impersonation", "inappropriate", "scam", "other")
private val REPORT_LABELS = mapOf(
    "spam" to "Spam",
    "harassment" to "Harassment or bullying",
    "impersonation" to "Impersonation",
    "inappropriate" to "Inappropriate content",
    "scam" to "Scam or fraud",
    "other" to "Something else",
)

@HiltViewModel
class UserPageViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val profile: UserProfile? = null,
        val stats: UserStats? = null,
        val statsError: Boolean = false,
        val blocked: Boolean = false,
        val reasons: List<String> = emptyList(),
        val shared: List<Conversation> = emptyList(),
        val sharedOverflow: Int = 0,
        val busy: Boolean = false,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var userId: String = ""

    fun load(id: String) {
        userId = id
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = null)
            val profile = repo.userProfile(id).getOrNull()
            if (profile == null) {
                _state.value = _state.value.copy(loading = false, profile = null)
                return@launch
            }
            val statsResult = repo.userStats(id)
            val blocked = repo.blockState(id).getOrElse { false }
            val reasons = repo.myReportReasons(id).getOrElse { emptyList() }
            val mine = repo.viewerId
            val shared = if (mine != null) {
                repo.observeConversations().first().filter { it.memberIds.contains(id) && it.memberIds.contains(mine) }
            } else emptyList()
            _state.value = State(
                loading = false,
                profile = profile,
                stats = statsResult.getOrNull(),
                statsError = statsResult.isFailure,
                blocked = blocked,
                reasons = reasons,
                shared = shared.take(3),
                sharedOverflow = (shared.size - 3).coerceAtLeast(0),
            )
        }
    }

    fun toggleBlock() {
        val current = _state.value
        val profile = current.profile ?: return
        viewModelScope.launch {
            _state.value = current.copy(busy = true)
            val targetBlocked = !current.blocked
            val result = if (targetBlocked) repo.block(profile.id) else repo.unblock(profile.id)
            val fresh = repo.blockState(profile.id).getOrElse { targetBlocked }
            _state.value = _state.value.copy(
                busy = false,
                blocked = fresh,
                notice = if (result.isSuccess) {
                    (if (fresh) "Blocked " else "Unblocked ") + profile.firstName
                } else {
                    "Could not update the block — try again"
                },
            )
        }
    }

    fun submitReport(reason: String, details: String) {
        val profile = _state.value.profile ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = repo.report(profile.id, reason, details.ifBlank { null })
            val reasons = repo.myReportReasons(profile.id).getOrElse { emptyList() }
            _state.value = _state.value.copy(
                busy = false,
                reasons = reasons,
                notice = if (result.isSuccess) {
                    "Report submitted. Thanks for helping keep Pulse safe."
                } else {
                    "Could not submit the report — try again"
                },
            )
        }
    }

    fun startDm(onReady: (String) -> Unit) {
        val profile = _state.value.profile ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.createDm(profile.id)
                .onSuccess { conversation ->
                    _state.value = _state.value.copy(busy = false)
                    onReady(conversation.id)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        busy = false,
                        notice = error.message ?: "Could not open the direct chat — try again",
                    )
                }
        }
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPageScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: UserPageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reportOpen by remember { mutableStateOf(false) }

    LaunchedEffect(userId) { viewModel.load(userId) }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2600)
            viewModel.consumeNotice()
        }
    }

    val profile = state.profile
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        when {
            state.loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            profile == null -> Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Profile unavailable", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This Pulse member does not exist (or is no longer here).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(Modifier.padding(horizontal = 20.dp)) {
                // Hero
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatarPhoto(profile)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(profile.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            profile.handle?.let { "@$it" } ?: "No handle yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (!profile.statusEmoji.isNullOrBlank() || !profile.statusText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("${profile.statusEmoji.orEmpty()} ${profile.statusText.orEmpty()}".trim(), fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    profile.about?.takeIf { it.isNotBlank() } ?: "No bio yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))

                // Activity 3x2 grid
                Text("Activity", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (state.statsError) {
                    Text(
                        "Stats unavailable right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                } else {
                    StatsGrid(state.stats)
                }
                Spacer(Modifier.height(14.dp))

                // Rooms in common
                if (state.shared.isNotEmpty()) {
                    Text("Rooms in common", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    state.shared.forEach { conversation ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenRoom(conversation.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PulseAvatar(name = conversation.title, colorHex = null, size = 32.dp, isGroup = true)
                            Spacer(Modifier.width(10.dp))
                            Text(conversation.title, fontSize = 14.sp)
                        }
                    }
                    if (state.sharedOverflow > 0) {
                        Text(
                            "+${state.sharedOverflow} more",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Member since / last seen
                val joined = state.stats?.joinedAtIso ?: profile.createdAtIso
                MemberRow("Member since", joined?.let { formatStamp(it, monthOnly = true) } ?: "—")
                MemberRow(
                    "Last seen",
                    profile.lastSeenIso?.let { "Active " + relative(it) } ?: "Last seen hidden",
                )
                Spacer(Modifier.height(16.dp))

                // Actions
                Button(
                    onClick = { viewModel.startDm(onReady = onOpenRoom) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Message " + profile.firstName) }
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        if (!state.busy) viewModel.toggleBlock()
                    },
                    color = if (state.blocked) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (state.blocked) "Unblock " else "Block ") + profile.firstName,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { reportOpen = true },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text("Report " + profile.firstName, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    state.notice?.let { notice ->
        Surface(
            Modifier.padding(16.dp).clip(RoundedCornerShape(999.dp)),
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

    if (reportOpen && profile != null) {
        ReportPanel(
            firstName = profile.firstName,
            priorReasons = state.reasons,
            busy = state.busy,
            onDismiss = { reportOpen = false },
            onSubmit = { reason, details ->
                reportOpen = false
                viewModel.submitReport(reason, details)
            },
        )
    }
}

@Composable
private fun ProfileAvatarPhoto(profile: UserProfile) {
    val path = profile.avatar
    if (path != null && path.startsWith("/api/uploads/")) {
        AsyncImage(
            model = PulseEndpoints.http(path),
            contentDescription = "Avatar of " + profile.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(androidx.compose.foundation.shape.CircleShape),
        )
    } else {
        PulseAvatar(name = profile.name, colorHex = profile.color, size = 84.dp)
    }
}

@Composable
private fun StatsGrid(stats: UserStats?) {
    val tiles = listOf(
        "Messages" to (stats?.messages ?: 0),
        "Reactions" to (stats?.reactions ?: 0),
        "Photos" to (stats?.photos ?: 0),
        "Voice notes" to (stats?.voiceNotes ?: 0),
        "Chats" to (stats?.chats ?: 0),
        "Groups" to (stats?.groups ?: 0),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value.toString(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemberRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPanel(
    firstName: String,
    priorReasons: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var reason by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Text("Report $firstName", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tell us what's happening. Reports are private — $firstName will not be notified.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            if (priorReasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "You already reported this account for " +
                        priorReasons.joinToString(", ") { REPORT_LABELS[it] ?: it } + ".",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            REPORT_REASONS.forEach { key ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { reason = key }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = reason == key, onClick = { reason = key })
                    Text(REPORT_LABELS[key] ?: key, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = details,
                onValueChange = { if (it.length <= 500) details = it },
                placeholder = { Text("Add details (optional)") },
                supportingText = { Text("${details.length}/500") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSubmit(reason, details) },
                enabled = reason.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Submit report") }
        }
    }
}

internal fun formatStamp(iso: String, monthOnly: Boolean = false): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val pattern = if (monthOnly) "MMM yyyy" else "MMM d, HH:mm"
    return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(instant)
}

internal fun relative(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val minutes = (System.currentTimeMillis() - instant.toEpochMilli()) / 60000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

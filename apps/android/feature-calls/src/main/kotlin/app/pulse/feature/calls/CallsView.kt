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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallState
import app.pulse.domain.model.CallStatus
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History VM — Room-cached mirror of GET /api/calls, refreshed on entry. */
@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    val rows: StateFlow<List<CallLogEntry>> = repo.observeCallLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repo.refreshCallLog()
                .onFailure { _notice.value = "Can't load call history — showing cached rows." }
                .onSuccess { _notice.value = null }
        }
    }

    /** R3-B item 5 — the redial fallback surfaces through the same channel. */
    fun notify(text: String) {
        _notice.value = text
    }
}

/**
 * Wave 3 — call history (the CALL-LOG half of the wave). Renders all eight
 * directive cases from the single-writer REST rows:
 *   outgoing accepted   → ↑ + "Outgoing · duration"
 *   outgoing declined   → ↑ + "Declined"
 *   outgoing cancelled  → ↑ + "No answer" (wire collapses cancel/timeout → missed)
 *   incoming accepted   → ↓ + "Incoming · duration"
 *   incoming declined   → ↓ + "Declined"
 *   incoming missed     → ↯ + "Missed" (caller cancel / 30s timeout)
 *   timeout             → caller side of the same row ("No answer")
 *   completed           → any connected call that ended normally (duration > 0)
 *
 * R3-B item 5 — rows that carry a peer identity also carry a trailing REDIAL
 * button (web calls-page.tsx rows reopen the chat; iOS rows redial): same
 * engine path as the contacts call button, RECORD_AUDIO gated first, with a
 * per-row busy spinner and the honest "Calls aren't ready yet" fallback when
 * the engine never leaves idle (DM resolution failure).
 */
@Composable
fun CallsView(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit = {},
    // R3-B item 5 — start an outgoing call from a history row's identity.
    onRedial: (CallLogEntry) -> Unit = {},
) {
    val vm: CallHistoryViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val callVm: CallViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val callState by callVm.snapshot.collectAsStateWithLifecycle()

    // Redial busy machine: armed on tap → cleared the moment the engine's
    // snapshot leaves IDLE (the call overlay takes over) or after the honest
    // 6 s timeout when the engine never starts ("Calls aren't ready yet").
    var redialBusyId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(callState.state, redialBusyId) {
        if (redialBusyId != null && callState.state != CallState.IDLE) redialBusyId = null
    }
    LaunchedEffect(redialBusyId) {
        if (redialBusyId != null) {
            delay(6_000)
            if (redialBusyId != null) {
                vm.notify("Calls aren't ready yet")
                redialBusyId = null
            }
        }
    }

    // Wave 3 gate (contacts precedent): RECORD_AUDIO must be live before the
    // engine touches the mic; denial keeps the call unstarted (honest).
    var pendingRedial by remember { mutableStateOf<CallLogEntry?>(null) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingRedial
        pendingRedial = null
        if (granted && target != null) {
            onRedial(target)
        } else {
            redialBusyId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Calls", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
        }

        if (notice != null) {
            Text(
                text = notice ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No calls yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    CallRow(
                        row = row,
                        redialBusy = redialBusyId == row.id,
                        onClick = { onOpenRoom(row.conversationId) },
                        onRedial = if (row.peer != null) {
                            {
                                if (redialBusyId == null) {
                                    redialBusyId = row.id
                                    pendingRedial = row
                                    micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CallRow(
    row: CallLogEntry,
    redialBusy: Boolean,
    onClick: () -> Unit,
    onRedial: (() -> Unit)?,
) {
    val (icon, label, tint) = rowPresentation(row)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "Call from ${row.peer?.name ?: "unknown"}: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (row.peer?.name ?: "?").trim().split(Regex("\\s+")).take(2)
                    .map { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
                    .joinToString("").ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.peer?.name ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == CallStatus.MISSED || row.status == CallStatus.DECLINED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (onRedial != null) {
            // R3-B item 5 — the redial disc: emerald disc + phone glyph,
            // per-row spinner while the outgoing call is being armed.
            if (redialBusy) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = PulsePalette.Emerald,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PulsePalette.Emerald, CircleShape)
                        .clickable(onClick = onRedial)
                        .semantics { contentDescription = "Redial ${row.peer?.name ?: "unknown"}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The 8 directive cases → (icon, label, tint). Caller rows write outgoing=true. */
internal fun rowPresentation(row: CallLogEntry): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, Color> {
    val durationLabel = if (row.durationSec > 0) formatDuration(row.durationSec) else ""
    return when {
        row.outgoing && row.status == CallStatus.COMPLETED ->
            Triple(Icons.Filled.CallMade, if (durationLabel.isBlank()) "Outgoing" else "Outgoing · $durationLabel", Color(0xFF10B981))
        row.outgoing && row.status == CallStatus.DECLINED ->
            Triple(Icons.Filled.CallMade, "Declined", Color(0xFFE11D48))
        row.outgoing ->
            Triple(Icons.Filled.CallMade, "No answer", Color(0xFFE11D48))
        !row.outgoing && row.status == CallStatus.COMPLETED ->
            Triple(Icons.Filled.CallReceived, if (durationLabel.isBlank()) "Incoming" else "Incoming · $durationLabel", Color(0xFF10B981))
        !row.outgoing && row.status == CallStatus.DECLINED ->
            Triple(Icons.Filled.CallReceived, "Declined", Color(0xFFE11D48))
        else ->
            Triple(Icons.Filled.CallMissed, "Missed", Color(0xFFE11D48))
    }
}

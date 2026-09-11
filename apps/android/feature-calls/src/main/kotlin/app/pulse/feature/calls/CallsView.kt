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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallStatus
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 */
@Composable
fun CallsView(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit = {},
) {
    val vm: CallHistoryViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

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
                    CallRow(row, onClick = { onOpenRoom(row.conversationId) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CallRow(row: CallLogEntry, onClick: () -> Unit) {
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
        Icon(
            Icons.Filled.Call,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
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

package app.pulse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.SavedItem
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Saved library state holder (Wave 2, spec §1 row 14): refresh → Room-cached
 * flow, local search over sender/content/conversation (the server has NO
 * pagination/search — the fetch is capped at 100 newest), unsave via the save
 * toggle. "Open original" = navigate room + jump-to-message (the shell wires
 * the flash).
 */
@HiltViewModel
class SavedLibraryViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val items: List<SavedItem> = emptyList(),
        val query: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val viewerId: String? get() = repo.viewerId

    private var all: List<SavedItem> = emptyList()

    init {
        refresh()
        viewModelScope.launch {
            repo.observeSavedLibrary().collect { rows ->
                all = rows
                applyFilter()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.refreshSavedLibrary()
                .onSuccess { rows ->
                    all = rows
                    applyFilter()
                    _state.value = _state.value.copy(loading = false)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = failure.message ?: "Couldn't load your saved messages",
                    )
                }
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        applyFilter()
    }

    fun unsave(messageId: String) {
        viewModelScope.launch {
            runCatching { repo.unsaveMessage(messageId) }
                .onFailure {
                    _state.value = _state.value.copy(error = "Couldn't remove from saved")
                }
        }
    }

    private fun applyFilter() {
        val q = _state.value.query.trim()
        _state.value = _state.value.copy(
            items = if (q.isEmpty()) {
                all
            } else {
                all.filter { item ->
                    item.message.body.contains(q, ignoreCase = true) ||
                        item.message.authorName.contains(q, ignoreCase = true) ||
                        (item.conversationName ?: "").contains(q, ignoreCase = true)
                }
            },
        )
    }
}

/**
 * Saved library — the Wave 2 dock "Saved" destination. Honest states end to
 * end: loading spinner, error card with Retry, empty state (Star + hint),
 * searchable rows with 🖼/🎤/📎 snippets, unsave with confirm, and tap →
 * open the room jumped to the original message.
 */
@Composable
fun SavedLibraryScreen(
    onBack: () -> Unit,
    onOpenRoom: (conversationId: String, messageId: String) -> Unit,
    viewModel: SavedLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var unsaveTarget by remember { mutableStateOf<SavedItem?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Saved",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (state.items.isEmpty()) "Your kept messages" else "${state.items.size} ${if (state.items.size == 1) "message" else "messages"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BasicField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search saved messages…",
                    leading = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.loading && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PulsePalette.Emerald)
                    }
                }
                state.error != null && state.items.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    state.error ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.width(10.dp))
                                TextButton(onClick = viewModel::refresh) { Text("Retry") }
                            }
                        }
                    }
                }
                state.items.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = PulsePalette.Amber,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Nothing saved yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Long-press any message in a chat, then Save",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.message.id }) { item ->
                            SavedRow(
                                item = item,
                                viewerId = viewModel.viewerId,
                                onOpen = { onOpenRoom(item.conversationId, item.message.id) },
                                onUnsave = { unsaveTarget = item },
                            )
                        }
                    }
                }
            }
        }
    }

    unsaveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { unsaveTarget = null },
            title = { Text("Remove from saved?") },
            text = { Text("This message will leave your saved library. The original chat is untouched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unsave(target.message.id)
                        unsaveTarget = null
                    },
                ) {
                    Text("Remove", color = PulsePalette.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { unsaveTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SavedRow(
    item: SavedItem,
    viewerId: String?,
    onOpen: () -> Unit,
    onUnsave: () -> Unit,
) {
    val message = item.message
    val mine = message.authorId == viewerId
    val convName = item.conversationName ?: "chat"
    val title = if (mine) "You in $convName" else "${message.authorName.ifBlank { "Someone" }} in $convName"
    val prefix = when {
        message.imagePath != null -> "🖼 "
        message.audioPath != null -> "🎤 "
        message.filePath != null -> "📎 "
        else -> ""
    }
    val base = when {
        message.body.isNotBlank() -> message.body
        message.imagePath != null -> "Photo"
        message.audioPath != null -> "Voice note"
        else -> message.fileName ?: "File"
    }
    val excerpt = if (base.length > 64) base.take(64) + "…" else base

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PulsePalette.EmeraldDeep,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        savedDateLabel(item.savedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    prefix + excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onUnsave, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Unsave",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** "14:32" today, "Mar 3" older — list-row rhythm (no wire ISO here, epoch ms). */
private fun savedDateLabel(savedAtMs: Long): String {
    if (savedAtMs <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(savedAtMs).atZone(zone)
    return if (local.toLocalDate() == LocalDate.now()) {
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        local.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

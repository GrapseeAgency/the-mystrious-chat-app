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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.SavedItem
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.EmberPalette
import app.pulse.ui.PulseIcons
import app.pulse.ui.PulsePalette
import app.pulse.ui.LocalPulseUiTheme
import app.pulse.ui.emberBackdrop
import app.pulse.ui.pulseTabBackdrop
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bounded server/offline saved pages, current-page filtering, durable explicit
 * removal and exact-message navigation. Partial pages never clear the library. */
@HiltViewModel
class SavedLibraryViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val items: List<SavedItem> = emptyList(),
        val query: String = "",
        val previous: Boolean = false,
        val next: Boolean = false,
        val cached: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val syncStatus = repo.observeSavedSyncStatus()
    val viewerId: String? get() = repo.viewerId

    private var all: List<SavedItem> = emptyList()
    private val selected = MutableStateFlow<List<String>?>(null)
    private var cursor: String? = null
    private var nextCursor: String? = null
    private val previous = mutableListOf<String?>()
    private var request = 0L
    private var ownerEpoch: String? = null
    fun page(older: Boolean) {
        if (_state.value.loading) return
        if (older) { val next = nextCursor ?: return; previous.add(cursor); cursor = next }
        else { if (previous.isEmpty()) return; cursor = previous.removeAt(previous.lastIndex) }
        _state.value = _state.value.copy(loading = true)
        selected.value = emptyList(); all = emptyList(); applyFilter(); refresh()
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private val liveRefresh = app.pulse.domain.sync.CoalescedRefresh(viewModelScope) { refresh(); loadJob?.join() }
    init {
        viewModelScope.launch {
            repo.events().collect { event ->
                if (event is app.pulse.domain.repository.PulseEvent.SavedChanged && cursor == null) liveRefresh.request()
                if (event is app.pulse.domain.repository.PulseEvent.PrivateFeedsChanged) {
                    request++; selected.value = emptyList(); all = emptyList(); applyFilter()
                    _state.value = _state.value.copy(loading = true)
                    liveRefresh.request()
                }
            }
        }
        refresh()
        viewModelScope.launch {
            selected.flatMapLatest { repo.observeSavedPage(it) }.collect { rows ->
                all = rows
                applyFilter()
            }
        }
    }

    fun repairFirstPage() { if (cursor == null && !_state.value.loading) liveRefresh.request() }

    fun refresh() {
        val epoch = repo.attachmentSessionKey() ?: return
        val owned = repo.boundToCurrentAccount()
        if (ownerEpoch != epoch) { cursor = null; nextCursor = null; previous.clear(); selected.value = null; ownerEpoch = epoch }
        val ticket = ++request
        loadJob = viewModelScope.launch {
            if (repo.attachmentSessionKey() != epoch) return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val page = owned.savedLibraryPage(cursor).getOrThrow()
                if (repo.attachmentSessionKey() == epoch && request == ticket) {
                    nextCursor = page.nextCursor
                    selected.value = page.items.map { it.message.id }
                    all = page.items; applyFilter()
                    _state.value = _state.value.copy(loading = false, previous = previous.isNotEmpty(), next = page.hasMore, cached = page.cached)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                if (repo.attachmentSessionKey() == epoch && request == ticket) {
                    if ((failure as? app.pulse.domain.model.PulseApiException)?.status in listOf(401, 403, 404)) {
                        selected.value = emptyList(); all = emptyList(); applyFilter()
                    }
                    _state.value = _state.value.copy(loading = false, previous = previous.isNotEmpty(), error = failure.message ?: "Couldn't load your saved messages")
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        applyFilter()
    }

    fun unsave(messageId: String) {
        val epoch = repo.attachmentSessionKey() ?: return
        val owned = repo.boundToCurrentAccount()
        viewModelScope.launch {
            if (repo.attachmentSessionKey() != epoch) return@launch
            try {
                owned.unsaveMessage(messageId).getOrThrow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch) _state.value = _state.value.copy(error = error.message ?: "Couldn't remove from saved")
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
                        (item.message.integrationLabel ?: item.message.authorName).contains(q, ignoreCase = true) ||
                        (item.conversationName ?: "").contains(q, ignoreCase = true)
                }
            },
        )
    }
}

/**
 * Saved library - the Wave 2 dock "Saved" destination. Honest states end to
 * end: loading spinner, error card with Retry, empty state (Star + hint),
 * searchable rows with kind-aware snippets, unsave with confirm, and tap →
 * open the room jumped to the original message.
 */
@Composable
fun SavedLibraryScreen(
    onBack: () -> Unit,
    onOpenRoom: (conversationId: String, messageId: String) -> Unit,
    viewModel: SavedLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle(initialValue = null)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                viewModel.repairFirstPage()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    var unsaveTarget by remember { mutableStateOf<SavedItem?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .pulseTabBackdrop(LocalPulseUiTheme.current)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (state.cached) Text("Cached saved messages · reopen to refresh online", modifier = Modifier.padding(12.dp))
        syncStatus?.let { Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Surface(color = Color.Transparent) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(PulseIcons.ChevronLeft, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Saved",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
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
                    placeholder = "Filter this saved page…",
                    leading = {
                        Icon(PulseIcons.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }
        }

        FeedPageButtons(state.previous, state.next, state.loading, { viewModel.page(false) }, { viewModel.page(true) })
        Box(Modifier.weight(1f)) {
            when {
                state.loading && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EmberPalette.Amber)
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
                            PulseIcons.Star,
                            contentDescription = null,
                            tint = EmberPalette.Amber,
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
    val sender = message.integrationLabel ?: if (mine) "You" else message.authorName.ifBlank { "Someone" }
    val title = "$sender in $convName"
    // R35 Neo - plain kind prefixes (the old glyph prefixes are gone).
    val prefix = when {
        message.imagePath != null -> ""
        message.audioPath != null -> ""
        message.filePath != null -> ""
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
                    PulseIcons.Trash,
                    contentDescription = "Unsave",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** "14:32" today, "Mar 3" older - list-row rhythm (no wire ISO here, epoch ms). */
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

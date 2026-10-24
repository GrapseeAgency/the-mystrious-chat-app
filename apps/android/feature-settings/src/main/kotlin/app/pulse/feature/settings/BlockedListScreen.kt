package app.pulse.feature.settings

import app.pulse.ui.PulseIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class BlockedListViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val rows: List<app.pulse.domain.model.BlockedAccount> = emptyList(),
        val error: Boolean = false,
        val busyId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var ownerEpoch: String? = repo.attachmentSessionKey()
    private var readTicket = java.util.UUID.randomUUID()
    private var actionTicket = java.util.UUID.randomUUID()

    init { refresh() }

    private fun owner(): String? {
        val epoch = repo.attachmentSessionKey()
        if (epoch != ownerEpoch) {
            ownerEpoch = epoch; readTicket = java.util.UUID.randomUUID(); actionTicket = java.util.UUID.randomUUID()
            _state.value = State()
        }
        return epoch
    }

    fun refresh() {
        val epoch = owner() ?: return
        if (_state.value.busyId != null) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID()
        readTicket = ticket
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            try {
                val rows = owned.blockedAccounts().getOrThrow()
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() != epoch || readTicket != ticket) return@launch
                _state.value = _state.value.copy(rows = rows, error = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch && readTicket == ticket)
                    _state.value = _state.value.copy(error = true)
            } finally {
                if (repo.attachmentSessionKey() == epoch && readTicket == ticket)
                    _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun unblock(id: String) {
        val epoch = owner() ?: return
        if (_state.value.busyId != null || _state.value.rows.none { it.id == id }) return
        val owned = repo.boundToCurrentAccount()
        val ticket = java.util.UUID.randomUUID()
        actionTicket = ticket; readTicket = java.util.UUID.randomUUID()
        _state.value = _state.value.copy(busyId = id, loading = false)
        viewModelScope.launch {
            try {
                owned.unblock(id).getOrThrow()
                coroutineContext.ensureActive()
                if (repo.attachmentSessionKey() != epoch || actionTicket != ticket) return@launch
                _state.value = _state.value.copy(rows = _state.value.rows.filterNot { it.id == id }, error = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repo.attachmentSessionKey() == epoch && actionTicket == ticket)
                    _state.value = _state.value.copy(error = true)
            } finally {
                if (repo.attachmentSessionKey() == epoch && actionTicket == ticket)
                    _state.value = _state.value.copy(busyId = null)
            }
        }
    }

}

@Composable
fun BlockedListScreen(
    onBack: () -> Unit,
    viewModel: BlockedListViewModel = hiltViewModel(),
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
                Text("Blocked accounts", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    when {
                        state.loading -> "Loading…"
                        state.error -> "Could not confirm blocked accounts"
                        state.rows.isEmpty() -> "No blocked accounts"
                        else -> "${state.rows.size} account" + if (state.rows.size == 1) " blocked" else "s blocked"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        if (state.error) {
            Text("Blocked accounts could not be confirmed. Known rows are retained.", color = MaterialTheme.colorScheme.error)
            TextButton(enabled = state.busyId == null, onClick = viewModel::refresh) { Text("Retry list") }
        }
        when {
            state.loading && state.rows.isEmpty() -> Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

            state.rows.isEmpty() && !state.error -> Column(Modifier.padding(20.dp)) {
                Text("Nobody is blocked. Blocked accounts cannot message you in direct chats.", fontSize = 13.sp)
            }

            else -> LazyColumn {
                items(state.rows, key = { it.id }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulseAvatar(name = row.name, colorHex = row.color, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                "Blocked " + (row.blockedAtIso?.let { blockedStamp(it) } ?: ""),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        if (state.busyId == row.id) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = { viewModel.unblock(row.id) },
                                enabled = state.busyId == null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                shape = RoundedCornerShape(999.dp),
                            ) { Text("Unblock") }
                        }
                    }
                }
            }
        }
    }
}

private fun blockedStamp(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault("")

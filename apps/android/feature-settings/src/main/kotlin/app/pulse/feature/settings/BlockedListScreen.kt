package app.pulse.feature.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = repo.blockedAccounts()
            _state.value = State(
                loading = false,
                rows = result.getOrElse { emptyList() },
                error = result.isFailure,
            )
        }
    }

    fun unblock(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = id)
            repo.unblock(id)
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(busyId = null) }
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Blocked accounts", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    when {
                        state.loading -> "Loading…"
                        state.rows.isEmpty() -> "No blocked accounts"
                        else -> "${state.rows.size} account" + if (state.rows.size == 1) " blocked" else "s blocked"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        when {
            state.loading -> Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

            state.error -> Column(Modifier.padding(20.dp)) {
                Text("Could not load the list.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("Try again") }
            }

            state.rows.isEmpty() -> Column(Modifier.padding(20.dp)) {
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

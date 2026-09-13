package app.pulse.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.model.User
import app.pulse.ui.PulseAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val matches: List<User> = emptyList(),
        val total: Int = 0,
        val busyId: String? = null,
        val error: String? = null,
    )

    private val query = MutableStateFlow("")
    private val busyId = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val roster = kotlinx.coroutines.flow.flow {
        emit(repo.users().getOrElse { emptyList() })
    }

    val state: StateFlow<UiState> = combine(
        query.debounce(120),
        roster,
        busyId,
        error,
    ) { q, users, busy, err ->
        val needle = q.trim().lowercase()
        val matches = if (needle.isEmpty()) {
            users
        } else {
            users.filter {
                it.name.lowercase().contains(needle) ||
                    (it.handle ?: "").lowercase().contains(needle) ||
                    (it.bio ?: "").lowercase().contains(needle)
            }
        }
        UiState(query = q, matches = matches, total = users.size, busyId = busy, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun message(user: User, onReady: (Conversation) -> Unit) {
        viewModelScope.launch {
            busyId.value = user.id
            error.value = null
            repo.createDm(user.id)
                .onSuccess { conversation ->
                    busyId.value = null
                    onReady(conversation)
                }
                .onFailure { e ->
                    busyId.value = null
                    error.value = e.message ?: "Could not open the direct chat — try again"
                }
        }
    }

    fun consumeError() {
        error.value = null
    }
}

@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: AddContactViewModel = hiltViewModel(),
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Add contact", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    if (state.total == 0) "New Pulse members show up here the moment they join."
                    else "Everyone",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search people by name or handle…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.matches.size} match" + if (state.matches.size == 1) "" else "es",
            Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        state.error?.let { message ->
            Text(
                message,
                Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (state.total == 0) {
            Column(Modifier.padding(24.dp)) {
                Text("No one to add yet", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "New Pulse members show up here the moment they join.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else if (state.matches.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text("No matches", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nobody here for “${state.query}”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.matches, key = { it.id }) { user ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.message(user) { } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulseAvatar(name = user.name, colorHex = user.color, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                user.handle?.let { "@$it" } ?: "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        if (state.busyId == user.id) {
                            CircularProgressIndicator(Modifier.height(20.dp).width(20.dp))
                        } else {
                            Button(onClick = { viewModel.message(user) { conversation -> onOpenRoom(conversation.id) } }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, Modifier.height(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Message")
                            }
                        }
                    }
                }
            }
        }
    }
}

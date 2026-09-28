package app.pulse.android

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import app.pulse.feature.chat.MediaSupport
import app.pulse.ui.PulseAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * R10-a — the share-in payload MainActivity forwards from [ShareInActivity].
 * Text rides [text], an image rides [stream] (a caller-granted content:// Uri
 * — the app can read it: the grant belongs to OUR package).
 */
data class ShareInPayload(val text: String? = null, val stream: Uri? = null) {
    val isImage: Boolean get() = stream != null
    val isText: Boolean get() = !text.isNullOrBlank()
}

/**
 * R10-a — picker + sender state for the share-in sheet. The conversation list
 * is the SAME live source the chats surface uses (repo.observeConversations);
 * text shares go through repo.sendMessage (the offline-queueable path the
 * room composer uses), image shares through the EXISTING staging pipeline
 * (MediaSupport.imageToDataUrl → repo.uploadMedia → repo.sendMediaMessage —
 * media is online-only by spec and surfaces honest errors, never fake rows).
 */
@HiltViewModel
class ShareInViewModel @Inject constructor(
    private val repo: PulseRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed interface Progress {
        data object Idle : Progress
        data object Sending : Progress
        data class Sent(val conversationId: String) : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun sendText(conversationId: String, text: String) {
        _progress.value = Progress.Sending
        viewModelScope.launch {
            repo.sendMessage(conversationId, text.trim())
                .onSuccess { _progress.value = Progress.Sent(conversationId) }
                .onFailure { _progress.value = Progress.Failed(it.message ?: "Couldn't send") }
        }
    }

    fun sendImage(conversationId: String, uri: Uri) {
        _progress.value = Progress.Sending
        viewModelScope.launch {
            MediaSupport.imageToDataUrl(context, uri)
                .onSuccess { dataUrl ->
                    repo.uploadMedia(dataUrl)
                        .onSuccess { path ->
                            repo.sendMediaMessage(
                                conversationId = conversationId,
                                body = "",
                                imagePath = path,
                            )
                                .onSuccess { _progress.value = Progress.Sent(conversationId) }
                                .onFailure { _progress.value = Progress.Failed(it.message ?: "Couldn't send the photo") }
                        }
                        .onFailure { _progress.value = Progress.Failed(it.message ?: "Upload failed") }
                }
                .onFailure { _progress.value = Progress.Failed(it.message ?: "Couldn't read the shared image") }
        }
    }

    fun consumeProgress() {
        _progress.value = Progress.Idle
    }
}

/**
 * R10-a — the share-in sheet at shell level (ModalBottomSheet, the house
 * sheet idiom). Lists recent conversations; picking one sends the payload
 * through the pipeline above, then opens the room so the send is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareInSheet(
    payload: ShareInPayload,
    onDismiss: () -> Unit,
    onOpenRoom: (String) -> Unit,
    viewModel: ShareInViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var sendingTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(progress) {
        when (val p = progress) {
            is ShareInViewModel.Progress.Sent -> {
                onOpenRoom(p.conversationId)
                viewModel.consumeProgress()
                onDismiss()
            }
            is ShareInViewModel.Progress.Failed -> sendingTo = null
            else -> Unit
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text("Share to", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    payload.isImage -> "Photo"
                    else -> payload.text.orEmpty().take(120)
                },
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            (progress as? ShareInViewModel.Progress.Failed)?.let { failure ->
                Text(
                    failure.message,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (conversations.isEmpty()) {
                Text(
                    "No conversations yet — create one from the Chats tab.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                LazyColumn(Modifier.height(340.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        val busy = sendingTo == conversation.id && progress is ShareInViewModel.Progress.Sending
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    sendingTo = conversation.id
                                    if (payload.isImage) {
                                        viewModel.sendImage(conversation.id, payload.stream!!)
                                    } else {
                                        viewModel.sendText(conversation.id, payload.text.orEmpty())
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PulseAvatar(
                                name = conversation.title,
                                colorHex = conversation.avatar,
                                size = 38.dp,
                                isGroup = conversation.kind == Conversation.Kind.GROUP,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conversation.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                conversation.lastMessagePreview?.let { preview ->
                                    Text(
                                        preview,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

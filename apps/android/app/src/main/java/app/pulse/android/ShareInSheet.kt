package app.pulse.android

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
import app.pulse.ui.PulseAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/**
 * R10-a/R42 - the share-in payload. Text rides [text]; a photo or document
 * rides its PREPARED data URL ([dataUrl]) from the durable share-in inbox -
 * never a revocable content:// grant. [kind] is "text" | "image" | "file".
 */
data class ShareInPayload(
    val text: String? = null,
    val stream: android.net.Uri? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: String = if (stream != null) "image" else "text",
    val dataUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
) {
    val isImage: Boolean get() = kind == "image" || (kind == "text" && stream != null)
    val isDocument: Boolean get() = kind == "file"
    val isText: Boolean get() = !text.isNullOrBlank()

    companion object {
        /** An inbox entry is already prepared; no URI grant is involved. */
        fun from(entry: ShareInInbox.Entry) = ShareInPayload(
            id = entry.id, kind = entry.kind, text = entry.text,
            dataUrl = entry.dataUrl, fileName = entry.fileName, fileSize = entry.fileSize)
    }
}

/**
 * R10-a - picker + sender state for the share-in sheet. The conversation list
 * is the SAME live source the chats surface uses (repo.observeConversations);
 * every share goes through the EXISTING durable staging pipeline
 * (repo.queueSharedImage / queueSharedDocument / SendMessageUseCase). Raw
 * bytes commit before upload; no room attachment draft is overwritten. Only
 * a durable queue success (or the user's explicit Remove) consumes the
 * inbox entry.
 */
@HiltViewModel
class ShareInViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed interface Progress {
        data object Idle : Progress
        data object Sending : Progress
        data class Failed(val message: String) : Progress
    }

    fun delivery() = app.pulse.domain.sync.SharedContentDelivery(repo)
}

/**
 * R10-a - the share-in sheet at shell level (ModalBottomSheet, the house
 * sheet idiom). Lists recent conversations; picking one queues the prepared
 * payload through the durable pipeline above, then opens the room so the
 * send is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareInSheet(
    payload: ShareInPayload,
    onDismiss: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onConsumed: () -> Unit = {},
    onRemoved: () -> Unit = {},
    viewModel: ShareInViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.key(payload.id) {
        OwnedShareInSheet(payload, onDismiss, onOpenRoom, onConsumed, onRemoved, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnedShareInSheet(payload: ShareInPayload, onDismiss: () -> Unit, onOpenRoom: (String) -> Unit, onConsumed: () -> Unit, onRemoved: () -> Unit, viewModel: ShareInViewModel) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val delivery = remember { viewModel.delivery() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var progress by remember { mutableStateOf<ShareInViewModel.Progress>(ShareInViewModel.Progress.Idle) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    val current = delivery.isCurrent()
    LaunchedEffect(current) { if (!current) onDismiss() }
    if (!current) return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text("Share to", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    payload.isImage -> "Photo"
                    payload.isDocument -> payload.fileName ?: "Document"
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
                    "No conversations yet - create one from the Chats tab.",
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
                                .clickable(enabled = progress !is ShareInViewModel.Progress.Sending) {
                                    if (progress is ShareInViewModel.Progress.Sending) return@clickable
                                    sendingTo = conversation.id
                                    progress = ShareInViewModel.Progress.Sending
                                    scope.launch {
                                        try {
                                            // R42 - the payload's bytes were prepared at share
                                            // arrival (inbox); delivery only queues them durably.
                                            val result = when {
                                                payload.isDocument -> delivery.document(
                                                    conversation.id,
                                                    checkNotNull(payload.dataUrl),
                                                    checkNotNull(payload.fileName),
                                                    payload.fileSize ?: 0L,
                                                )
                                                payload.isImage -> delivery.image(conversation.id) {
                                                    Result.success(checkNotNull(payload.dataUrl))
                                                }
                                                else -> delivery.text(conversation.id, payload.text.orEmpty())
                                            }
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                            if (!delivery.isCurrent()) return@launch
                                            result.fold(onSuccess = { onOpenRoom(conversation.id); onConsumed() },
                                                onFailure = { progress = ShareInViewModel.Progress.Failed(it.message ?: "Couldn't queue this share. Retry when ready.") })
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                                        } catch (failure: Exception) {
                                            if (delivery.isCurrent()) progress = ShareInViewModel.Progress.Failed(failure.message ?: "Couldn't queue this share")
                                        }
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
            // R42 - explicit Remove is one of the only two ways an inbox entry
            // is consumed (the other is a durable queue success).
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = progress !is ShareInViewModel.Progress.Sending) { onRemoved() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Remove this share", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

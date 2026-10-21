package app.pulse.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pulse.domain.model.Message
import app.pulse.domain.sync.hasReadViewportOverlap
import app.pulse.domain.sync.visibleThreadReadBoundary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A thread is a reading surface, not permission to read every cached reply. */
@Composable
fun ThreadReadReceipts(
    ownerKey: Any,
    enabled: Boolean,
    connected: Boolean,
    root: Message?,
    replies: List<Message>,
    listState: LazyListState,
    onRead: suspend (String) -> Result<Unit>,
    onActive: () -> (() -> Unit) = { {} },
) {
    val lifecycle = LocalLifecycleOwner.current
    val minimumPixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    val rows by rememberUpdatedState(root to replies)
    val reader by rememberUpdatedState(onRead)
    val acquireAttention by rememberUpdatedState(onActive)
    LaunchedEffect(ownerKey, root?.id, enabled, connected, listState, lifecycle, minimumPixels) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!enabled || rows.first == null) return@repeatOnLifecycle
            val releaseAttention = acquireAttention()
            fun currentBoundary(): String? {
                val (parent, messages) = rows
                val layout = listState.layoutInfo
                val visible = layout.visibleItemsInfo.filter {
                    hasReadViewportOverlap(it.offset, it.size, layout.viewportStartOffset,
                        layout.viewportEndOffset, minimumPixels)
                }.mapNotNull { if (it.key == "thread-parent") parent?.id else it.key as? String }.toSet()
                return visibleThreadReadBoundary(parent, messages, visible)
            }
            try {
                snapshotFlow { currentBoundary() }.collectLatest { id ->
                    if (id == null) return@collectLatest
                    delay(600)
                    repeat(3) { attempt ->
                        // Expiry may happen without a database/layout emission.
                        if (currentBoundary() != id) return@collectLatest
                        if (reader(id).isSuccess) return@collectLatest
                        delay(1_500L * (attempt + 1))
                    }
                }
            } finally { releaseAttention() }
        }
    }
}

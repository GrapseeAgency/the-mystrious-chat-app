package app.pulse.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
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
import app.pulse.domain.sync.visibleThreadReadIds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.coroutineScope

/** A thread is a reading surface, not permission to read every cached reply. */
@Composable
fun ThreadReadReceipts(
    ownerKey: Any,
    enabled: Boolean,
    connected: Boolean,
    root: Message?,
    replies: List<Message>,
    listState: LazyListState,
    onRead: (String) -> app.pulse.domain.sync.VisibleReadIntent,
    onActive: () -> (() -> Unit) = { {} },
) {
    val intents = remember(ownerKey, root?.id) { mutableMapOf<String, app.pulse.domain.sync.VisibleReadIntent>() }
    val lifecycle = LocalLifecycleOwner.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    val nativeReadMenus = app.pulse.ui.rememberNativeReadMenus()
    val nativeMenuRevision = nativeReadMenus.value.revision
    val menuObscured = nativeReadMenus.value.obscured
    val windowFocused = window.isWindowFocused
    val minimumPixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    val rows by rememberUpdatedState(root to replies)
    val reader by rememberUpdatedState(onRead)
    val acquireAttention by rememberUpdatedState(onActive)
    LaunchedEffect(ownerKey, root?.id, enabled, connected, listState, lifecycle, minimumPixels, windowFocused, menuObscured, nativeMenuRevision) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!enabled || !window.isWindowFocused || nativeReadMenus.value.obscured || rows.first == null) return@repeatOnLifecycle
            val releaseAttention = acquireAttention()
            fun currentBoundary(): List<String> {
                if (!window.isWindowFocused || nativeReadMenus.value.obscured || nativeReadMenus.value.revision != nativeMenuRevision) return emptyList()
                val (parent, messages) = rows
                val layout = listState.layoutInfo
                val visible = layout.visibleItemsInfo.filter {
                    hasReadViewportOverlap(it.offset, it.size, layout.viewportStartOffset,
                        layout.viewportEndOffset, minimumPixels)
                }.mapNotNull { if (it.key == "thread-parent") parent?.id else it.key as? String }.toSet()
                return visibleThreadReadIds(parent, messages, visible)
            }
            try {
                coroutineScope {
                    val reads = app.pulse.domain.sync.VisibleReadJobs(this,
                        isCurrent = { it in currentBoundary() },
                        send = { id -> intents.getOrPut(id) { reader(id) }.send() })
                    snapshotFlow { currentBoundary() }.collect { reads.update(it.toSet()) }
                }
            } finally { releaseAttention() }
        }
    }
}

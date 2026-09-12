package app.pulse.feature.stories

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.PulseEndpoints
import app.pulse.ui.PulseAvatar
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val StageBlack = Color(0xFF000000)
private val BarTrack = Color.White.copy(alpha = 0.32f)

/** {gateway}/api/uploads/{path} — the image-story source (spec §wire). Shared with the composer. */
internal fun storyImageUrl(imagePath: String): String =
    PulseEndpoints.http("/api/uploads/" + imagePath.removePrefix("/"))

/**
 * Full-screen story viewer (Wave 4). A thin renderer over the pure
 * [StoryViewerStateMachine]: 5000ms stories, per-group progress bars,
 * left-32%-prev tap zones, ≥240ms hold-to-pause with a "Paused" pill,
 * drag-down dismiss (>110px or >550px/s), optimistic view marking with a
 * single retry (D6), owner viewers sheet polling every 5s (D7) and a delete
 * confirm strip — the machine handles vanish auto-advance (D3) when the
 * displayed story is deleted underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    startUserId: String?,
    onClose: () -> Unit,
    storiesVm: StoriesViewModel,
) {
    val groups by storiesVm.groups.collectAsStateWithLifecycle()
    val flags by storiesVm.flags.collectAsStateWithLifecycle()
    val machine = remember { StoryViewerStateMachine(startUserId) }
    val state by machine.state.collectAsStateWithLifecycle()
    val viewers by storiesVm.viewers.collectAsStateWithLifecycle()
    val viewersLoading by storiesVm.viewersLoading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var viewersOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Feed → machine: start-position pick, expiry re-filter (D2) and
    // vanish reconciliation (D3) all flow through this one door.
    // Guard: an empty feed BEFORE the first fetch lands is "not loaded yet",
    // not an empty world — feeding it now would auto-close the viewer.
    LaunchedEffect(groups, flags.loadedOnce) {
        if (flags.loadedOnce || groups.isNotEmpty()) {
            machine.on(StoryViewerInput.GroupsUpdated(groups, System.currentTimeMillis()))
        }
    }

    // Playback loop — 5000ms per story. The MACHINE no-ops ticks while
    // paused/dismissed, so this loop never needs to stop-and-restart
    // (a paused→resumed hold would otherwise leave a dead key'd effect:
    // resume must continue from elapsed, never restart).
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16)
            machine.on(StoryViewerInput.Tick(System.currentTimeMillis()))
        }
    }

    // D6: mark non-mine stories as viewed the moment they are DISPLAYED
    // (not after 5s) — optimistic + single retry driven by the machine.
    LaunchedEffect(state.pendingMark?.storyId, state.pendingMark?.attempt) {
        val mark = state.pendingMark ?: return@LaunchedEffect
        storiesVm.markViewed(
            mark.storyId,
            onOk = { count -> machine.on(StoryViewerInput.ViewMarkOk(mark.storyId, count)) },
            onFail = { machine.on(StoryViewerInput.ViewMarkFailed(mark.storyId)) },
        )
    }

    LaunchedEffect(state.dismissed) {
        if (state.dismissed) onClose()
    }

    val current = state.current
    val group = current?.let { state.groups.getOrNull(it.groupIndex) }

    // Drag-down dismiss visuals (>110px or >550px/s — machine constants).
    val dragY = remember { Animatable(0f) }
    val velocityTracker = remember { VelocityTracker() }

    Box(
        Modifier
            .fillMaxSize()
            .background(StageBlack)
            .graphicsLayer {
                translationY = dragY.value
                alpha = 1f - (dragY.value / 600f).coerceIn(0f, 0.5f)
            }
            // Tap zones: left 32% width = previous, rest = next. Press ≥240ms
            // pauses (release resumes); a drag release is NOT a tap.
            .pointerInput(current?.story?.id) {
                detectTapGestures(
                    onPress = { offset ->
                        var holding = false
                        val holdJob = scope.launch {
                            delay(StoryViewerStateMachine.State.HOLD_THRESHOLD_MS)
                            holding = true
                            machine.on(StoryViewerInput.HoldStart(System.currentTimeMillis()))
                        }
                        try {
                            awaitRelease()
                        } finally {
                            holdJob.cancel()
                            if (holding) {
                                machine.on(StoryViewerInput.HoldEnd(System.currentTimeMillis()))
                            } else if (dragY.value > 0.5f) {
                                // drag release — never a zone tap
                            } else if (offset.x < size.width * 0.32f) {
                                machine.on(StoryViewerInput.TapPrev)
                            } else {
                                machine.on(StoryViewerInput.TapNext)
                            }
                        }
                    },
                )
            }
            .pointerInput(current?.story?.id) {
                detectVerticalDragGestures(
                    onDragStart = { machine.on(StoryViewerInput.HoldStart(System.currentTimeMillis())) },
                    onVerticalDrag = { change, amount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { dragY.snapTo((dragY.value + amount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        val velocityY = velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        machine.on(StoryViewerInput.HoldEnd(System.currentTimeMillis()))
                        val shouldDismiss = dragY.value > StoryViewerStateMachine.State.DISMISS_DISTANCE_PX ||
                            velocityY > StoryViewerStateMachine.State.DISMISS_VELOCITY_PXPS
                        if (shouldDismiss) {
                            machine.on(StoryViewerInput.DragDismiss)
                        } else {
                            scope.launch { dragY.animateTo(0f, spring(stiffness = 380f)) }
                        }
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        machine.on(StoryViewerInput.HoldEnd(System.currentTimeMillis()))
                        scope.launch { dragY.animateTo(0f, spring(stiffness = 380f)) }
                    },
                )
            },
    ) {
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
            }

            current.story.isImage -> ImageStage(
                imagePath = current.story.imagePath.orEmpty(),
                caption = current.story.caption,
            )

            else -> TextStage(
                background = current.story.background,
                caption = current.story.caption,
            )
        }

        if (current != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // Progress bars — CURRENT GROUP only (done/active/future).
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    group?.stories.orEmpty().forEachIndexed { i, _ ->
                        val fraction = when {
                            i < current.storyIndexInGroup -> 1f
                            i == current.storyIndexInGroup -> state.progress
                            else -> 0f
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(BarTrack),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Color.White),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ViewerHeader(
                    name = current.userName,
                    color = current.userColor,
                    stamp = storyRelativeTime(current.story.createdAtEpochMs),
                    mine = current.mine,
                    viewCount = current.story.viewCount,
                    confirmDelete = confirmDelete,
                    onOpenViewers = {
                        confirmDelete = false
                        viewersOpen = true
                    },
                    onAskDelete = { confirmDelete = true },
                    onCancelDelete = { confirmDelete = false },
                    onConfirmDelete = {
                        confirmDelete = false
                        // Optimistic removal; the machine auto-advances (D3)
                        // when GroupsUpdated reports the story gone.
                        storiesVm.deleteStory(current.story.id) {}
                    },
                    onClose = onClose,
                )
            }

            if (state.paused) {
                Text(
                    "Paused",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (viewersOpen && current != null) {
        // D7: the sheet polls every ≤5s while open and the count badge
        // (header eye) reflects the fresh list size on close.
        LaunchedEffect(viewersOpen, current.story.id) {
            while (isActive) {
                storiesVm.loadViewers(current.story.id)
                delay(5_000)
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                viewersOpen = false
                storiesVm.resetViewers()
            },
            containerColor = Color(0xFF18181B),
        ) {
            Text(
                "Viewers · ${viewers.size}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (viewers.isEmpty()) {
                Text(
                    if (viewersLoading) "Loading…" else "No views yet",
                    color = Color(0xFFA1A1AA),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    viewers.forEach { viewer ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PulseAvatar(name = viewer.name, colorHex = viewer.color, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(viewer.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                viewer.username?.takeIf { it.isNotBlank() }?.let {
                                    Text("@$it", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                                }
                            }
                            Text(
                                storyRelativeTime(
                                    runCatching { Instant.parse(viewer.viewedAtIso).toEpochMilli() }.getOrDefault(0L),
                                ),
                                color = Color(0xFFA1A1AA),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ViewerHeader(
    name: String,
    color: String?,
    stamp: String,
    mine: Boolean,
    viewCount: Int,
    confirmDelete: Boolean,
    onOpenViewers: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseAvatar(name = name, colorHex = color, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stamp.isNotEmpty()) {
                Text(stamp, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
        }
        if (confirmDelete) {
            // Delete confirm strip (web confirmDelete state parity).
            Text(
                "Delete this status?",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
            HeaderIconButton(onClick = onConfirmDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete status", tint = Color(0xFFFB7185), modifier = Modifier.size(20.dp))
            }
            HeaderIconButton(onClick = onCancelDelete) {
                Text("Keep", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            if (mine) {
                Box(contentAlignment = Alignment.Center) {
                    HeaderIconButton(onClick = onOpenViewers) {
                        Icon(Icons.Filled.RemoveRedEye, contentDescription = "Viewers", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    if (viewCount > 0) {
                        Text(
                            "$viewCount",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(Color(0xFF059669))
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                HeaderIconButton(onClick = onAskDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete status", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            HeaderIconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close stories", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Image story — {gateway}/api/uploads/{path} full-screen CONTAIN + caption pill (≤3 lines). */
@Composable
private fun ImageStage(imagePath: String, caption: String) {
    Box(Modifier.fillMaxSize().background(StageBlack)) {
        coil.compose.AsyncImage(
            model = storyImageUrl(imagePath),
            contentDescription = caption.ifBlank { "Story photo" },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (caption.isNotBlank()) {
            Text(
                caption,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 42.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Text story — full-bleed palette gradient, centered bold white caption, scrollable when long. */
@Composable
private fun TextStage(background: String, caption: String) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().background(brush = StoryPalette.gradient(background))) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                caption,
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 90.dp),
            )
        }
    }
}

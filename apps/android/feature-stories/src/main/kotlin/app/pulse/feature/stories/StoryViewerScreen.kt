package app.pulse.feature.stories

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import app.pulse.ui.EmberPalette
import app.pulse.ui.PulseIcons
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val StageBlack = EmberPalette.BackdropBase
private val BarTrack = Color.White.copy(alpha = 0.32f)

/** {gateway}/api/uploads/{path} - the image-story source (spec §wire). Shared with the composer. */
internal fun storyImageUrl(imagePath: String): String =
    PulseEndpoints.http(if (imagePath.startsWith("/api/uploads/")) imagePath else "/api/uploads/" + imagePath.removePrefix("/"))

/**
 * Full-screen story viewer (Wave 4). A thin renderer over the pure
 * [StoryViewerStateMachine]: 5000ms stories, per-group progress bars,
 * left-32%-prev tap zones, ≥240ms hold-to-pause with a "Paused" pill,
 * drag-down dismiss (>110px or >550px/s), optimistic view marking with a
 * single retry (D6), owner viewers sheet polling every 5s (D7) and a delete
 * confirm strip - the machine handles vanish auto-advance (D3) when the
 * displayed story is deleted underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    startUserId: String?,
    onClose: () -> Unit,
    storiesVm: StoriesViewModel,
) {
    val owner = storiesVm.composerSession()?.owner
    androidx.compose.runtime.key(owner, startUserId) { OwnedStoryViewerScreen(startUserId, onClose, storiesVm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnedStoryViewerScreen(startUserId: String?, onClose: () -> Unit, storiesVm: StoriesViewModel) {
    val session = remember { storiesVm.composerSession() }
    val groups by storiesVm.groups.collectAsStateWithLifecycle()
    val flags by storiesVm.flags.collectAsStateWithLifecycle()
    val machine = remember(startUserId) { StoryViewerStateMachine(startUserId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by machine.state.collectAsStateWithLifecycle()
    val viewers by storiesVm.viewers.collectAsStateWithLifecycle()
    val viewersLoading by storiesVm.viewersLoading.collectAsStateWithLifecycle()
    val viewersError by storiesVm.viewersError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var viewersOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var viewersTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.current?.story?.id) {
        if (viewersTarget != state.current?.story?.id) { viewersOpen = false; viewersTarget = null; storiesVm.resetViewers() }
        if (deleteTarget != state.current?.story?.id) { confirmDelete = false; deleteTarget = null }
    }

    // Feed → machine: start-position pick, expiry re-filter (D2) and
    // vanish reconciliation (D3) all flow through this one door.
    // Guard: an empty feed BEFORE the first fetch lands is "not loaded yet",
    // not an empty world - feeding it now would auto-close the viewer.
    LaunchedEffect(groups, flags.loadedOnce) {
        if (flags.loadedOnce || groups.isNotEmpty()) {
            machine.on(StoryViewerInput.GroupsUpdated(groups, System.currentTimeMillis()))
        }
    }

    val blocked = viewersOpen || confirmDelete || deleting || deleteError != null
    LaunchedEffect(machine, lifecycle, groups, flags.loadedOnce) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (flags.loadedOnce || groups.isNotEmpty()) machine.on(StoryViewerInput.GroupsUpdated(groups, System.currentTimeMillis()))
                delay(1_000)
            }
        }
    }
    // Foreground + modal gate is independent of the hold gesture gate.
    LaunchedEffect(machine, lifecycle, blocked) {
        machine.on(StoryViewerInput.PlaybackBlocked(true, SystemClock.elapsedRealtime()))
        if (blocked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            machine.on(StoryViewerInput.PlaybackBlocked(false, SystemClock.elapsedRealtime()))
            try {
                while (isActive) {
                    machine.on(StoryViewerInput.Tick(SystemClock.elapsedRealtime()))
                    delay(16)
                }
            } finally {
                machine.on(StoryViewerInput.PlaybackBlocked(true, SystemClock.elapsedRealtime()))
            }
        }
    }

    // D6: mark non-mine stories as viewed the moment they are DISPLAYED
    // (not after 5s); confirmation and the single retry are driven by the machine.
    LaunchedEffect(state.pendingMark, lifecycle, blocked) {
        val mark = state.pendingMark ?: return@LaunchedEffect
        if (blocked || session == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val result = storiesVm.markViewed(mark.storyId, session)
            currentCoroutineContext().ensureActive()
            result.fold(onSuccess = { machine.on(StoryViewerInput.ViewMarkOk(mark.storyId, it)) },
                onFailure = { machine.on(StoryViewerInput.ViewMarkFailed(mark.storyId)) })
        }
    }

    LaunchedEffect(state.dismissed) {
        if (state.dismissed) onClose()
    }

    val current = state.current
    val group = current?.let { state.groups.getOrNull(it.groupIndex) }

    // Drag-down dismiss visuals (>110px or >550px/s - machine constants).
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
                            machine.on(StoryViewerInput.HoldStart(SystemClock.elapsedRealtime()))
                        }
                        var released = false
                        try {
                            released = tryAwaitRelease()
                        } finally {
                            holdJob.cancel()
                            if (holding) {
                                machine.on(StoryViewerInput.HoldEnd(SystemClock.elapsedRealtime()))
                            } else if (!released || viewersOpen || confirmDelete || dragY.value > 0.5f) {
                                // drag release - never a zone tap
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
                    onDragStart = { machine.on(StoryViewerInput.HoldStart(SystemClock.elapsedRealtime())) },
                    onVerticalDrag = { change, amount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { dragY.snapTo((dragY.value + amount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        val velocityY = velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        machine.on(StoryViewerInput.HoldEnd(SystemClock.elapsedRealtime()))
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
                        machine.on(StoryViewerInput.HoldEnd(SystemClock.elapsedRealtime()))
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
                // Progress bars - CURRENT GROUP only (done/active/future).
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
                        viewersTarget = current.story.id
                        storiesVm.resetViewers()
                        viewersOpen = true
                    },
                    onAskDelete = { if (!deleting) { deleteTarget = current.story.id; confirmDelete = true } },
                    onCancelDelete = { confirmDelete = false },
                    onConfirmDelete = {
                        confirmDelete = false
                        deleteTarget?.let { id ->
                            if (!deleting && session != null) {
                                deleting = true
                                scope.launch {
                                    try {
                                        storiesVm.deleteStory(id, session).getOrThrow()
                                        currentCoroutineContext().ensureActive()
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (failure: Exception) {
                                        if (storiesVm.acceptsComposer(session)) deleteError = failure.message ?: "Deletion wasn't confirmed. Reconnect and retry."
                                    } finally { if (storiesVm.acceptsComposer(session)) deleting = false }
                                }
                            }
                        }
                        deleteTarget = null
                    },
                    onClose = onClose,
                )
            }

            if (state.paused) {
                Text(
                    if (deleting) "Deleting…" else "Paused",
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

    if (deleteError != null) ModalBottomSheet(onDismissRequest = { deleteError = null }, containerColor = EmberPalette.BubbleOut) {
        Text(deleteError.orEmpty(), color = Color.White, modifier = Modifier.padding(20.dp))
    }
    if (viewersOpen && viewersTarget != null) {
        // D7: the sheet polls every ≤5s while open and the count badge
        // (header eye) reflects the fresh list size on close.
        LaunchedEffect(viewersTarget, lifecycle) {
            val target = viewersTarget ?: return@LaunchedEffect
            if (session == null) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    storiesVm.loadViewers(target, session)
                    delay(5_000)
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                viewersOpen = false
                storiesVm.resetViewers()
            },
            // R35 Neo - elevated carbon sheet fill (locked token #0d1211).
            containerColor = EmberPalette.BubbleOut,
        ) {
            Text(
                "Viewers · ${viewers.size}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            viewersError?.let { Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            if (viewers.isEmpty() && viewersError == null) {
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
                Icon(PulseIcons.Trash, contentDescription = "Delete status", tint = EmberPalette.Signal, modifier = Modifier.size(20.dp))
            }
            HeaderIconButton(onClick = onCancelDelete) {
                Text("Keep", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            if (mine) {
                Box(contentAlignment = Alignment.Center) {
                    HeaderIconButton(onClick = onOpenViewers) {
                        Icon(PulseIcons.Eye, contentDescription = "Viewers", tint = Color.White, modifier = Modifier.size(20.dp))
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
                                .background(Color(0xFFC9762B))
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                HeaderIconButton(onClick = onAskDelete) {
                    Icon(PulseIcons.Trash, contentDescription = "Delete status", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            HeaderIconButton(onClick = onClose) {
                Icon(PulseIcons.X, contentDescription = "Close stories", tint = Color.White, modifier = Modifier.size(22.dp))
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

/** Image story - {gateway}/api/uploads/{path} full-screen CONTAIN + caption pill (≤3 lines). */
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

/** Text story - full-bleed palette gradient, centered bold white caption, scrollable when long. */
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

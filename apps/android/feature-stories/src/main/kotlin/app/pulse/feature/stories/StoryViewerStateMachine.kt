package app.pulse.feature.stories

import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure, JVM-testable state machine behind the full-screen story viewer
 * (no Android imports — the composable is a thin renderer for it).
 *
 * Web-truth behaviors implemented here (stories-sheet.tsx semantics,
 * preserved as behavior, not implementation):
 *  - 5000ms per story (both kinds); progress resumes from elapsed, never
 *    restarts after a pause (web rAF loop with elapsedRef).
 *  - advance crosses group boundaries; auto-closes after the last story;
 *    prev no-ops at the very first story.
 *  - D2 (web defect): expired stories (expiresAt <= now) are dropped BEFORE
 *    flattening — skipping them on advance never needs a network probe.
 *  - D3 (web defect): when the displayed story vanishes underneath (delete /
 *    expiry / server refetch), the machine auto-advances to the nearest
 *    survivor (same flat position clamped) or closes when nothing survives.
 *  - D6 (web defect): optimistic seen-marking — a non-mine story marks
 *    pendingViewMark as soon as it is DISPLAYED; one failure re-queues a
 *    single retry; a second failure gives up (the next feed fetch
 *    reconciles from the server).
 */
/**
 * Every event the viewer screen feeds the machine (nested objects are the
 * concrete inputs — referenced as StoryViewerInput.Tick etc.).
 */
sealed interface StoryViewerInput {
    /** Playback frame — carries wall time; elapsed accrues only while unpaused. */
    data class Tick(val nowMs: Long) : StoryViewerInput
    data object TapNext : StoryViewerInput
    data object TapPrev : StoryViewerInput
    data class HoldStart(val nowMs: Long) : StoryViewerInput
    data class HoldEnd(val nowMs: Long) : StoryViewerInput
    data object DragDismiss : StoryViewerInput
    data class GroupsUpdated(val groups: List<StoryGroup>, val nowMs: Long) : StoryViewerInput
    data class ViewMarkOk(val storyId: String, val viewCount: Int) : StoryViewerInput
    data class ViewMarkFailed(val storyId: String) : StoryViewerInput
}

class StoryViewerStateMachine(
    /** Where the viewer was opened from (rail cell) — first story of that group. */
    private val startUserId: String? = null,
) {

    data class PendingViewMark(val storyId: String, val attempt: Int)

    data class FlatStory(
        val userId: String,
        val userName: String,
        val userColor: String?,
        val mine: Boolean,
        val groupIndex: Int,
        val storyIndexInGroup: Int,
        val story: StoryItem,
    )

    data class State(
        val groups: List<StoryGroup> = emptyList(),
        val flat: List<FlatStory> = emptyList(),
        val index: Int = 0,
        val elapsedMs: Long = 0L,
        val paused: Boolean = false,
        /** drag-down dismiss OR finished the last story — the screen closes on this. */
        val dismissed: Boolean = false,
        val pendingMark: PendingViewMark? = null,
        /** Monotonic counter — screens key effects off it instead of data classes. */
        val revision: Long = 0L,
    ) {
        val current: FlatStory? get() = flat.getOrNull(index)
        /** 0f..1f of the CURRENT story's progress bar. */
        val progress: Float get() = (elapsedMs.toFloat() / DURATION_MS).coerceIn(0f, 1f)

        companion object {
            const val DURATION_MS: Long = 5_000L
            /** press-and-hold ≥240ms = pause (web hold timer). */
            const val HOLD_THRESHOLD_MS: Long = 240L
            /** vertical drag-down dismiss thresholds (web touch-forensics values). */
            const val DISMISS_DISTANCE_PX: Float = 110f
            const val DISMISS_VELOCITY_PXPS: Float = 550f
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set once the first non-empty feed lands and the start position is chosen. */
    private var started = false

    /** Timestamp of the last Tick while unpaused — null = clock not armed yet. */
    private var lastTickAt: Long? = null

    fun on(input: StoryViewerInput) = _state.update { prev -> reduce(prev, input) }

    private fun reduce(prev: State, input: StoryViewerInput): State {
        val base = prev.copy(revision = prev.revision + 1)
        return when (input) {
            is StoryViewerInput.Tick -> onTick(base, input.nowMs)
            is StoryViewerInput.TapNext -> goTo(base, base.index + 1)
            is StoryViewerInput.TapPrev -> if (base.index > 0) goTo(base, base.index - 1) else base
            is StoryViewerInput.HoldStart -> base.copy(paused = true).also { lastTickAt = input.nowMs }
            is StoryViewerInput.HoldEnd -> base.copy(paused = false).also { lastTickAt = input.nowMs }
            is StoryViewerInput.DragDismiss -> base.copy(dismissed = true)
            is StoryViewerInput.GroupsUpdated -> onGroupsUpdated(base, input.groups, input.nowMs)
            is StoryViewerInput.ViewMarkOk -> onViewMarkOk(base, input.storyId, input.viewCount)
            is StoryViewerInput.ViewMarkFailed -> onViewMarkFailed(base, input.storyId)
        }
    }

    private fun onTick(state: State, nowMs: Long): State {
        if (state.paused || state.dismissed || state.current == null) return state
        val last = lastTickAt
        lastTickAt = nowMs
        if (last == null) return state // arm the clock; do not count the first frame
        val elapsed = state.elapsedMs + (nowMs - last).coerceAtLeast(0L)
        return if (elapsed >= State.DURATION_MS) {
            goTo(state, state.index + 1) // auto-advance (closes after the last story)
        } else {
            state.copy(elapsedMs = elapsed)
        }
    }

    /** Move to a flat index; past-the-end closes the viewer (web `next()` semantics). */
    private fun goTo(state: State, target: Int): State {
        if (state.dismissed) return state
        if (target >= state.flat.size) return state.copy(dismissed = true)
        if (target < 0) return state
        lastTickAt = null
        val next = state.copy(index = target, elapsedMs = 0L)
        return next.copy(pendingMark = pendingMarkFor(next))
    }

    private fun onGroupsUpdated(state: State, rawGroups: List<StoryGroup>, nowMs: Long): State {
        // D2: drop expired BEFORE flattening (offline arithmetic, no network).
        val groups = rawGroups.mapNotNull { g ->
            val stories = g.stories.filter { it.expiresAtEpochMs > nowMs }
            if (stories.isEmpty() || g.user == null) null else g.copy(stories = stories, allSeen = stories.all { it.viewedByMe })
        }
        val flat = flatten(groups)

        if (!started) {
            if (flat.isEmpty()) return state.copy(dismissed = true) // nothing live → auto-close
            started = true
            val target = startUserId?.let { id -> flat.indexOfFirst { it.userId == id } } ?: -1
            lastTickAt = null
            val next = state.copy(
                groups = groups,
                flat = flat,
                index = if (target >= 0) target else 0,
                elapsedMs = 0L,
            )
            return next.copy(pendingMark = pendingMarkFor(next))
        }

        val currentId = state.current?.story?.id
        val found = currentId?.let { id -> flat.indexOfFirst { it.story.id == id } } ?: -1
        return when {
            // D3: the displayed story vanished → nearest survivor (same position
            // clamped), or close when nothing survived.
            found < 0 -> {
                if (flat.isEmpty()) {
                    state.copy(groups = groups, flat = flat, dismissed = true)
                } else {
                    lastTickAt = null
                    val next = state.copy(
                        groups = groups,
                        flat = flat,
                        index = state.index.coerceAtMost(flat.lastIndex),
                        elapsedMs = 0L,
                    )
                    next.copy(pendingMark = pendingMarkFor(next))
                }
            }
            // Same story still live — keep position AND elapsed (no restart on refresh).
            else -> {
                val next = state.copy(groups = groups, flat = flat, index = found)
                if (next.current?.story?.viewedByMe == true && next.pendingMark?.storyId == currentId) {
                    next.copy(pendingMark = null)
                } else {
                    next
                }
            }
        }
    }

    /** D6: a successful view POST settles the optimistic mark everywhere. */
    private fun onViewMarkOk(state: State, storyId: String, viewCount: Int): State {
        val flat = state.flat.map {
            if (it.story.id == storyId) it.copy(story = it.story.copy(viewedByMe = true, viewCount = viewCount)) else it
        }
        val groups = state.groups.map { g ->
            g.copy(
                stories = g.stories.map { s ->
                    if (s.id == storyId) s.copy(viewedByMe = true, viewCount = viewCount) else s
                },
                allSeen = if (g.mine) g.allSeen else g.stories.all { s ->
                    s.viewedByMe || s.id == storyId
                },
            )
        }
        return state.copy(
            groups = groups,
            flat = flat,
            pendingMark = state.pendingMark?.takeIf { it.storyId != storyId },
        )
    }

    /** D6: ONE retry — the first failure re-queues, the second gives up. */
    private fun onViewMarkFailed(state: State, storyId: String): State {
        val pending = state.pendingMark ?: return state
        if (pending.storyId != storyId) return state
        return if (pending.attempt == 0) {
            state.copy(pendingMark = pending.copy(attempt = 1))
        } else {
            state.copy(pendingMark = null)
        }
    }

    private fun pendingMarkFor(state: State): PendingViewMark? {
        val cur = state.current ?: return null
        return if (!cur.mine && !cur.story.viewedByMe) PendingViewMark(cur.story.id, attempt = 0) else null
    }

    private fun flatten(groups: List<StoryGroup>): List<FlatStory> = groups.flatMapIndexed { gi, g ->
        val user = g.user
        g.stories.mapIndexed { si, s ->
            FlatStory(
                userId = user?.id ?: "",
                userName = user?.name ?: "",
                userColor = user?.color,
                mine = g.mine,
                groupIndex = gi,
                storyIndexInGroup = si,
                story = s,
            )
        }
    }
}

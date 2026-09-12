package app.pulse.feature.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.StoryCell
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryViewer
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single owner of the stories feed (Wave 4): the ChatsScreen tray rail, the
 * full-screen viewer and the composer all share ONE activity-scoped instance
 * of this VM, so optimistic updates (seen marks, deletes, publishes) show up
 * everywhere at once.
 *
 * Transport = REST only (web parity: TanStack 60s refetchInterval + query
 * invalidations — there are ZERO socket events for stories).
 */
@HiltViewModel
class StoriesViewModel @Inject constructor(
    private val repo: PulseRepository,
    @Suppress("unused") private val prefs: PulsePrefsStore,
) : ViewModel() {

    /**
     * D5 (web defect): error vs empty are DISTINCT states — `error != null`
     * means the transport failed with nothing to show (retry offered);
     * `error == null && loadedOnce && groups empty` is an honest empty feed.
     */
    data class Flags(
        val loading: Boolean = false,
        val loadedOnce: Boolean = false,
        val error: String? = null,
    )

    private val _groups = MutableStateFlow<List<StoryGroup>>(emptyList())
    val groups: StateFlow<List<StoryGroup>> = _groups.asStateFlow()

    private val _flags = MutableStateFlow(Flags())
    val flags: StateFlow<Flags> = _flags.asStateFlow()

    /** Tray cells for the existing ChatsScreen rail (StoryGroup → StoryCell mapping keeps the rail untouched). */
    val cells: StateFlow<List<StoryCell>> = _groups
        .map { list ->
            list.mapNotNull { g ->
                val user = g.user ?: return@mapNotNull null
                StoryCell(
                    userId = user.id,
                    name = user.name,
                    color = user.color,
                    mine = g.mine,
                    unseen = !g.allSeen,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var pollJob: Job? = null

    /** Pull the feed. quiet=false flips the loading flag (pull-to-refresh / first load). */
    fun refresh(quiet: Boolean = false) {
        if (repo.viewerId == null) return
        viewModelScope.launch {
            if (!quiet) _flags.value = _flags.value.copy(loading = true, error = null)
            repo.stories().fold(
                onSuccess = { feed ->
                    _groups.value = feed
                    _flags.value = _flags.value.copy(loading = false, loadedOnce = true, error = null)
                },
                onFailure = { failure ->
                    // Serve the last good feed when there is one (stale-but-real
                    // beats a full-screen error); honest error only when empty.
                    if (_groups.value.isEmpty()) {
                        _flags.value = _flags.value.copy(
                            loading = false,
                            loadedOnce = true,
                            error = failure.message ?: "Could not reach the gateway",
                        )
                    } else {
                        _flags.value = _flags.value.copy(loading = false, loadedOnce = true)
                    }
                },
            )
        }
    }

    /** 60s poll while the shell is active (web refetchInterval 60_000 parity). */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                refresh(quiet = true)
            }
        }
    }

    fun boot() {
        refresh()
        startPolling()
    }

    // ── publish (composer) ──────────────────────────────────────

    fun publishStory(
        caption: String,
        background: String?,
        imagePath: String?,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        viewModelScope.launch {
            repo.createStory(caption, background, imagePath).fold(
                onSuccess = {
                    refresh(quiet = true)
                    onDone(true, null)
                },
                onFailure = { failure ->
                    // Surface the server's own copy ("A status needs a photo or
                    // some text." etc.) — honest errors, never silent drops.
                    onDone(false, failure.message ?: "Could not post your status")
                },
            )
        }
    }

    /** Compressor → data-URL upload leg; returns the gateway imagePath. */
    fun uploadStoryImage(dataUrl: String, onDone: (imagePath: String?) -> Unit) {
        viewModelScope.launch {
            onDone(repo.uploadMedia(dataUrl).getOrNull())
        }
    }

    // ── viewer actions ──────────────────────────────────────────

    /**
     * ONE view-mark attempt per call (the viewer machine drives the single
     * retry — D6). Optimistic: the story flips to viewed locally BEFORE the
     * POST so the ring reacts instantly; the next feed fetch reconciles
     * against server truth.
     */
    fun markViewed(storyId: String, onOk: (Int) -> Unit, onFail: () -> Unit) {
        viewModelScope.launch {
            markViewedLocal(storyId, viewCount = null)
            repo.markStoryViewed(storyId).fold(
                onSuccess = { count ->
                    markViewedLocal(storyId, count)
                    onOk(count)
                },
                onFailure = { onFail() },
            )
        }
    }

    /** Optimistic removal — the viewer's machine reconciles via GroupsUpdated (D3). */
    fun deleteStory(storyId: String, onDone: (success: Boolean) -> Unit) {
        removeStoryLocal(storyId)
        viewModelScope.launch {
            val ok = repo.deleteStory(storyId).isSuccess
            refresh(quiet = true)
            onDone(ok)
        }
    }

    private fun markViewedLocal(storyId: String, viewCount: Int?) {
        _groups.value = _groups.value.map { g ->
            g.copy(
                allSeen = if (g.mine) {
                    g.allSeen
                } else {
                    g.stories.all { it.viewedByMe || it.id == storyId }
                },
                stories = g.stories.map { s ->
                    if (s.id == storyId) {
                        s.copy(viewedByMe = true, viewCount = viewCount ?: s.viewCount)
                    } else {
                        s
                    }
                },
            )
        }
    }

    private fun removeStoryLocal(storyId: String) {
        _groups.value = _groups.value
            .map { g -> g.copy(stories = g.stories.filterNot { it.id == storyId }) }
            .filter { it.stories.isNotEmpty() }
    }

    // ── owner viewers sheet (D7) ────────────────────────────────

    private val _viewers = MutableStateFlow<List<StoryViewer>>(emptyList())
    val viewers: StateFlow<List<StoryViewer>> = _viewers.asStateFlow()

    private val _viewersLoading = MutableStateFlow(false)
    val viewersLoading: StateFlow<Boolean> = _viewersLoading.asStateFlow()

    /** One fetch — the sheet re-issues it every 5s while open (D7 poll loop). */
    fun loadViewers(storyId: String) {
        viewModelScope.launch {
            _viewersLoading.value = true
            repo.storyViewers(storyId).fold(
                onSuccess = { _viewers.value = it },
                onFailure = { /* sheet keeps the last list; count badge stays honest */ },
            )
            _viewersLoading.value = false
        }
    }

    fun resetViewers() {
        _viewers.value = emptyList()
    }
}

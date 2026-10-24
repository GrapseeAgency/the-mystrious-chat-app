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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * of this VM, so confirmed view marks, deletions and refreshed publications
 * reach the tray and viewer together.
 *
 * Transport = REST only (web parity: TanStack 60s refetchInterval + query
 * invalidations - there are ZERO socket events for stories).
 */
@HiltViewModel
class StoriesViewModel @Inject constructor(
    private val repo: PulseRepository,
    @Suppress("unused") private val prefs: PulsePrefsStore,
) : ViewModel() {

    /**
     * D5 (web defect): error vs empty are DISTINCT states - `error != null`
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

    private var feedOwner: String? = null
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null

    /** Pull the feed. quiet=false flips the loading flag (pull-to-refresh / first load). */
    fun refresh(quiet: Boolean = false) {
        val account = repo.boundToCurrentAccount()
        val owner = account.attachmentSessionKey()
        val ticket = ++refreshGeneration
        refreshJob?.cancel()
        if (feedOwner != owner) {
            feedOwner = owner; _groups.value = emptyList(); _flags.value = Flags(); resetViewers()
        }
        if (owner == null) return
        refreshJob = viewModelScope.launch {
            if (!quiet) _flags.value = _flags.value.copy(loading = true, error = null)
            val result = account.stories()
            if (!isActive || ticket != refreshGeneration || repo.attachmentSessionKey() != owner) return@launch
            result.fold(
                onSuccess = { feed ->
                    _groups.value = feed
                    _flags.value = _flags.value.copy(loading = false, loadedOnce = true, error = null)
                },
                onFailure = { failure ->
                    // Serve the last good feed when there is one (stale-but-real
                    // beats a full-screen error); honest error only when empty.
                    if ((failure as? app.pulse.domain.model.PulseApiException)?.kind in setOf("AUTH", "FORBIDDEN", "NOT_FOUND")) _groups.value = emptyList()
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

    // publish (composer)

    class ComposerSession internal constructor(val owner: String, internal val account: PulseRepository)

    fun composerSession(): ComposerSession? {
        val account = repo.boundToCurrentAccount()
        val owner = account.attachmentSessionKey() ?: return null
        return ComposerSession(owner, account).takeIf { acceptsComposer(it) }
    }
    fun acceptsComposer(session: ComposerSession): Boolean = repo.attachmentSessionKey() == session.owner &&
        session.account.attachmentSessionKey() == session.owner

    private suspend fun requireComposer(session: ComposerSession) {
        currentCoroutineContext().ensureActive()
        if (!acceptsComposer(session)) throw CancellationException("The original story account ended")
    }

    /** The calling presentation owns the coroutine; never detach its upload/post into viewModelScope. */
    suspend fun publishStory(caption: String, background: String?, imagePath: String?, session: ComposerSession): Result<Unit> {
        requireComposer(session)
        val result = session.account.createStory(caption, background, imagePath)
        requireComposer(session)
        if (result.isSuccess) refresh(quiet = true)
        return result.map { Unit }
    }

    suspend fun uploadStoryImage(dataUrl: String, session: ComposerSession): Result<String> {
        requireComposer(session)
        val result = session.account.uploadMedia(dataUrl)
        requireComposer(session)
        return result
    }

    // viewer actions

    /** Calls remain in the viewer's coroutine; only acknowledged actions change shared state. */
    suspend fun markViewed(storyId: String, session: ComposerSession): Result<Int> {
        requireComposer(session)
        val result = session.account.markStoryViewed(storyId)
        requireComposer(session)
        result.onSuccess { count ->
            refreshGeneration++; refreshJob?.cancel()
            _flags.value = _flags.value.copy(loading = false)
            markViewedLocal(storyId, count)
        }
        return result
    }

    suspend fun deleteStory(storyId: String, session: ComposerSession): Result<Unit> {
        requireComposer(session)
        val result = session.account.deleteStory(storyId)
        requireComposer(session)
        if (result.isSuccess) {
            refreshGeneration++; refreshJob?.cancel()
            _flags.value = _flags.value.copy(loading = false)
            removeStoryLocal(storyId)
        }
        return result
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

    // owner viewers sheet (D7)

    private val _viewers = MutableStateFlow<List<StoryViewer>>(emptyList())
    val viewers: StateFlow<List<StoryViewer>> = _viewers.asStateFlow()

    private var viewersGeneration = 0L
    private val _viewersLoading = MutableStateFlow(false)
    val viewersLoading: StateFlow<Boolean> = _viewersLoading.asStateFlow()

    private val _viewersError = MutableStateFlow<String?>(null)
    val viewersError: StateFlow<String?> = _viewersError.asStateFlow()
    private var viewersStoryId: String? = null

    /** The sheet owns polling and cancellation. An unavailable list is not an empty-success list. */
    suspend fun loadViewers(storyId: String, session: ComposerSession) {
        requireComposer(session)
        if (viewersStoryId != storyId) { resetViewers(); viewersStoryId = storyId }
        val ticket = ++viewersGeneration
        _viewersLoading.value = true
        val result = session.account.storyViewers(storyId)
        requireComposer(session)
        if (ticket != viewersGeneration) return
        result.fold(onSuccess = { _viewers.value = it; _viewersError.value = null }, onFailure = { failure ->
            if ((failure as? app.pulse.domain.model.PulseApiException)?.kind in setOf("AUTH", "FORBIDDEN", "NOT_FOUND")) _viewers.value = emptyList()
            _viewersError.value = failure.message ?: "Viewers couldn't be loaded. Reconnect to retry."
        })
        _viewersLoading.value = false
    }

    fun resetViewers() {
        viewersGeneration++
        viewersStoryId = null
        _viewersLoading.value = false
        _viewers.value = emptyList()
        _viewersError.value = null
    }

}

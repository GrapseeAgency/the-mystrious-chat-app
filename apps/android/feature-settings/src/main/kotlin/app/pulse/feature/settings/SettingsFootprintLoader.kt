package app.pulse.feature.settings

import app.pulse.domain.model.UserStats
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns the account and request before dispatch; a late response cannot repaint
 * another account's settings or replace a newer refresh. */
class SettingsFootprintLoader(private val repository: PulseRepository, private val scope: CoroutineScope) {
    data class State(val loading: Boolean = false, val stats: UserStats? = null, val error: String? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var job: Job? = null

    fun reset() {
        revision++
        job?.cancel(); job = null
        mutableState.value = State()
    }

    fun refresh(expectedViewer: String?) {
        reset()
        val target = repository.boundToCurrentAccount()
        val owner = target.viewerId
        val epoch = target.attachmentSessionKey()
        if (owner.isNullOrBlank() || owner != expectedViewer || epoch == null) {
            mutableState.value = State(error = "Open your active account to see its stats.")
            return
        }
        val ticket = revision
        fun current() = revision == ticket && target.viewerId == owner && target.attachmentSessionKey() == epoch
        mutableState.value = State(loading = true)
        job = scope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                val stats = target.userStats(owner).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (current()) mutableState.value = State(stats = stats)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) mutableState.value = State(error = error.message ?: "Couldn't load your stats.")
            } finally {
                if (revision == ticket) {
                    job = null
                    if (!current()) mutableState.value = State(error = "The profile session changed. Refresh to retry.")
                }
            }
        }
    }
}

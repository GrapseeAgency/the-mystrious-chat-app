package app.pulse.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Device-session state: viewer identity + appearance prefs (DataStore-backed). */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val prefs: PulsePrefsStore,
    private val repo: PulseRepository,
) : ViewModel() {

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True once the persisted prefs have emitted at least once — gates the
     * onboarding screen so a fresh launch doesn't flash it before DataStore
     * hands back an existing viewer.
     */
    val hydrated: StateFlow<Boolean> = prefs.viewerId
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val viewerName: StateFlow<String?> = prefs.viewerName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val viewerColor: StateFlow<String?> = prefs.viewerColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val fxMode: StateFlow<String> = prefs.fxMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "aurora")

    val darkOverride: StateFlow<String> = prefs.darkOverride
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val reducedMotion: StateFlow<Boolean> = prefs.reducedMotion
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun chooseViewer(id: String, name: String, color: String? = null) {
        viewModelScope.launch {
            prefs.setViewer(id, name, color)
            repo.start(id)
        }
    }

    fun forgetViewer() {
        viewModelScope.launch { prefs.setViewer(null, null) }
    }

    fun bootstrap(viewerId: String?) {
        viewerId?.let { repo.start(it) }
    }

    /** Header theme toggle — cycles system → light → dark (web ThemeToggleButton). */
    fun setDarkOverride(value: String) {
        viewModelScope.launch { prefs.setDarkOverride(value) }
    }

    fun cycleDarkOverride() {
        val next = when (darkOverride.value) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        setDarkOverride(next)
    }
}

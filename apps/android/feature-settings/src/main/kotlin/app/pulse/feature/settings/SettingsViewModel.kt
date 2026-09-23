package app.pulse.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.UserProfile
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wave 8 — the Settings surfaces' state holder (root + all nine sections).
 *
 * Prefs pipeline (web parity with src/lib/prefs.ts): every toggle applies
 * locally FIRST (optimistic), then PATCHes /api/settings; a failed PATCH
 * keeps the local change and raises the honest offline hint. Quiet hours +
 * haptics never ride the server blob (web keeps them in pulse.settings.v1).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {

    // ── viewer identity (Account section) ────────────────────────

    val viewerId: StateFlow<String?> = prefs.viewerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val viewerName: StateFlow<String?> = prefs.viewerName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _viewerProfile = MutableStateFlow<UserProfile?>(null)

    /** Live profile row for the Account card (@handle + avatar). */
    val viewerProfile: StateFlow<UserProfile?> = _viewerProfile.asStateFlow()

    // ── user preferences blob (server-backed) ────────────────────

    val pulsePrefs: StateFlow<WirePulsePrefs> = repo.pulsePrefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, PulseWave8Logic.DEFAULTS)

    private val _prefsOffline = MutableStateFlow(false)

    /** True when the last PATCH failed — the local change STAYS (honest hint). */
    val prefsOffline: StateFlow<Boolean> = _prefsOffline.asStateFlow()

    // ── local-only settings (web pulse.settings.v1 parity) ───────

    val hapticsOn: StateFlow<Boolean> = prefs.hapticsOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val quietHoursOn: StateFlow<Boolean> = prefs.quietHoursOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val quietStart: StateFlow<String> = prefs.quietStart
        .stateIn(viewModelScope, SharingStarted.Eagerly, "22:00")

    val quietEnd: StateFlow<String> = prefs.quietEnd
        .stateIn(viewModelScope, SharingStarted.Eagerly, "07:00")

    /** LOCAL reduced-motion override (drives ambient field + particles NOW). */
    val reducedMotion: StateFlow<Boolean> = prefs.reducedMotion
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Appearance — the existing darkOverride pref (shared with Profile). */
    val darkOverride: StateFlow<String> = prefs.darkOverride
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    /** Appearance — the existing ambient FX pref (shared with Profile). */
    val fxMode: StateFlow<String> = prefs.fxMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "aurora")

    /** R2-C item 3 — the design language (web pulse.uiTheme.v2 value string). */
    val uiTheme: StateFlow<String> = prefs.uiTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "glass")

    /** Real-time & Voice — live relay truth. */
    val connected: StateFlow<Boolean> = repo.observeConnected()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            prefs.viewerId.collect { id ->
                if (id == null) {
                    _viewerProfile.value = null
                } else {
                    _viewerProfile.value = repo.userProfile(id).getOrNull()
                }
            }
        }
    }

    // ── prefs mutations (optimistic local FIRST, then PATCH) ─────

    fun setBubbleRadius(value: String) = updatePrefs(WirePulsePrefs(bubbleRadius = value))
    fun setDensity(value: String) = updatePrefs(WirePulsePrefs(density = value))
    fun setWallpaper(value: String) = updatePrefs(WirePulsePrefs(wallpaper = value))
    fun setNotifPreviews(value: Boolean) = updatePrefs(WirePulsePrefs(notifPreviews = value))
    fun setNotifSound(value: Boolean) = updatePrefs(WirePulsePrefs(notifSound = value))
    fun setNotifVibrate(value: Boolean) = updatePrefs(WirePulsePrefs(notifVibrate = value))
    fun setLastSeenVisible(value: Boolean) = updatePrefs(WirePulsePrefs(lastSeenVisible = value))
    fun setReadReceipts(value: Boolean) = updatePrefs(WirePulsePrefs(readReceipts = value))
    fun setTypingVisible(value: Boolean) = updatePrefs(WirePulsePrefs(typingVisible = value))

    /**
     * Reduced motion — dual-write: the LOCAL device pref drives the ambient
     * field/particles immediately, the server blob keeps cross-platform parity.
     */
    fun setReducedMotion(value: Boolean) {
        viewModelScope.launch { prefs.setReducedMotion(value) }
        updatePrefs(WirePulsePrefs(reducedMotion = value))
    }

    private fun updatePrefs(patch: WirePulsePrefs) {
        viewModelScope.launch {
            repo.updatePulsePrefs(patch)
                .onSuccess { _prefsOffline.value = false }
                .onFailure { _prefsOffline.value = true } // local change KEPT
        }
    }

    // ── local settings setters ───────────────────────────────────

    fun setHapticsOn(value: Boolean) {
        viewModelScope.launch { prefs.setHapticsOn(value) }
    }

    fun setQuietHoursOn(value: Boolean) {
        viewModelScope.launch { prefs.setQuietHoursOn(value) }
    }

    fun setQuietStart(value: String) {
        viewModelScope.launch { prefs.setQuietStart(value) }
    }

    fun setQuietEnd(value: String) {
        viewModelScope.launch { prefs.setQuietEnd(value) }
    }

    fun setDarkOverride(value: String) {
        viewModelScope.launch { prefs.setDarkOverride(value) }
    }

    fun setFxMode(value: String) {
        viewModelScope.launch { prefs.setFxMode(value) }
    }

    /** R2-C item 3 — design-language swap; the shell re-reads it live. */
    fun setUiTheme(value: String) {
        viewModelScope.launch { prefs.setUiTheme(value) }
    }

    /** Quiet-hours math (verbatim web port) evaluated against the CURRENT inputs. */
    fun isQuietNow(): Boolean = PulseWave8Logic.isQuietHoursNow(
        quietHoursOn.value,
        quietStart.value,
        quietEnd.value,
    )

    // ── Chat section — Drafts & Outbox manager (fully offline) ───

    data class DraftRow(val conversationId: String, val title: String, val text: String)

    data class OutboxRow(
        val clientId: String,
        val conversationId: String,
        val title: String,
        val content: String,
        val attempts: Int,
    )

    val drafts: StateFlow<List<DraftRow>> =
        combine(repo.observeDrafts(), repo.observeConversations()) { drafts, conversations ->
            val titles = conversations.associate { it.id to it.title }
            drafts.map { (conversationId, text) ->
                DraftRow(conversationId, titles[conversationId] ?: "Chat", text)
            }.sortedBy { it.title.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outbox: StateFlow<List<OutboxRow>> =
        combine(repo.observeOutbox(), repo.observeConversations()) { rows, conversations ->
            val titles = conversations.associate { it.id to it.title }
            rows.map { entry ->
                OutboxRow(
                    clientId = entry.clientId,
                    conversationId = entry.conversationId,
                    title = titles[entry.conversationId] ?: "Chat",
                    content = entry.content,
                    attempts = entry.attempts,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteDraft(conversationId: String) {
        viewModelScope.launch { repo.clearDraft(conversationId) }
    }

    fun clearDrafts() {
        viewModelScope.launch { repo.clearAllDrafts() }
    }

    fun deleteOutboxEntry(clientId: String) {
        viewModelScope.launch { repo.discardOutboxEntry(clientId) }
    }

    fun clearOutbox() {
        viewModelScope.launch { repo.clearOutbox() }
    }

    // ── Data & Storage — footprint + cleanup ─────────────────────

    data class Footprint(val databaseBytes: Long = 0, val imageCacheBytes: Long = 0) {
        val totalBytes: Long get() = databaseBytes + imageCacheBytes
    }

    private val _footprint = MutableStateFlow(Footprint())

    /** Measured footprint — DB file(s) via getDatabasePath + the Coil disk cache. */
    val footprint: StateFlow<Footprint> = _footprint.asStateFlow()

    fun refreshFootprint() {
        viewModelScope.launch(Dispatchers.IO) {
            val dbDir = appContext.getDatabasePath("pulse.db").parentFile
            val dbBytes = dbDir?.listFiles()
                ?.filter { it.name.startsWith("pulse.db") }
                ?.sumOf { it.length() } ?: 0L
            val cacheBytes = runCatching {
                appContext.cacheDir.resolve("image_cache").walkBytes()
            }.getOrDefault(0L)
            _footprint.value = Footprint(dbBytes, cacheBytes)
        }
    }

    fun clearImageCache(onCleared: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cacheDir = appContext.cacheDir.resolve("image_cache")
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            refreshFootprint()
            withContext(Dispatchers.Main) { onCleared() }
        }
    }

    private fun java.io.File.walkBytes(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ── Real-time & Voice — live probe (timed real GET) ──────────

    data class ProbeState(
        val running: Boolean = false,
        val ok: Boolean? = null,
        val detail: String = "",
        val ms: Long? = null,
    )

    private val _probe = MutableStateFlow(ProbeState())

    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    /** The configured gateway origin — null/blank = offline-first (honest copy). */
    val gatewayHost: String get() = PulseEndpoints.gatewayHttpUrl

    /** Time a REAL GET /api/users — an honest latency verdict, not a fake ping. */
    fun probeGateway() {
        val origin = PulseEndpoints.gatewayHttpUrl
        if (origin.isBlank()) {
            _probe.value = ProbeState(ok = false, detail = "No gateway configured — Pulse runs offline-first.")
            return
        }
        _probe.value = ProbeState(running = true)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$origin/api/users").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("User-Agent", "Pulse-Probe/1 (Android)")
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) Result.success(code) else Result.failure(IOException("HTTP $code"))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val ms = System.currentTimeMillis() - started
            result.fold(
                onSuccess = { _probe.value = ProbeState(ok = true, detail = "GET /api/users answered", ms = ms) },
                onFailure = { _probe.value = ProbeState(ok = false, detail = it.message ?: "unreachable", ms = ms) },
            )
        }
    }
}

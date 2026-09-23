package app.pulse.feature.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────
// R1-W2I — PiP pane store (F-PI-01..03).
// Mirror of the web src/components/chat/pip-store.ts (zustand + persist):
//
// A floating pane = one conversation rendered as a draggable mini window.
// Model (web R28-a pane-management rework):
//   · at most ONE expanded (focused) window at a time
//   · every other live pane is collapsed into the compact pill stack
//     on the right edge ("minimized")
//   · PIP_MAX_PANES live panes; opening a new one demotes the focused
//     window to the stack and evicts the OLDEST stacked pane beyond
//     the cap
//   · per-pane normalized position (0..1 of the draggable range)
//     survives relaunches (web localStorage `pulse.pip.v2` → native
//     SharedPreferences key-value, same JSON shape)
//
// Web legacy contract kept for the room header toggle (chat-room.tsx):
//   open(id)    → expand (or create) the pane for `id`, demote the rest
//   close()     → close the focused window; stacked panes keep living
//   isOpen      → true while ≥ 1 pane exists
//   conversationId → the focused (expanded) pane's conversation,
//                    null when only the stack is showing
// ─────────────────────────────────────────────────────────────

/** Max simultaneously live panes (1 expanded + rest stacked) — web pip-store.ts PIP_MAX_PANES. */
const val PIP_MAX_PANES = 3

/**
 * One live pane. Web [PipPane] parity: pane key == conversation id (one pane
 * per conversation); [nx]/[ny] are the normalized top-left position across
 * the draggable range; [lastSeenAt] drives pane-local unread badges;
 * [openedAt] (creation/last-focus) is the eviction order.
 * Web's `meta` display blob is not stored here — the native cards read the
 * SHARED conversation cache (`PulseRepository.observeConversations`) instead,
 * which is the exact mirror of web's "same shared TanStack caches" rule.
 */
@Serializable
data class PipPane(
    val conversationId: String,
    val minimized: Boolean = false,
    val nx: Float = 1f,
    val ny: Float = 0.08f,
    val lastSeenAt: Long = 0L,
    val openedAt: Long = 0L,
)

/** Web PipChatState parity — persisted fields mirror the web partialize blob. */
@Serializable
data class PiPState(
    val panes: List<PipPane> = emptyList(),
    val isOpen: Boolean = false,
    val conversationId: String? = null,
    val minimized: Boolean = false,
)

/**
 * Process-singleton pane holder (Hilt @Singleton — the Wave 3 call engine /
 * Wave 5 voice rooms engine pattern): every surface overlays the same state.
 * Persists through SharedPreferences (the spec's "native key-value" for
 * `pulse.pip.v2` geometry persistence, AT-PI-03) on every mutation.
 */
@Singleton
class PulsePiPStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PiPState> = _state.asStateFlow()

    // ── legacy contract ops (web usePipChat) ─────────────────────

    /** open (or focus) the pane for a conversation — demotes the rest. */
    fun open(conversationId: String) = update { s ->
        val now = System.currentTimeMillis()
        val demotedAll = s.panes.map { p ->
            if (p.conversationId == conversationId) {
                p.copy(minimized = false, openedAt = now)
            } else {
                p.copy(minimized = true)
            }
        }
        var panes = demotedAll
        if (panes.none { it.conversationId == conversationId }) {
            // new pane: staggered default down the right edge (web parity)
            panes = panes + PipPane(
                conversationId = conversationId,
                minimized = false,
                nx = 1f,
                ny = clamp01(0.06f + 0.07f * demotedAll.size),
                lastSeenAt = now,
                openedAt = now,
            )
        }
        // cap live panes — evict the OLDEST pane that is not the focused one
        while (panes.size > PIP_MAX_PANES) {
            val oldest = panes
                .filter { it.conversationId != conversationId }
                .minByOrNull { it.openedAt } ?: break
            panes = panes.filter { it.conversationId != oldest.conversationId }
        }
        PiPState(panes = panes, isOpen = true, conversationId = conversationId, minimized = false)
    }

    /** close the focused window (stacked panes stay). */
    fun close() = update { s ->
        val id = s.conversationId ?: return@update s
        val panes = s.panes.filter { it.conversationId != id }
        PiPState(panes = panes, isOpen = panes.isNotEmpty(), conversationId = null, minimized = panes.isNotEmpty())
    }

    /** collapse the focused window into the stack. */
    fun minimize() = update { s ->
        val id = s.conversationId ?: return@update s
        PiPState(
            panes = s.panes.map { p -> if (p.conversationId == id) p.copy(minimized = true) else p },
            conversationId = null,
            minimized = s.panes.isNotEmpty(),
            isOpen = s.panes.isNotEmpty(),
        )
    }

    /** expand the most recently demoted stacked pane. */
    fun restore() = update { s ->
        if (s.conversationId != null) return@update s
        val next = s.panes.filter { it.minimized }.maxByOrNull { it.openedAt } ?: return@update s
        val now = System.currentTimeMillis()
        PiPState(
            panes = s.panes.map { p ->
                when (p.conversationId) {
                    next.conversationId -> p.copy(minimized = false, openedAt = now)
                    else -> p.copy(minimized = true)
                }
            },
            conversationId = next.conversationId,
            minimized = false,
            isOpen = true,
        )
    }

    // ── R28-a pane ops (web parity) ──────────────────────────────

    /** expand a specific stacked pane, demote the rest. */
    fun focusPane(conversationId: String) = update { s ->
        if (s.panes.none { it.conversationId == conversationId } || s.conversationId == conversationId) {
            return@update s
        }
        val now = System.currentTimeMillis()
        PiPState(
            panes = s.panes.map { p ->
                when (p.conversationId) {
                    conversationId -> p.copy(minimized = false, openedAt = now)
                    else -> p.copy(minimized = true)
                }
            },
            conversationId = conversationId,
            minimized = false,
            isOpen = true,
        )
    }

    /** close a specific pane (focused or stacked). */
    fun closePane(conversationId: String) = update { s ->
        val panes = s.panes.filter { it.conversationId != conversationId }
        val wasFocused = s.conversationId == conversationId
        PiPState(
            panes = panes,
            isOpen = panes.isNotEmpty(),
            conversationId = if (wasFocused) null else s.conversationId,
            minimized = panes.isNotEmpty() && if (wasFocused) true else s.minimized,
        )
    }

    /** persist a pane's normalized position (0..1) — web setPanePosition. */
    fun setPanePosition(conversationId: String, nx: Float, ny: Float) = update { s ->
        val pane = s.panes.firstOrNull { it.conversationId == conversationId } ?: return@update s
        val sx = clamp01(nx)
        val sy = clamp01(ny)
        if (kotlin.math.abs(pane.nx - sx) < 0.0005f && kotlin.math.abs(pane.ny - sy) < 0.0005f) {
            return@update s
        }
        s.copy(panes = s.panes.map { p -> if (p.conversationId == conversationId) p.copy(nx = sx, ny = sy) else p })
    }

    /** mark the focused reading surface seen (≥1s guard, web parity). */
    fun markSeen(conversationId: String, at: Long) = update { s ->
        val pane = s.panes.firstOrNull { it.conversationId == conversationId } ?: return@update s
        if (at - pane.lastSeenAt < 1000) return@update s
        s.copy(panes = s.panes.map { p -> if (p.conversationId == conversationId) p.copy(lastSeenAt = at) else p })
    }

    // ── internals ────────────────────────────────────────────────

    private fun update(transform: (PiPState) -> PiPState) {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            persist(next)
        }
    }

    /** Relaunch restore — web persist.merge() parity: sanitize panes, the
     *  focused pane must exist AND be expanded, otherwise stack-only. */
    private fun load(): PiPState {
        val raw = prefs.getString(KEY, null) ?: return PiPState()
        val saved = runCatching { json.decodeFromString(PiPState.serializer(), raw) }.getOrNull() ?: return PiPState()
        val panes = sanitizePanes(saved.panes)
        val focused = saved.conversationId?.let { id -> panes.firstOrNull { it.conversationId == id && !it.minimized } }
        return PiPState(
            panes = panes,
            isOpen = panes.isNotEmpty() && saved.isOpen,
            conversationId = focused?.conversationId,
            minimized = panes.isNotEmpty() && focused == null,
        )
    }

    private fun persist(state: PiPState) {
        val encoded = runCatching { json.encodeToString(PiPState.serializer(), state) }.getOrNull() ?: return
        prefs.edit().putString(KEY, encoded).apply()
    }

    /** Web sanitizePanes: dedupe by id, clamp positions, keep the newest
     *  PIP_MAX_PANES ordered oldest → newest. */
    private fun sanitizePanes(raw: List<PipPane>): List<PipPane> {
        val seen = HashSet<String>()
        val out = ArrayList<PipPane>()
        for (p in raw) {
            if (p.conversationId.isEmpty() || !seen.add(p.conversationId)) continue
            out += p.copy(nx = clamp01(p.nx), ny = clamp01(p.ny))
        }
        return out.sortedBy { it.openedAt }.takeLast(PIP_MAX_PANES)
    }

    private fun clamp01(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f

    private companion object {
        const val PREFS_FILE = "pulse.pip"
        const val KEY = "pulse.pip.v2"
    }
}

package app.pulse.feature.voice.engine

/**
 * Pure jitter-buffer playhead for per-peer PCM chunks (spec §1.1 VR-5).
 *
 * Per-peer state {lastSeq, nextAtMs}; a chunk at [seq] is:
 *  • DROPPED when `seq <= lastSeq` (duplicate or stale — the reconnect
 *    blackhole guard), otherwise
 *  • scheduled at `max(now + PRE_ROLL_MS, nextAt)` — an 85ms pre-roll absorbs
 *    jitter, and chaining `nextAt = at + duration` keeps back-to-back blocks
 *    gapless.
 *
 * WEB DEFECT FIX #2: when a peer disappears from voice:roster, the engine
 * calls [resetPeer] (lastSeq→0, queued state gone) so the peer's REJOIN plays
 * from seq 1 immediately instead of being deduped into silence.
 */
class PlaybackScheduler(private val preRollMs: Long = PRE_ROLL_MS) {

    sealed interface Decision {
        data class Schedule(val atMs: Long) : Decision
        data object Drop : Decision
    }

    private class PeerState {
        var lastSeq: Long = 0L
        var nextAtMs: Long = 0L
    }

    private val peers = HashMap<String, PeerState>()

    /** Decides AND advances — a Schedule verdict has already moved the playhead. */
    fun decide(userId: String, seq: Long, durationMs: Long, nowMs: Long): Decision {
        val peer = peers.getOrPut(userId) { PeerState() }
        if (seq <= peer.lastSeq) return Decision.Drop
        val at = maxOf(nowMs + preRollMs, peer.nextAtMs)
        peer.lastSeq = seq
        peer.nextAtMs = at + durationMs
        return Decision.Schedule(at)
    }

    /** Roster-drop / teardown reset for ONE peer (WEB DEFECT FIX #2 seam). */
    fun resetPeer(userId: String) {
        peers.remove(userId)
    }

    /** Full teardown. */
    fun reset() {
        peers.clear()
    }

    companion object {
        /** The 85ms jitter pre-roll (web parity; spec VR-5). */
        const val PRE_ROLL_MS = 85L
    }
}

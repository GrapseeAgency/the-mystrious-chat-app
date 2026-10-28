package app.pulse.data.repository

import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import app.pulse.domain.sync.MessageDispatchGate
import java.util.concurrent.ConcurrentHashMap

/** Draft/edit and schedule replay share the account's persisted global admission.
 * Room slow-mode holds apply to sends, not to saving drafts or creating schedules.
 * State contains no database reference: the coordinator's weak database keys stay weak.
 */
internal class ComposerRetryAdmission(private val db: PulseDatabase) {
    class State {
        val gate = MessageDispatchGate()
        val loaded = ConcurrentHashMap<String, Long>()
        val unsaved = ConcurrentHashMap<String, Wave7CacheEntity>()
    }
    private fun state(owner: String) = DraftWriteCoordinator.forDatabase(db).replayRetryStates
        .getOrPut(owner) { State() }
    private fun journal(owner: String): OutboxRetryJournal {
        val state = state(owner)
        return OutboxRetryJournal(db, state.gate, state.loaded, state.unsaved)
    }
    suspend fun canRun(owner: String): Boolean {
        journal(owner).restore(owner)
        return !isHeld(owner)
    }
    fun isHeld(owner: String): Boolean = state(owner).gate.remainingDelayMillis() > 0
    suspend fun hold(owner: String, seconds: Int?) = journal(owner).hold(owner, seconds, null)
}

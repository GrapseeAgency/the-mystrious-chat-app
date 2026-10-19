package app.pulse.domain.notify

import app.pulse.protocol.ReminderItemDto

/** Network identity captured at repository creation, never read from a later session. */
data class ReminderOwner(val userId: String, val origin: String)

interface ReminderEffects {
    suspend fun schedule(owner: ReminderOwner, reminder: ReminderItemDto)
    suspend fun finish(owner: ReminderOwner, id: String)

    object None : ReminderEffects {
        override suspend fun schedule(owner: ReminderOwner, reminder: ReminderItemDto) = Unit
        override suspend fun finish(owner: ReminderOwner, id: String) = Unit
    }
}

/** One process-wide gate shared by the worker, foreground poll, and cancellation.
 * Store writes must be durable before returning. OS acceptance is not proof of
 * human delivery. No network or suspend callbacks run while holding this lock. */
class ReminderDeliveryGate(private val store: Store) {
    enum class State { ARMED, ACCEPTED, FINISHED }
    enum class Verdict { ACCEPTED, BLOCKED, RETRY }
    interface Store {
        fun read(key: String): State?
        fun write(key: String, state: State)
    }
    // If durable receipt storage fails after notify(), retry that write without
    // ringing again in this process. A process kill in this OS/DB gap is not atomic.
    private val acceptedInMemory = mutableSetOf<String>()

    @Synchronized fun schedule(key: String, enqueue: () -> Unit): Boolean {
        val state = store.read(key)
        if (state == State.ACCEPTED || state == State.FINISHED || key in acceptedInMemory) return false
        if (state != State.ARMED) store.write(key, State.ARMED)
        enqueue() // KEEP, not REPLACE: a poll must not cancel a running worker.
        return true
    }

    @Synchronized fun deliver(key: String, currentOwner: () -> Boolean, post: () -> Boolean): Verdict {
        if (!currentOwner()) return Verdict.BLOCKED
        when (store.read(key)) {
            State.FINISHED -> return Verdict.BLOCKED
            State.ACCEPTED -> return Verdict.ACCEPTED
            else -> Unit
        }
        if (key !in acceptedInMemory) {
            if (!post()) return Verdict.RETRY
            acceptedInMemory.add(key)
        }
        store.write(key, State.ACCEPTED)
        acceptedInMemory.remove(key)
        return Verdict.ACCEPTED
    }

    @Synchronized fun settleRemote(key: String, cancelPending: () -> Unit) {
        if (store.read(key) == State.FINISHED) return
        store.write(key, State.FINISHED)
        acceptedInMemory.remove(key)
        cancelPending()
    }

    @Synchronized fun finish(key: String, cancel: () -> Unit) {
        // Tombstone before OS cancellation: a queued/stale worker cannot repost.
        store.write(key, State.FINISHED)
        acceptedInMemory.remove(key)
        cancel()
    }
}

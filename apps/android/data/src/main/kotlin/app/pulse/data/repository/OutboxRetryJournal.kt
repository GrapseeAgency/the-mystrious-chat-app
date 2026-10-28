package app.pulse.data.repository

import androidx.room.withTransaction
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import app.pulse.domain.sync.MessageDispatchGate
import java.util.concurrent.ConcurrentHashMap

/** Persist absolute retry admission deadlines, not process-local wake tasks.
 * Restore once per persisted deadline into the monotonic gate. Repeated scans
 * must not restart a cooldown. Account databases and owner prefixes isolate it.
 */
internal class OutboxRetryJournal(
    private val db: PulseDatabase, private val gate: MessageDispatchGate,
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    private val loaded = ConcurrentHashMap<String, Long>()
    private fun prefix(owner: String) = "outbox-retry:v1|$owner|"
    suspend fun hold(owner: String, seconds: Int?, room: String?) {
        gate.holdRetries(seconds, room) // Retain the in-process hold even if disk fails.
        val scope = room ?: ""
        val key = prefix(owner) + if (room == null) "global" else "room|$room"
        val until = wallNow() + (seconds ?: 60).coerceIn(1, 86_400).toLong() * 1_000
        val row = db.withTransaction {
            val previous = db.wave7Dao().get(key)
            Wave7CacheEntity(key, scope, maxOf(until, previous?.updatedAt ?: 0)).also { db.wave7Dao().upsert(it) }
        }
        restore(row)
    }
    private fun restore(row: Wave7CacheEntity) {
        if (loaded.put(row.key, row.updatedAt) == row.updatedAt) return
        val remaining = (row.updatedAt - wallNow()).coerceIn(0, 86_400_000)
        if (remaining > 0) gate.holdRetries(((remaining + 999) / 1_000).toInt(), row.json.ifEmpty { null })
    }
    suspend fun restore(owner: String) {
        val now = wallNow()
        loaded.entries.removeIf { it.value <= now }
        // These are only retry-admission rows, never user messages/receipts.
        for (row in db.wave7Dao().readJournal(prefix(owner))) {
            if (row.updatedAt > now) restore(row)
        }
        db.wave7Dao().pruneExpiredRetryHolds(prefix(owner), now)
    }
}

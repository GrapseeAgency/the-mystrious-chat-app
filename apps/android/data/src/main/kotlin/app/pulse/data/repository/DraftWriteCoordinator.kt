package app.pulse.data.repository

import app.pulse.data.local.PulseDatabase
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** AccountRepositories reuses a Room handle on return to an account. Its old,
 * finite journal tasks must share ordering with the replacement repository. */
internal class DraftWriteCoordinator internal constructor() {
    val versions = ConcurrentHashMap<String, AtomicLong>()
    val threadBuffers = ConcurrentHashMap<String, Pair<Long, String>>()
    val threadFailures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val localMutex = Mutex()
    val serverMutex = Mutex()
    val bufferChanges = MutableStateFlow(0L)

    fun bufferSnapshot(key: String, revision: Long, text: String): Pair<Long, String> {
        val snapshot = revision to localSnapshot(text)
        threadBuffers.compute(key) { _, previous ->
            if (versions[key]?.get() != revision || (previous != null && previous.first > revision)) previous else snapshot
        }
        bufferChanges.update { it + 1 }
        return snapshot
    }

    fun finishSnapshot(key: String, revision: Long) {
        val snapshot = threadBuffers[key] ?: return
        if (snapshot.first == revision && threadBuffers.remove(key, snapshot)) bufferChanges.update { it + 1 }
    }

    /** Caller holds localMutex while reading SQLite and this buffer together. */
    fun currentSnapshot(key: String, persisted: String?): String? =
        threadBuffers[key]?.takeIf { it.first == versions[key]?.get() }?.second ?: persisted

    suspend fun readSnapshot(key: String, readPersisted: suspend () -> String?): String? = localMutex.withLock {
        // A failed disk read must not hide an already staged recovery snapshot.
        // Recheck after an awaited read too: keystrokes reserve revisions synchronously.
        currentSnapshot(key, null) ?: currentSnapshot(key, readPersisted())
    }

    companion object {
        /** A draft is recovery data, not a sendable message. Never clip it to the wire limit. */
        internal fun localSnapshot(text: String): String = if (text.isBlank()) "" else text

        private val databases = WeakHashMap<PulseDatabase, DraftWriteCoordinator>()
        fun forDatabase(database: PulseDatabase): DraftWriteCoordinator = synchronized(databases) {
            databases.getOrPut(database) { DraftWriteCoordinator() }
        }
    }
}

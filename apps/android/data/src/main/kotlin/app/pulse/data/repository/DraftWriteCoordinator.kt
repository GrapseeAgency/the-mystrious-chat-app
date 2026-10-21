package app.pulse.data.repository

import app.pulse.data.local.PulseDatabase
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** AccountRepositories reuses a Room handle on return to an account. Its old,
 * finite journal tasks must share ordering with the replacement repository. */
internal class DraftWriteCoordinator private constructor() {
    val versions = ConcurrentHashMap<String, AtomicLong>()
    val threadBuffers = ConcurrentHashMap<String, Pair<Long, String>>()
    val threadFailures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val localMutex = Mutex()
    val serverMutex = Mutex()

    companion object {
        private val databases = WeakHashMap<PulseDatabase, DraftWriteCoordinator>()
        fun forDatabase(database: PulseDatabase): DraftWriteCoordinator = synchronized(databases) {
            databases.getOrPut(database) { DraftWriteCoordinator() }
        }
    }
}

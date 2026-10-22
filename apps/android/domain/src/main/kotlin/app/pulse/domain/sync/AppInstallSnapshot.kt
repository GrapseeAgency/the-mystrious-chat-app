package app.pulse.domain.sync

import app.pulse.protocol.AppInstallStateDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Complete-or-error catalog scan. A failed app read is not a disconnection.
 * Children belong to the caller and no more than eight reads run at once. */
suspend fun appInstallSnapshot(
    appIds: List<String>,
    owner: String,
    read: suspend (String) -> Result<AppInstallStateDto>,
): Set<String> = coroutineScope {
    require(owner.isNotBlank() && appIds.distinct().size == appIds.size)
    val connected = mutableSetOf<String>()
    for (batch in appIds.chunked(8)) {
        val rows = batch.map { id -> async {
            val row = read(id).getOrThrow()
            require(row.appVersion == 1 && row.appId == id && row.userId == owner) { "App scan ownership changed." }
            id to row.installed
        } }.awaitAll()
        for ((id, installed) in rows) if (installed) connected.add(id)
    }
    connected.toSet()
}

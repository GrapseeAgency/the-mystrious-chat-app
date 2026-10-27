package app.pulse.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** Never replace stored preferences to recover a read. Keep appearance stable,
 * let security consumers choose a conservative fallback, and retry only while
 * a collector is alive. Mutation failures still propagate to their caller. */
internal fun <T> DataStore<Preferences>.observeResilient(
    initial: T,
    unavailable: (T) -> T = { it },
    read: (Preferences) -> T,
): Flow<T> = flow {
    var last = initial
    data.map { read(it) }.retryWhen { error, _ ->
        if (error is CancellationException || error !is Exception) throw error
        emit(unavailable(last))
        delay(5_000)
        true
    }.collect { value -> last = value; emit(value) }
}

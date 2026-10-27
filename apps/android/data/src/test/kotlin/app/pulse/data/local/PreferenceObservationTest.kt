package app.pulse.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreferenceObservationTest {
    private class BrokenThenRecovered(private val before: Preferences, private val after: Preferences) : DataStore<Preferences> {
        var subscriptions = 0
        override val data: Flow<Preferences> = flow {
            subscriptions++
            emit(if (subscriptions == 1) before else after)
            if (subscriptions == 1) throw IOException("Temporary read failure")
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("Reads must not rewrite storage")
    }
    @Test fun `read failure preserves appearance disables disclosure and recovers without resetting disk`() = runTest {
        val key = stringPreferencesKey("appearance")
        val source = BrokenThenRecovered(preferencesOf(key to "autumn"), preferencesOf(key to "recovered autumn"))
        val values = source.observeResilient(initial = "unknown" to false, unavailable = { it.first to false }) {
            it[key].orEmpty() to true
        }.take(3).toList()
        assertEquals(listOf("autumn" to true, "autumn" to false, "recovered autumn" to true), values)
        assertEquals(2, source.subscriptions)
    }
    @Test fun `personal security stays protected while the file is unreadable`() = runTest {
        val source = BrokenThenRecovered(preferencesOf(), preferencesOf())
        val values = RoomPrivacyLocalStore(source).observe("room").take(3).toList()
        assertNull(values[0].on)
        assertEquals(true, values[1].on); assertTrue(values[1].unreadable); assertTrue(values[1].pending)
        assertNull(values[2].on); assertFalse(values[2].unreadable)
    }
    @Test fun `retired collectors do not turn cancellation into retry or false settings`() = runTest {
        val source = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw CancellationException("retired") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("Unexpected write")
        }
        val observed = mutableListOf<String>()
        try { source.observeResilient(initial = "unknown") { "loaded" }.toList(observed); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertTrue(observed.isEmpty()) }
    }
    @Test fun `actual preference store masks disclosure without dropping appearance or declaring sync complete`() = runTest {
        val key = stringPreferencesKey("wave8.prefs.json")
        val saved = preferencesOf(key to """{"density":"compact","readReceipts":true,"typingVisible":true,"lastSeenVisible":true,"notifPreviews":true}""")
        val store = PulsePrefsLocalStore(BrokenThenRecovered(saved, saved))
        val values = store.prefs.take(3).toList()
        assertEquals(true, values[0].readReceipts)
        assertEquals(false, values[1].readReceipts); assertEquals(false, values[1].typingVisible)
        assertEquals(false, values[1].lastSeenVisible); assertEquals(false, values[1].notifPreviews)
        assertEquals("compact", values[1].density)
        assertEquals(true, values[2].readReceipts)
        val pending = PulsePrefsLocalStore(BrokenThenRecovered(preferencesOf(), preferencesOf())).hasPendingSettings.take(3).toList()
        assertEquals(listOf(false, true, false), pending)
    }
}

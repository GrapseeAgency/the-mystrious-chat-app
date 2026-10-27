package app.pulse.domain.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadStateInvalidationsTest {
    @Test fun `reconnect invalidates active and not yet mounted scopes without any queued read`() = runTest {
        val changes = ReadStateInvalidations()
        val first = mutableListOf<Long>()
        val other = mutableListOf<Long>()
        backgroundScope.launch { changes.observe("room").collect { first += it } }
        backgroundScope.launch { changes.observe("other").collect { other += it } }
        runCurrent()
        changes.settled("room")
        runCurrent()
        changes.reconnected()
        runCurrent()
        assertEquals(listOf(0L, 1L, 2L), first)
        assertEquals(listOf(0L, 1L), other)
        assertEquals(1L, changes.observe("later").first())
        changes.reconnected()
        runCurrent()
        assertEquals(3L, changes.observe("room").first())
        assertEquals(2L, changes.observe("later").first())
        changes.clear()
        runCurrent()
        assertEquals(0L, changes.observe("room").first())
        assertEquals(0L, changes.observe("later").first())
    }

    @Test fun `HTTP settlement reaches active and later scopes without invalidating unrelated rooms`() = runTest {
        val changes = ReadStateInvalidations()
        val first = mutableListOf<Long>()
        val other = mutableListOf<Long>()
        backgroundScope.launch { changes.observe("room").collect { first += it } }
        backgroundScope.launch { changes.observe("other").collect { other += it } }
        runCurrent()
        changes.settled("room")
        runCurrent()
        changes.settled("room")
        runCurrent()
        assertEquals(listOf(0L, 1L, 2L), first)
        assertEquals(listOf(0L), other)
        assertEquals(2L, changes.observe("room").first())
        changes.clear()
        runCurrent()
        assertEquals(0L, first.last())
        assertEquals(0L, changes.observe("room").first())
    }
}

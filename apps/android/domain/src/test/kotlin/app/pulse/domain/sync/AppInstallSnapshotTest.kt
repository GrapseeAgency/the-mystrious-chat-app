package app.pulse.domain.sync

import app.pulse.protocol.AppInstallStateDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppInstallSnapshotTest {
    private fun state(id: String, owner: String = "me", installed: Boolean = true) = AppInstallStateDto(
        appVersion = 1, appId = id, userId = owner, installed = installed,
        status = if (installed) "connected" else null, installedAt = "2026-10-22T00:00:00Z",
        installs = if (installed) 1 else 0, installers = emptyList(),
    )
    @Test fun `all hundred catalog apps are checked with bounded children`() = runTest {
        var active = 0; var peak = 0
        val seen = mutableSetOf<String>()
        val ids = (1..100).map(Int::toString)
        val connected = appInstallSnapshot(ids, "me") { id ->
            active++; peak = maxOf(peak, active)
            try { delay(10); seen.add(id); Result.success(state(id, installed = id == "100")) }
            finally { active-- }
        }
        assertEquals(ids.toSet(), seen); assertEquals(setOf("100"), connected)
        assertTrue(peak in 1..8); assertEquals(0, active)
    }
    @Test fun `failed read never produces a complete empty catalog`() = runTest {
        var returned = false
        try {
            appInstallSnapshot(listOf("1", "2"), "me") { id ->
                if (id == "2") Result.failure(IllegalStateException("offline")) else Result.success(state(id))
            }
            returned = true
        } catch (expected: IllegalStateException) { assertEquals("offline", expected.message) }
        assertFalse(returned)
    }
    @Test fun `foreign app or owner is rejected instead of entering the connected set`() = runTest {
        for (row in listOf(state("other"), state("1", owner = "other"))) {
            var rejected = false
            try { appInstallSnapshot(listOf("1"), "me") { Result.success(row) } }
            catch (expected: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
    @Test fun `leaving the page cancels every active child and never starts the next batch`() = runTest {
        var active = 0; var started = 0
        val scan = async {
            appInstallSnapshot((1..100).map(Int::toString), "me") {
                active++; started++
                try { awaitCancellation() } finally { active-- }
            }
        }
        runCurrent(); assertEquals(8, active)
        scan.cancel(); scan.join()
        assertEquals(0, active); assertEquals(8, started)
    }
}

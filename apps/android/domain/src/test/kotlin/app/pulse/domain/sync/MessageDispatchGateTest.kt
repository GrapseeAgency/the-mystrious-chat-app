package app.pulse.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDispatchGateTest {
    @Test fun `new send stays queued while recovery owns the writer`() = runTest {
        val gate = MessageDispatchGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = launch { gate.serialized { entered.complete(Unit); release.await() } }
        entered.await()
        var sends = 0
        assertNull(gate.tryImmediate({ true }) { sends++; "sent" })
        assertEquals(0, sends)
        release.complete(Unit); worker.join()
        assertEquals("sent", gate.tryImmediate({ true }) { sends++; "sent" })
        assertEquals(1, sends)
    }

    @Test fun `newer operation cannot overtake an older queue head`() = runTest {
        val gate = MessageDispatchGate()
        var sends = 0
        assertNull(gate.tryImmediate({ false }) { sends++; true })
        assertEquals(0, sends)
        assertEquals(true, gate.tryImmediate({ true }) { sends++; true })
    }

    @Test fun `cancellation releases the writer for durable recovery`() = runTest {
        val gate = MessageDispatchGate()
        val blocker = CompletableDeferred<Unit>()
        val worker = launch { gate.tryImmediate({ true }) { blocker.await(); true } }
        runCurrent(); worker.cancelAndJoin()
        assertEquals(true, gate.tryImmediate({ true }) { true })
    }

    @Test fun `rate limit survives new-send triggers and cannot be shortened`() = runTest {
        var time = 1000L
        val gate = MessageDispatchGate { time }
        gate.holdRetries(60)
        time += 10_000L
        gate.holdRetries(1)
        assertEquals(50_000L, gate.remainingDelayMillis())
        assertNull(gate.tryImmediate({ true }) { "too early" })
        time += 50_000L
        assertEquals("allowed", gate.tryImmediate({ true }) { "allowed" })
    }
    @Test fun `room cooldown cannot freeze another room and global holds still dominate`() = runTest {
        var now = 1000L
        val gate = MessageDispatchGate { now }
        gate.holdRetries(60, "slow")
        gate.holdRetries(1, "slow")
        assertEquals(setOf("slow"), gate.blockedConversations())
        assertNull(gate.tryImmediate({ true }, "slow") { "not yet" })
        assertEquals("sent", gate.tryImmediate({ true }, "direct") { "sent" })
        gate.holdRetries(90)
        assertNull(gate.tryImmediate({ true }, "direct") { "globally held" })
        now += 60_000
        assertTrue(gate.blockedConversations().isEmpty())
        assertNull(gate.tryImmediate({ true }, "slow") { "still globally held" })
        now += 30_000
        assertEquals("sent", gate.tryImmediate({ true }, "slow") { "sent" })
    }

}

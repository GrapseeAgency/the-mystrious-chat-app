package app.pulse.domain.notify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReminderDeliveryGateTest {
    private class Store : ReminderDeliveryGate.Store {
        val rows = mutableMapOf<String, ReminderDeliveryGate.State>()
        var fail = false
        override fun read(key: String) = rows[key]
        override fun write(key: String, state: ReminderDeliveryGate.State) {
            check(!fail) { "disk unavailable" }
            rows[key] = state
        }
    }
    @Test fun `denied notification never becomes accepted and can retry after permission grant`() {
        val store = Store(); val gate = ReminderDeliveryGate(store)
        assertEquals(ReminderDeliveryGate.Verdict.RETRY, gate.deliver("a:r", { true }, { false }))
        assertNull(store.read("a:r"))
        assertEquals(ReminderDeliveryGate.Verdict.ACCEPTED, gate.deliver("a:r", { true }, { true }))
    }
    @Test fun `failed server acknowledgement does not replay notification on next poll or restart`() {
        val store = Store(); var posts = 0
        assertEquals(ReminderDeliveryGate.Verdict.ACCEPTED, ReminderDeliveryGate(store).deliver("a:r", { true }, { posts++; true }))
        assertEquals(ReminderDeliveryGate.Verdict.ACCEPTED, ReminderDeliveryGate(store).deliver("a:r", { true }, { posts++; true }))
        assertEquals(1, posts)
    }
    @Test fun `cancel tombstone blocks stale work and stale snapshot rearming`() {
        val store = Store(); val gate = ReminderDeliveryGate(store)
        var cancels = 0
        gate.schedule("a:r") {}
        gate.finish("a:r") { cancels++ }
        assertFalse(gate.schedule("a:r") { fail<Unit>("must not enqueue") })
        assertEquals(ReminderDeliveryGate.Verdict.BLOCKED, gate.deliver("a:r", { true }, { fail<Boolean>("must not notify") }))
        assertEquals(1, cancels)
    }
    @Test fun `inactive owner cannot deliver even with an accepted receipt`() {
        val gate = ReminderDeliveryGate(Store())
        gate.deliver("a:r", { true }, { true })
        assertEquals(ReminderDeliveryGate.Verdict.BLOCKED, gate.deliver("a:r", { false }, { true }))
    }
    @Test fun `receipt storage failure retries persistence without replaying sound`() {
        val store = Store(); val gate = ReminderDeliveryGate(store); var posts = 0
        store.fail = true
        assertThrows(IllegalStateException::class.java) { gate.deliver("a:r", { true }, { posts++; true }) }
        store.fail = false
        assertEquals(ReminderDeliveryGate.Verdict.ACCEPTED, gate.deliver("a:r", { true }, { posts++; true }))
        assertEquals(1, posts)
    }
    @Test fun `foreground and worker racing commit one accepted notification`() {
        val gate = ReminderDeliveryGate(Store()); val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1); var posts = 0
        try {
            val tasks = (1..2).map { pool.submit<ReminderDeliveryGate.Verdict> {
                start.await(2, TimeUnit.SECONDS)
                gate.deliver("a:r", { true }, { posts++; true })
            } }
            start.countDown()
            tasks.forEach { assertEquals(ReminderDeliveryGate.Verdict.ACCEPTED, it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, posts)
        } finally { pool.shutdownNow() }
    }
    @Test fun `cancellation racing a running post removes it and prevents successor posts`() {
        val gate = ReminderDeliveryGate(Store()); val pool = Executors.newFixedThreadPool(2)
        val posting = CountDownLatch(1); val release = CountDownLatch(1)
        var visible = false
        try {
            val delivery = pool.submit { gate.deliver("a:r", { true }, {
                posting.countDown(); check(release.await(3, TimeUnit.SECONDS)); visible = true; true
            }) }
            assertTrue(posting.await(2, TimeUnit.SECONDS))
            val cancel = pool.submit { gate.finish("a:r") { visible = false } }
            release.countDown(); delivery.get(3, TimeUnit.SECONDS); cancel.get(3, TimeUnit.SECONDS)
            assertFalse(visible)
            assertEquals(ReminderDeliveryGate.Verdict.BLOCKED, gate.deliver("a:r", { true }, { true }))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `distinct owner namespaces and hash colliding IDs stay independent`() {
        val gate = ReminderDeliveryGate(Store()); var posts = 0
        assertEquals("Aa".hashCode(), "BB".hashCode())
        for (key in listOf("a:Aa", "a:BB", "b:Aa")) gate.deliver(key, { true }, { posts++; true })
        assertEquals(3, posts)
    }
}

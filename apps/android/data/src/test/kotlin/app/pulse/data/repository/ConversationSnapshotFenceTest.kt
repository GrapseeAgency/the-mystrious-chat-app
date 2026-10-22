package app.pulse.data.repository

import org.junit.Assert.*
import org.junit.Test

class ConversationSnapshotFenceTest {
    @Test fun concurrentReadersDoNotCancelEachOther() {
        val fence = ConversationSnapshotFence()
        val one = fence.beginRead("room")
        val two = fence.beginRead("room")
        assertTrue(fence.isCurrent(one)); assertTrue(fence.isCurrent(two))
    }
    @Test fun bothWriteBoundariesRetirePreWriteAndDuringWriteSnapshots() {
        val fence = ConversationSnapshotFence()
        val before = fence.beginRead("room")
        fence.invalidate("room") // dispatch
        val during = fence.beginRead("room")
        assertFalse(fence.isCurrent(before)); assertTrue(fence.isCurrent(during))
        fence.invalidate("room") // completion, including lost acknowledgement
        assertFalse(fence.isCurrent(during))
        assertTrue(fence.isCurrent(fence.beginRead("room")))
    }
    @Test fun leavingOneRoomCannotInvalidateAnotherRoomsRead() {
        val fence = ConversationSnapshotFence()
        val removed = fence.beginRead("left")
        val other = fence.beginRead("other")
        fence.invalidate("left")
        assertFalse(fence.isCurrent(removed)); assertTrue(fence.isCurrent(other))
    }
    @Test fun retirementCannotReuseAnOldZeroRevision() {
        val fence = ConversationSnapshotFence()
        val old = fence.beginRead("room")
        fence.clear()
        val current = fence.beginRead("room")
        assertFalse(fence.isCurrent(old)); assertTrue(fence.isCurrent(current))
    }
}

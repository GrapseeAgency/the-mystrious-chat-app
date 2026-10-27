package app.pulse.feature.calls

import app.pulse.domain.model.GroupCallSignalData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupCallSignalOwnershipTest {
    private val signal = GroupCallSignalData(event = "gcall:answer", conversationId = "room", callId = "current", from = "peer", to = "me")

    @Test fun `a prior call or different room cannot terminate the admitted call`() {
        assertTrue(matchesGroupCall(signal.copy(event = "gcall:ended"), "room", "current"))
        assertFalse(matchesGroupCall(signal.copy(callId = "old"), "room", "current"))
        assertFalse(matchesGroupCall(signal.copy(conversationId = "other"), "room", "current"))
        assertFalse(matchesGroupCall(signal, null, "current"))
        assertFalse(matchesGroupCall(signal.copy(callId = ""), "room", ""))
    }

    @Test fun `targeted SDP and ICE require current member and recipient`() {
        val roster = listOf("me", "peer")
        assertTrue(matchesGroupPeerSignal(signal, "room", "current", "me", roster))
        assertFalse(matchesGroupPeerSignal(signal.copy(to = "other-viewer"), "room", "current", "me", roster))
        assertFalse(matchesGroupPeerSignal(signal.copy(from = "outsider"), "room", "current", "me", roster))
        assertFalse(matchesGroupPeerSignal(signal.copy(from = "me"), "room", "current", "me", roster))
        assertFalse(matchesGroupPeerSignal(signal.copy(callId = "old"), "room", "current", "me", roster))
        assertFalse(matchesGroupPeerSignal(signal, "room", "current", "me", listOf("me")))
        assertFalse(matchesGroupPeerSignal(signal.copy(to = ""), "room", "current", "", roster))
    }
    @Test fun `delayed rings retain their deadline and missing or expired identities are refused`() {
        val now = 2_000_000_000_000L
        val ring = signal.copy(event = "gcall:ring", expiresAt = java.time.Instant.ofEpochMilli(now + 5_000).toString())
        assertEquals(now + 5_000, groupRingDeadline(ring, now))
        assertNull(groupRingDeadline(ring, now + 5_000))
        for (invalid in listOf(ring.copy(callId = ""), ring.copy(conversationId = ""), ring.copy(from = ""),
            ring.copy(expiresAt = null), ring.copy(expiresAt = "bad"))) assertNull(groupRingDeadline(invalid, now))
        assertEquals(now + 45_000, groupRingDeadline(ring.copy(expiresAt = java.time.Instant.ofEpochMilli(now + 90_000).toString()), now))
    }
    @Test fun `a delayed admission refusal belongs only to its original attempt`() {
        val refusal = signal.copy(event = "gcall:ended", callId = "", joinAttemptId = "attempt-A")
        assertTrue(matchesPendingGroupAttempt(refusal, "room", "attempt-A"))
        assertFalse(matchesPendingGroupAttempt(refusal, "room", "attempt-B"))
        assertFalse(matchesPendingGroupAttempt(refusal, "other", "attempt-A"))
        assertFalse(matchesPendingGroupAttempt(refusal.copy(joinAttemptId = null), "room", "attempt-A"))
        assertFalse(matchesPendingGroupAttempt(refusal, null, "attempt-A"))
        assertFalse(matchesPendingGroupAttempt(refusal, "room", null))
    }
}

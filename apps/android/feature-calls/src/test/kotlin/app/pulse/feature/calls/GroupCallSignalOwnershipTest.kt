package app.pulse.feature.calls

import app.pulse.domain.model.GroupCallSignalData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}

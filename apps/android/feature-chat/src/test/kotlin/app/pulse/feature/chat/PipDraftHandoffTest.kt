package app.pulse.feature.chat

import app.pulse.domain.model.ComposerDraftToken
import org.junit.Assert.*
import org.junit.Test

class PipDraftHandoffTest {
    private val token = ComposerDraftToken("room", "owner", 4)
    @Test fun failedPersistenceNeverClearsMiniChat() {
        assertFalse(paneMayClearAfterSend(PipSendResult.Failed("disk full"), "keep", "keep", token, token))
    }
    @Test fun queuedOrDeliveredOnlyClearsTheExactOwnedRevision() {
        for (result in listOf(PipSendResult.Queued, PipSendResult.Delivered)) {
            assertTrue(paneMayClearAfterSend(result, "text", "text", token, token))
            assertFalse(paneMayClearAfterSend(result, "text", "new text", token, token.copy(revision=5)))
            assertFalse(paneMayClearAfterSend(result, "text", "text", token, token.copy(ownerId="other")))
            assertFalse(paneMayClearAfterSend(result, "text", "text", token, token.copy(revision=5)))
            assertFalse(paneMayClearAfterSend(result, "text", "text", null, null))
        }
    }
}

package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationReplyOwnerTest {
    @Test fun `legacy foreign and uninitialized recipients cannot authorize a reply`() {
        for (recipient in listOf(null, "", " ", "other")) {
            assertNull(NotificationReplyOwner.capture(recipient, "owner", "epoch"))
        }
        assertNull(NotificationReplyOwner.capture("owner", null, "epoch"))
        assertNull(NotificationReplyOwner.capture("owner", "owner", null))
        assertNull(NotificationReplyOwner.capture("owner", "owner", ""))
    }
    @Test fun `account rotation and same account reentry invalidate the captured action`() {
        val owner = checkNotNull(NotificationReplyOwner.capture("owner", "owner", "epoch"))
        assertTrue(owner.matches("owner", "epoch"))
        assertFalse(owner.matches("other", "epoch"))
        assertFalse(owner.matches("owner", "later-epoch"))
        assertFalse(owner.matches(null, null))
        assertFalse(NotificationReplyOwner("", "").matches("", ""))
    }
}

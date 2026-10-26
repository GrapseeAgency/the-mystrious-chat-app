package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageNotificationIdentityTest {
    @Test fun exactTagsRoundTripAndDoNotAliasHashCollisionsOrDifferentOwners() {
        for (identity in listOf(MessageNotificationIdentity("owner", "room", "message"),
            MessageNotificationIdentity("o/+", "a b", "বাংলা"))) {
            assertEquals(identity, MessageNotificationIdentity.parse(identity.tag))
        }
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(MessageNotificationIdentity("owner", "Aa", "m").tag,
            MessageNotificationIdentity("owner", "BB", "m").tag)
        assertNotEquals(MessageNotificationIdentity("owner", "r", "m").tag,
            MessageNotificationIdentity("other", "r", "m").tag)
        for (tag in listOf(null, "", "legacy", "pulse-msg-v1/o/r", "pulse-msg-v1/o/r/", "pulse-msg-v1/o/r/%ZZ",
            "pulse-msg-v1/o/r/" + "x".repeat(129))) assertNull(MessageNotificationIdentity.parse(tag))
    }
}

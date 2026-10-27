package app.pulse.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wave 6 F-DL - deep-link parser contract: the three families, both wire
 * shapes (authority vs bare ssp), alias hosts, percent decoding, and total
 * failure (null, never a throw).
 */
class PulseDeepLinkTest {

    @Test
    fun `parses the three families`() {
        assertEquals(PulseDeepLink.Invite("PULSE12"), PulseDeepLink.parse("pulse://invite/PULSE12"))
        assertEquals(PulseDeepLink.User("u1"), PulseDeepLink.parse("pulse://user/u1"))
        assertEquals(PulseDeepLink.Room("c1"), PulseDeepLink.parse("pulse://room/c1"))
    }

    @Test
    fun `parses the bare ssp shape without slashes`() {
        assertEquals(PulseDeepLink.Invite("abc"), PulseDeepLink.parse("pulse:invite/abc"))
        assertEquals(PulseDeepLink.User("u9"), PulseDeepLink.parse("pulse:user/u9"))
        assertEquals(PulseDeepLink.Room("c9"), PulseDeepLink.parse("pulse:chat/c9"))
    }

    @Test
    fun `accepts the web route aliases`() {
        assertEquals(PulseDeepLink.Invite("abc"), PulseDeepLink.parse("pulse://join/abc"))
        assertEquals(PulseDeepLink.Invite("abc"), PulseDeepLink.parse("pulse://group/abc"))
        assertEquals(PulseDeepLink.User("u"), PulseDeepLink.parse("pulse://u/u"))
        assertEquals(PulseDeepLink.Room("c"), PulseDeepLink.parse("pulse://conversation/c"))
    }

    @Test
    fun `decodes percent-encoded keys`() {
        assertEquals(PulseDeepLink.User("a b"), PulseDeepLink.parse("pulse://user/a%20b"))
        assertEquals(PulseDeepLink.Invite("ab+cd"), PulseDeepLink.parse("pulse:invite/ab%2Bcd"))
    }

    @Test
    fun `unknown kinds and malformed links are null - never a throw`() {
        assertNull(PulseDeepLink.parse("pulse://unknown/abc"))
        assertNull(PulseDeepLink.parse("pulse://invite"))
        assertNull(PulseDeepLink.parse("pulse://invite/"))
        assertNull(PulseDeepLink.parse("https://pulse.example/invite/abc"))
        assertNull(PulseDeepLink.parse(null))
        assertNull(PulseDeepLink.parse(""))
        assertNull(PulseDeepLink.parse("not a uri at all %%%"))
    }

    @Test
    fun `scheme is case-insensitive and keys keep their case`() {
        assertEquals(PulseDeepLink.Room("C1"), PulseDeepLink.parse("PULSE://room/C1"))
    }

    // R7 item 4 - reminder jump payload on room deep links

    @Test
    fun `roomUri builds the jump payload only when a message id is present`() {
        assertEquals("pulse://room/c1", PulseDeepLink.roomUri("c1"))
        assertEquals("pulse://room/c1?jump=m1", PulseDeepLink.roomUri("c1", "m1"))
        assertEquals("pulse://room/c1", PulseDeepLink.roomUri("c1", ""))
        assertEquals("pulse://room/c1", PulseDeepLink.roomUri("c1", null))
    }

    @Test
    fun `parses the jump query param on both wire shapes`() {
        assertEquals(
            PulseDeepLink.Room("c1", "m1"),
            PulseDeepLink.parse("pulse://room/c1?jump=m1"),
        )
        assertEquals(
            PulseDeepLink.Room("c9", "m9"),
            PulseDeepLink.parse("pulse:chat/c9?jump=m9"),
        )
        // Round-trip: builder → parser keeps the anchored message.
        assertEquals(
            PulseDeepLink.Room("c1", "m1"),
            PulseDeepLink.parse(PulseDeepLink.roomUri("c1", "m1")),
        )
    }

    @Test
    fun `other query params and non-room kinds ignore jump`() {
        assertEquals(PulseDeepLink.Room("c1"), PulseDeepLink.parse("pulse://room/c1?x=1"))
        assertEquals(PulseDeepLink.Room("c1", "m1"), PulseDeepLink.parse("pulse://room/c1?x=1&jump=m1"))
        assertEquals(PulseDeepLink.User("u1"), PulseDeepLink.parse("pulse://user/u1?jump=m1"))
    }
    @Test fun `pasted invite links extract only a valid invite code`() {
        for (raw in listOf(" abcd1234 ", "pulse://invite/abcd1234?from=share", "pulse:join/ABCD1234",
            "https://pulse.test/?join=abcd1234&other=1", "https://pulse.test/invite/ABCD1234/")) {
            assertEquals("ABCD1234", PulseDeepLink.inviteCode(raw))
        }
        for (raw in listOf(null, "", "abc", "pulse://room/ABCD1234", "javascript:ABCD1234",
            "https://pulse.test/user/ABCD1234", "https://pulse.test/?join=%ZZ")) {
            assertNull(PulseDeepLink.inviteCode(raw))
        }
    }

    @Test fun `OS rendered FCM taps preserve the room and message without requiring a URI`() {
        assertEquals(PulseDeepLink.Room("room", "message"), PulseDeepLink.notificationRoom("message", " room ", " message "))
        assertEquals(PulseDeepLink.Room("room"), PulseDeepLink.notificationRoom("message", "room", "  "))
        for (kind in listOf("call", "gcall")) {
            assertEquals(PulseDeepLink.Room("room"), PulseDeepLink.notificationRoom(kind, "room", "not-a-message"))
        }
        for (kind in listOf(null, "unknown", "")) assertNull(PulseDeepLink.notificationRoom(kind, "room", "message"))
        assertNull(PulseDeepLink.notificationRoom("message", "  ", "message"))
        assertNull(PulseDeepLink.notificationRoom("message", null, "message"))
    }

    @Test fun `room URI round trips reserved delimiters and percent escapes exactly once`() {
        val room = "room/a+b%2F?&é"
        val message = "message/one+two%2F?&é"
        assertEquals(PulseDeepLink.Room(room, message), PulseDeepLink.parse(PulseDeepLink.roomUri(room, message)))
        assertEquals(PulseDeepLink.Room("a+b"), PulseDeepLink.parse("pulse://room/a+b"))
        assertEquals(PulseDeepLink.Room("a%2Fb", "m%2F1"), PulseDeepLink.parse("pulse://room/a%252Fb?jump=m%252F1"))
    }

}

package app.pulse.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wave 6 F-DL — deep-link parser contract: the three families, both wire
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

    // ── R7 item 4 — reminder jump payload on room deep links ──

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
}

package app.pulse.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 6 - cross-gateway push provenance kernel: a payload stamped with a
 * foreign deployment's origin is dropped; a matching origin and legacy
 * unstamped payloads are accepted.
 */
class PushOriginGateTest {

    @Test
    fun `unstamped legacy payloads are always accepted`() {
        assertFalse(PushOriginGate.isForeign(null, "https://gateway.test"))
        assertFalse(PushOriginGate.isForeign("", "https://gateway.test"))
        assertFalse(PushOriginGate.isForeign("   ", "https://gateway.test"))
    }

    @Test
    fun `a matching origin is accepted with normalization`() {
        assertTrue(PushOriginGate.isForeign("https://gateway.test", null))
        assertFalse(PushOriginGate.isForeign("https://gateway.test", "https://gateway.test"))
        // Trailing slash + case normalization never turns a match into a drop.
        assertFalse(PushOriginGate.isForeign("https://Gateway.test/", "https://gateway.test"))
        assertFalse(PushOriginGate.isForeign("https://gateway.test", "https://GATEWAY.test/"))
        assertFalse(PushOriginGate.isForeign("http://gw.test:3000", "http://gw.test:3000/"))
    }

    @Test
    fun `a foreign deployment origin is dropped`() {
        assertTrue(PushOriginGate.isForeign("https://other.test", "https://gateway.test"))
        assertTrue(PushOriginGate.isForeign("https://gateway.test:8443", "https://gateway.test"))
        assertTrue(PushOriginGate.isForeign("http://gateway.test", "https://gateway.test"))
        assertTrue(PushOriginGate.isForeign("https://gateway.test", null))
    }
}

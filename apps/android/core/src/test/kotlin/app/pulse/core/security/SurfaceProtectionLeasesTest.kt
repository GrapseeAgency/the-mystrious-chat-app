package app.pulse.core.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SurfaceProtectionLeasesTest {
    @Test fun `closing one room cannot clear another room or a successor lease`() {
        val leases = SurfaceProtectionLeases(); val window = Any()
        var secure = false
        val first = leases.acquire(window, { secure }, { secure = it })
        val second = leases.acquire(window, { secure }, { secure = it })
        assertTrue(secure)
        first(); first(); assertTrue(secure)
        second(); assertFalse(secure)
        val replacement = leases.acquire(window, { secure }, { secure = it })
        second(); assertTrue(secure)
        replacement(); assertFalse(secure)
    }
    @Test fun `preexisting platform protection is preserved and other windows are independent`() {
        val leases = SurfaceProtectionLeases()
        var preexisting = true; var other = false
        val first = leases.acquire(Any(), { preexisting }, { preexisting = it })
        val second = leases.acquire(Any(), { other }, { other = it })
        first(); assertTrue(preexisting); assertTrue(other)
        second(); assertFalse(other); assertTrue(preexisting)
    }
}

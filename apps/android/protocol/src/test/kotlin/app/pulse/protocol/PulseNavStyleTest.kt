package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R4-B item 3 — JVM pins for the navigation-style registry parser. The id
 * strings are the web nav-registry.ts values verbatim; the parse rules pin
 * the "5 excluded ids → capsule" and "junk → capsule" fallbacks.
 */
class PulseNavStyleTest {

    @Test
    fun `every included style parses from its web id string`() {
        val cases = mapOf(
            "capsule" to PulseNavStyle.CAPSULE,
            "floating-top" to PulseNavStyle.FLOATING_TOP,
            "pill" to PulseNavStyle.PILL,
            "bottom-bar" to PulseNavStyle.BOTTOM_BAR,
            "tab-bar" to PulseNavStyle.TAB_BAR,
            "floating-tab-bar" to PulseNavStyle.FLOATING_TAB_BAR,
            "rail" to PulseNavStyle.RAIL,
            "island" to PulseNavStyle.ISLAND,
        )
        cases.forEach { (raw, expected) -> assertEquals(expected, PulseNavStyle.fromPersisted(raw)) }
        assertEquals(8, PulseNavStyle.entries.size)
        assertEquals(8, cases.size)
    }

    @Test
    fun `the five excluded web ids parse to the capsule fallback`() {
        PulseNavStyle.EXCLUDED_ON_PHONE.forEach { excluded ->
            assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted(excluded), excluded)
        }
        assertEquals(setOf("floating-dock", "command-bar", "radial", "gesture", "contextual-dock"), PulseNavStyle.EXCLUDED_ON_PHONE)
    }

    @Test
    fun `junk wrong-case and null all fall back to capsule`() {
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted("definitely-junk"))
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted("CAPSULE"))
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted("Capsule"))
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted(" "))
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted(""))
        assertEquals(PulseNavStyle.CAPSULE, PulseNavStyle.fromPersisted(null))
    }

    @Test
    fun `ids are byte-identical with the web registry and round-trip`() {
        // web NAV_STYLES ids (nav-registry.ts:51-63), order preserved.
        assertEquals(
            listOf(
                "capsule", "floating-top", "pill", "bottom-bar",
                "tab-bar", "floating-tab-bar", "rail", "island",
            ),
            PulseNavStyle.entries.map { it.id },
        )
        PulseNavStyle.entries.forEach { style ->
            assertEquals(style, PulseNavStyle.fromPersisted(style.id))
        }
    }

    @Test
    fun `prefs key matches the web localStorage key exactly`() {
        assertTrue(PulseNavStyle.PREFS_KEY == "pulse.navStyle.v2")
        assertEquals("capsule", PulseNavStyle.DEFAULT_ID)
    }
}

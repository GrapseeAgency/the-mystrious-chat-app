package app.pulse.protocol

import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R7 item 1 — the clustering kernel contract (web chat-room.tsx:1376-1393
 * parity): sender/gap/day/anon/anonAlias break rules + head/tail wiring.
 */
class ClusterKernelTest {

    private val zone = ZoneId.of("UTC")

    private fun entry(
        id: String,
        senderId: String = "u1",
        createdAtMs: Long,
        anon: Boolean = false,
        anonAlias: String? = null,
    ) = PulseClusterKernel.Entry(id, senderId, createdAtMs, anon, anonAlias)

    @Test
    fun `same sender inside 5 minutes clusters into one head`() {
        val t0 = 1_700_000_000_000L
        val flags = PulseClusterKernel.flags(
            listOf(
                entry("m1", createdAtMs = t0),
                entry("m2", createdAtMs = t0 + 60_000),
                entry("m3", createdAtMs = t0 + 120_000),
            ),
            zone,
        )
        assertTrue(flags.getValue("m1").head)
        assertFalse(flags.getValue("m2").head)
        assertFalse(flags.getValue("m3").head)
        assertFalse(flags.getValue("m1").tail)
        assertFalse(flags.getValue("m2").tail)
        assertTrue(flags.getValue("m3").tail)
    }

    @Test
    fun `gap over 5 minutes opens a new head`() {
        val t0 = 1_700_000_000_000L
        val flags = PulseClusterKernel.flags(
            listOf(
                entry("m1", createdAtMs = t0),
                entry("m2", createdAtMs = t0 + PulseClusterKernel.CLUSTER_WINDOW_MS),
                // web compares against the IMMEDIATELY previous message
                // (chat-room.tsx:1391,1393) — so the >window gap must be
                // measured from m2, not from m1.
                entry("m3", createdAtMs = t0 + 2 * PulseClusterKernel.CLUSTER_WINDOW_MS + 1),
            ),
            zone,
        )
        // Exactly AT the window is NOT a break (web uses a strict > comparison).
        assertFalse(flags.getValue("m2").head)
        assertTrue(flags.getValue("m3").head)
    }

    @Test
    fun `sender change opens a new head`() {
        val t0 = 1_700_000_000_000L
        val flags = PulseClusterKernel.flags(
            listOf(
                entry("a", senderId = "u1", createdAtMs = t0),
                entry("b", senderId = "u2", createdAtMs = t0 + 1_000),
                entry("c", senderId = "u1", createdAtMs = t0 + 2_000),
            ),
            zone,
        )
        assertTrue(flags.getValue("a").head)
        assertTrue(flags.getValue("b").head)
        assertTrue(flags.getValue("c").head)
        // tails close on every break
        assertTrue(flags.getValue("a").tail)
        assertTrue(flags.getValue("b").tail)
        assertTrue(flags.getValue("c").tail)
    }

    @Test
    fun `anon toggle and alias change each open a new head`() {
        val t0 = 1_700_000_000_000L
        val flags = PulseClusterKernel.flags(
            listOf(
                entry("a", createdAtMs = t0),
                entry("b", createdAtMs = t0 + 1_000, anon = true, anonAlias = "Ember the Falcon"),
                entry("c", createdAtMs = t0 + 2_000, anon = true, anonAlias = "Ember the Falcon"),
                entry("d", createdAtMs = t0 + 3_000, anon = true, anonAlias = "Ember the Otter"),
            ),
            zone,
        )
        assertTrue(flags.getValue("a").head)
        assertTrue(flags.getValue("b").head) // anon toggle → fresh cluster (R24-b)
        assertFalse(flags.getValue("c").head) // identical anon + alias keeps clustering
        assertTrue(flags.getValue("d").head) // alias change → fresh cluster
    }

    @Test
    fun `local day change opens a new head even inside the window`() {
        // 200ms apart, but spanning UTC midnight → different local days.
        val late = 1_700_006_399_900L // 2023-11-14T23:59:59.900Z
        val early = 1_700_006_400_100L // 2023-11-15T00:00:00.100Z
        val flags = PulseClusterKernel.flags(
            listOf(
                entry("a", createdAtMs = late),
                entry("b", createdAtMs = early),
            ),
            zone,
        )
        assertTrue(flags.getValue("a").head)
        assertTrue(flags.getValue("b").head)
    }

    @Test
    fun `empty input yields an empty map`() {
        assertEquals(emptyMap<String, PulseClusterKernel.Flags>(), PulseClusterKernel.flags(emptyList(), zone))
    }

    @Test
    fun `single message is head and tail`() {
        val flags = PulseClusterKernel.flags(listOf(entry("only", createdAtMs = 1_700_000_000_000L)), zone)
        assertEquals(PulseClusterKernel.Flags(head = true, tail = true), flags.getValue("only"))
    }
}

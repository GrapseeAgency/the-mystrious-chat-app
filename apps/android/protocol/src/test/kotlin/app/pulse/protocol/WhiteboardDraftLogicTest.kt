package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R2-C item 4 — the whiteboard pending-stroke DRAFT store logic (pure port
 * of iOS PulseWhiteboardDraft.swift): encode/decode roundtrip, junk-tolerant
 * restore, restore-time dedupe against the server snapshot and the purge
 * math for the flush-verdict path.
 */
class WhiteboardDraftLogicTest {

    private fun stroke(
        vararg points: Pair<Double, Double>,
        color: String = "#10b981",
        width: Double = 3.0,
    ): WhiteboardStrokePostDto = WhiteboardStrokePostDto(
        color = color,
        width = width,
        points = points.map { listOf(it.first, it.second) },
    )

    // ── encode / decode roundtrip ───────────────────────────────

    @Test
    fun `encode decode roundtrip preserves strokes oldest first`() {
        val draft = listOf(
            stroke(0.1 to 0.2, 0.3 to 0.4),
            stroke(color = "#f43f5e", width = 8.0, points = *arrayOf(0.5 to 0.5, 0.6 to 0.7)),
        )
        val raw = PulseWhiteboardDraftLogic.encode(draft)
        val restored = PulseWhiteboardDraftLogic.decode(raw)
        assertEquals(draft, restored)
        assertEquals("#10b981", restored.first().color)
        assertEquals("#f43f5e", restored.last().color)
    }

    @Test
    fun `decode degrades junk and blank to empty draft`() {
        assertTrue(PulseWhiteboardDraftLogic.decode(null).isEmpty())
        assertTrue(PulseWhiteboardDraftLogic.decode("").isEmpty())
        assertTrue(PulseWhiteboardDraftLogic.decode("   ").isEmpty())
        assertTrue(PulseWhiteboardDraftLogic.decode("not json at all").isEmpty())
        assertTrue(PulseWhiteboardDraftLogic.decode("{\"wallpaper\":\"aurora\"}").isEmpty())
    }

    @Test
    fun `decode drops pointless empty-point rows`() {
        // An array containing one empty-point stroke decodes to nothing.
        val onlyEmpty = "[{\"color\":\"#10b981\",\"width\":3.0,\"points\":[]}]"
        assertTrue(PulseWhiteboardDraftLogic.decode(onlyEmpty).isEmpty())
    }

    // ── restore-time dedupe (server snapshot already has the stroke) ──

    @Test
    fun `droppingSynced drops draft strokes the snapshot already carries`() {
        val synced = stroke(0.1 to 0.2, 0.3 to 0.4)
        val unsynced = stroke(color = "#f43f5e", points = *arrayOf(0.9 to 0.9, 0.8 to 0.8))
        val snapshot = listOf(
            WhiteboardStrokeDto(id = "s1", color = synced.color, width = synced.width, points = synced.points),
        )
        val kept = PulseWhiteboardDraftLogic.droppingSynced(listOf(synced, unsynced), snapshot)
        assertEquals(listOf(unsynced), kept)
    }

    @Test
    fun `droppingSynced keeps everything against an empty snapshot`() {
        val draft = listOf(stroke(0.1 to 0.2, 0.3 to 0.4))
        assertEquals(draft, PulseWhiteboardDraftLogic.droppingSynced(draft, emptyList()))
    }

    @Test
    fun `droppingSynced width difference means NOT synced`() {
        val draft = listOf(stroke(width = 3.0, points = *arrayOf(0.1 to 0.2, 0.3 to 0.4)))
        val snapshot = listOf(WhiteboardStrokeDto(id = "s1", color = "#10b981", width = 4.0, points = draft[0].points))
        assertEquals(draft, PulseWhiteboardDraftLogic.droppingSynced(draft, snapshot))
    }

    // ── purge math (flush verdict / undo) ───────────────────────

    @Test
    fun `droppingFirst removes exactly the acknowledged batch`() {
        val a = stroke(0.1 to 0.1, 0.2 to 0.2)
        val b = stroke(0.3 to 0.3, 0.4 to 0.4)
        val c = stroke(0.5 to 0.5, 0.6 to 0.6)
        assertEquals(listOf(c), PulseWhiteboardDraftLogic.droppingFirst(listOf(a, b, c), 2))
    }

    @Test
    fun `droppingFirst clamps over-drops and ignores non-positive counts`() {
        val a = stroke(0.1 to 0.1, 0.2 to 0.2)
        assertTrue(PulseWhiteboardDraftLogic.droppingFirst(listOf(a), 5).isEmpty())
        assertEquals(listOf(a), PulseWhiteboardDraftLogic.droppingFirst(listOf(a), 0))
        assertEquals(listOf(a), PulseWhiteboardDraftLogic.droppingFirst(listOf(a), -1))
        assertTrue(PulseWhiteboardDraftLogic.droppingFirst(emptyList(), 1).isEmpty())
    }

    @Test
    fun `droppingLast removes the newest pending stroke`() {
        val a = stroke(0.1 to 0.1, 0.2 to 0.2)
        val b = stroke(0.3 to 0.3, 0.4 to 0.4)
        assertEquals(listOf(a), PulseWhiteboardDraftLogic.droppingLast(listOf(a, b)))
        assertTrue(PulseWhiteboardDraftLogic.droppingLast(emptyList()).isEmpty())
    }
}

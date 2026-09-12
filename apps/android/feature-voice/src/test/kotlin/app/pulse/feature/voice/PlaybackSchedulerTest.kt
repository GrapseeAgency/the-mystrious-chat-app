package app.pulse.feature.voice

import app.pulse.feature.voice.engine.PlaybackScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-peer jitter playhead contract (spec §1.1 VR-5) — including WEB DEFECT
 * FIX #2: a roster-dropped peer resets its state so its REJOIN (seq restarts
 * at 1) plays immediately instead of being deduped into silence.
 */
class PlaybackSchedulerTest {

    private val dur = 250L

    @Test
    fun `first chunk schedules at now plus the 85ms pre-roll`() {
        val s = PlaybackScheduler()
        val d = s.decide("u1", seq = 1, durationMs = dur, nowMs = 1_000)
        assertTrue(d is PlaybackScheduler.Decision.Schedule)
        assertEquals(1_085L, (d as PlaybackScheduler.Decision.Schedule).atMs)
    }

    @Test
    fun `stale and duplicate seq are dropped`() {
        val s = PlaybackScheduler()
        s.decide("u1", 3, dur, nowMs = 1_000)
        assertTrue(s.decide("u1", 3, dur, nowMs = 1_100) is PlaybackScheduler.Decision.Drop) // dup
        assertTrue(s.decide("u1", 2, dur, nowMs = 1_200) is PlaybackScheduler.Decision.Drop) // stale
        assertTrue(s.decide("u1", 4, dur, nowMs = 1_300) is PlaybackScheduler.Decision.Schedule) // live
    }

    @Test
    fun `scheduleAt is max(now+85 nextAt) - chunks chain gapless`() {
        val s = PlaybackScheduler()
        // First block at 1085 (1000+85) → nextAt 1335.
        assertEquals(1_085L, (s.decide("u1", 1, dur, 1_000) as PlaybackScheduler.Decision.Schedule).atMs)
        // Second arrives early (now 1100): max(1185, 1335) = 1335 — no overlap.
        assertEquals(1_335L, (s.decide("u1", 2, dur, 1_100) as PlaybackScheduler.Decision.Schedule).atMs)
        // Third after a pause (now 2000): nextAt 1585 is in the past → now+85 wins.
        assertEquals(2_085L, (s.decide("u1", 3, dur, 2_000) as PlaybackScheduler.Decision.Schedule).atMs)
    }

    @Test
    fun `peers are independent`() {
        val s = PlaybackScheduler()
        assertEquals(1_085L, (s.decide("a", 5, dur, 1_000) as PlaybackScheduler.Decision.Schedule).atMs)
        assertEquals(1_085L, (s.decide("b", 1, dur, 1_000) as PlaybackScheduler.Decision.Schedule).atMs)
        // b's seq 1 again is a dup even though a's sequence moved on.
        assertTrue(s.decide("b", 1, dur, 1_200) is PlaybackScheduler.Decision.Drop)
    }

    @Test
    fun `resetPeer on roster drop lets the rejoin seq-1 play - WEB DEFECT FIX 2`() {
        val s = PlaybackScheduler()
        s.decide("u1", 1, dur, 1_000)
        s.decide("u1", 2, dur, 1_300)
        s.resetPeer("u1")
        // Peer rejoins and the relay seq restarts at 1 — without the reset the
        // receiver's lastSeq=2 would blackhole every subsequent chunk.
        val d = s.decide("u1", 1, dur, nowMs = 9_000)
        assertEquals(9_085L, (d as PlaybackScheduler.Decision.Schedule).atMs)
        // The unknown peer reset is a no-op, and other peers keep their state.
        s.resetPeer("ghost")
        assertTrue(s.decide("u1", 1, dur, 9_100) is PlaybackScheduler.Decision.Drop)
    }

    @Test
    fun `full reset clears every peer`() {
        val s = PlaybackScheduler()
        s.decide("u1", 4, dur, 1_000)
        s.decide("u2", 9, dur, 1_000)
        s.reset()
        assertTrue(s.decide("u1", 1, dur, 2_000) is PlaybackScheduler.Decision.Schedule)
        assertTrue(s.decide("u2", 1, dur, 2_000) is PlaybackScheduler.Decision.Schedule)
    }

    @Test
    fun `pre-roll constant is the web-parity 85ms`() {
        assertEquals(85L, PlaybackScheduler.PRE_ROLL_MS)
    }
}

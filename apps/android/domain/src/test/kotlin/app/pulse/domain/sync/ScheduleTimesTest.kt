package app.pulse.domain.sync

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleTimesTest {
    @Test fun `snapshot presets use elapsed durations and the next local morning across DST`() {
        val now = Instant.parse("2026-10-31T12:00:00.123456Z")
        val zone = ZoneId.of("America/New_York")
        val choices = ScheduleTimes.Preset.values().associateWith { ScheduleTimes.resolve(it, now, zone) }
        assertEquals(Instant.parse("2026-10-31T13:00:00.123Z"), choices[ScheduleTimes.Preset.HOUR])
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), choices[ScheduleTimes.Preset.TOMORROW_MORNING])
        assertEquals(Instant.parse("2026-11-07T12:00:00.123Z"), choices[ScheduleTimes.Preset.WEEK])
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), choices[ScheduleTimes.Preset.TONIGHT])
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), ScheduleTimes.resolve(ScheduleTimes.Preset.TOMORROW_MORNING,
            Instant.parse("2026-03-07T12:00:00Z"), zone))
        assertEquals(Instant.parse("2026-10-24T03:00:00Z"), ScheduleTimes.resolve(ScheduleTimes.Preset.TOMORROW_MORNING,
            Instant.parse("2026-10-23T02:00:00Z"), ZoneId.of("Asia/Dhaka")))
    }
    @Test fun `only an unchanged submitted instruction may recover an expired scheduling window`() {
        val selected = Instant.parse("2026-10-31T13:00:00Z")
        val attempt = ScheduleTimes.Attempt("Hello", selected)
        val later = selected.plusSeconds(3600)
        assertTrue(ScheduleTimes.canSubmit(" Hello ", selected, later, attempt))
        assertFalse(ScheduleTimes.canSubmit("Hello", selected, later, null))
        assertFalse(ScheduleTimes.canSubmit("Changed", selected, later, attempt))
        assertFalse(ScheduleTimes.canSubmit("Hello", selected.plusSeconds(1), later, attempt))
        assertFalse(ScheduleTimes.canSubmit("", selected, later, attempt))
        assertTrue(ScheduleTimes.canSubmit("Hello", selected, selected.minusSeconds(30), null))
        assertFalse(ScheduleTimes.canSubmit("Hello", selected, selected.minusSeconds(29), null))
    }
}

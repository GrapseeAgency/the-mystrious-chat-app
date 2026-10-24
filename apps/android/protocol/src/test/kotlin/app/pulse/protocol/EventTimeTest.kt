package app.pulse.protocol

import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventTimeTest {
    @Test fun localInputUsesTheDeviceZoneAndNeverInventsUTC() {
        val dhaka = ZoneId.of("Asia/Dhaka")
        assertEquals("2026-10-22T12:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22 18:00", dhaka))
        assertEquals("2026-10-22T12:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22T18:00", dhaka))
        assertEquals("2026-10-22T13:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22", dhaka))
        assertEquals("2026-10-22T18:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22T18:00:00Z", dhaka))
        assertEquals("2026-10-22T12:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22T18:00:00+06:00", dhaka))
        assertEquals("2026-10-22T23:00:00Z", PulseWave7Logic.parseEventStart("2026-10-22T18:00:00-05:00", dhaka))
        assertNull(PulseWave7Logic.parseEventStart("2026-02-30 12:00", dhaka))
        assertNull(PulseWave7Logic.parseEventStart("2026-10-22 24:30", dhaka))
    }
    @Test fun daylightSavingGapsAndOverlapsNeedExplicitOffset() {
        val ny = ZoneId.of("America/New_York")
        assertNull(PulseWave7Logic.parseEventStart("2026-03-08 02:30", ny))
        assertNull(PulseWave7Logic.parseEventStart("2026-11-01 01:30", ny))
        assertEquals("2026-11-01T05:30:00Z", PulseWave7Logic.parseEventStart("2026-11-01T01:30:00-04:00", ny))
        assertEquals("2026-11-01T06:30:00Z", PulseWave7Logic.parseEventStart("2026-11-01T01:30:00-05:00", ny))
    }
}

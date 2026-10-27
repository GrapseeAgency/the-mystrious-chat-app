package app.pulse.domain.sync

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Resolve once per picker presentation. Retrying must not silently move the deadline. */
object ScheduleTimes {
    enum class Preset { HOUR, TOMORROW_MORNING, WEEK, TONIGHT }

    fun resolve(preset: Preset, now: Instant, zone: ZoneId): Instant = when (preset) {
        Preset.HOUR -> now.plusSeconds(3600)
        Preset.WEEK -> now.plusSeconds(7 * 86400L)
        Preset.TOMORROW_MORNING -> now.atZone(zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant()
        Preset.TONIGHT -> now.atZone(zone).toLocalDate().atTime(20, 0).atZone(zone).toInstant()
    }.truncatedTo(ChronoUnit.MILLIS)

    data class Attempt(val text: String, val at: Instant)

    fun canSubmit(text: String, at: Instant, now: Instant, previous: Attempt?): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > 2000) return false
        // Only the same instruction may bypass NEW admission's window to recover its receipt.
        return previous == Attempt(normalized, at) ||
            (!at.isBefore(now.plusSeconds(30)) && !at.isAfter(now.plusSeconds(30L * 86400)))
    }
}

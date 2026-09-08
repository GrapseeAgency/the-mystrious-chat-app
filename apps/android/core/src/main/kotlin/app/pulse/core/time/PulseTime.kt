package app.pulse.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Wire-time helpers — the wire carries ISO-8601 timestamps ("…Z"). */
object PulseTime {

    fun parse(raw: String?): OffsetDateTime? = runCatching {
        OffsetDateTime.parse(raw)
    }.getOrElse {
        runCatching {
            Instant.parse(raw).atOffset(ZoneId.systemDefault().rules.getOffset(Instant.now()))
        }.getOrNull()
    }

    /** "14:32" today, "Mar 3" older — list-row parity with the web inbox. */
    fun shortLabel(raw: String?): String {
        val t = parse(raw) ?: return ""
        val local = t.atZoneSameInstant(ZoneId.systemDefault())
        return if (local.toLocalDate() == LocalDate.now()) {
            local.format(DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            local.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }

    /** "14:32" — bubble meta line. */
    fun clock(raw: String?): String {
        val t = parse(raw) ?: return ""
        return t.atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    /** Day chip label: "Today" / "Yesterday" / "Mar 3". */
    fun dayChip(raw: String?): String {
        val t = parse(raw) ?: return ""
        val date = t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        return when (date) {
            LocalDate.now() -> "Today"
            LocalDate.now().minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }
}

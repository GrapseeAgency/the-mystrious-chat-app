package app.pulse.data.remote

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** HTTP Retry-After accepts either delta-seconds or an HTTP date. */
internal fun retryAfterSeconds(header: String?, serverDate: String?, now: Instant = Instant.now()): Int? {
    val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { return if (it >= 0) it.coerceIn(1, 86_400).toInt() else null }
    fun parse(value: String?): Instant? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()
    val until = parse(raw) ?: return null
    val base = parse(serverDate) ?: now
    val remaining = java.time.Duration.between(base, until).toMillis()
    return ((remaining.coerceAtLeast(0L) + 999) / 1000).coerceIn(1, 86_400).toInt()
}

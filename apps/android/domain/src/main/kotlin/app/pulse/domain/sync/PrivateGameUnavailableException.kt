package app.pulse.domain.sync

/** A denied/gone private read must retire the visible snapshot. Transient
 * network failures may retain it, but must not be confused with this verdict. */
class PrivateGameUnavailableException(message: String) : IllegalStateException(message)

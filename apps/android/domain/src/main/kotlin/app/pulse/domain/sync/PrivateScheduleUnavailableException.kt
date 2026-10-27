package app.pulse.domain.sync

/** Denial retires private scheduled drafts; transport failures retain confirmed rows. */
class PrivateScheduleUnavailableException(message: String) : IllegalStateException(message)

package app.pulse.domain.sync

import app.pulse.domain.model.Message

/** Pins/search retain selected IDs, not detached copies of message content.
 * Missing cache rows are not proof of deletion (history may be partial). */
fun reconcileMessageSelection(
    selected: List<Message>, current: List<Message>, now: Long,
    keep: (Message) -> Boolean = { true },
): List<Message> {
    val latest = current.associateBy { it.id }
    return selected.map { latest[it.id] ?: it }.filter {
        !it.isDeleted && !it.viewOnce && (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false) && keep(it)
    }
}

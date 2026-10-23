package app.pulse.domain.sync

import app.pulse.domain.model.Message

/** IDs cannot be resurrected; consumption and edit stamps survive delayed envelopes.
 * This is not a total server revision: reactions/pins still need their own ordering. */
fun mergeMessageState(current: Message?, incoming: Message): Message {
    if (current != null && current.id == incoming.id && current.conversationId != incoming.conversationId) return mergeMessageState(null, current)
    val prior = current?.takeIf { it.id == incoming.id }
    var result = if (prior?.isDeleted == true && !incoming.isDeleted) prior else incoming
    if (prior?.viewOnce == true) result = result.copy(viewOnce = true)
    if (prior?.viewedOnce == true) result = result.copy(viewedOnce = true)
    if (prior?.viewedAt != null) result = result.copy(viewedAt = prior.viewedAt, viewOnce = true)
    val expiry = prior?.expiresAtEpochMs
    if (expiry != null && (result.expiresAtEpochMs?.let { it > expiry } != false)) result = result.copy(expiresAtEpochMs = expiry)
    if (result.viewedAt != null) result = result.copy(viewOnce = true)
    if (result.isDeleted) return result.copy(body = "", payload = null, replyToId = null, replyToBody = null,
        replyToAuthor = null, quoteBounds = null, imagePath = null, audioPath = null, durationMs = null, filePath = null,
        fileName = null, fileSize = null, transcript = null, transcribedAt = null, poll = null,
        linkPreview = null, pinnedAt = null, reactions = emptyList())
    // Closure freezes the tally. A late open snapshot must never reopen voting
    // or replace final results, even if it arrived through a different action.
    val priorPoll = prior?.poll
    if (priorPoll?.closed == true) {
        result = result.copy(poll = priorPoll)
    }
    fun stamp(value: String?): Long = value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MIN_VALUE
    if (prior != null && stamp(prior.editedAt) > stamp(result.editedAt)) {
        result = result.copy(body = prior.body, editedAt = prior.editedAt)
    }
    if (prior?.replyToId != null && prior.replyToId == result.replyToId) {
        val oldBounds = prior.quoteBounds
        val bounds = result.quoteBounds
        if (oldBounds?.version == 1 && bounds?.version == 1) {
            result = result.copy(quoteBounds = bounds.copy(
                deleted = bounds.deleted || oldBounds.deleted,
                viewOnce = bounds.viewOnce || oldBounds.viewOnce,
                expiresAtEpochMs = listOfNotNull(bounds.expiresAtEpochMs, oldBounds.expiresAtEpochMs).minOrNull(),
            ))
        }
    }
    return result.redactQuote()
}

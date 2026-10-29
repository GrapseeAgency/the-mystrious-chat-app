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
    // Section-5 "Old Android expiry loss": Long.MIN_VALUE is the malformed
    // sentinel (unavailable deadline), never a real lease. A malformed value
    // must neither overwrite a parseable prior nor clamp a fresh server
    // deadline down to it; the raw wire string rides expiresAtRaw so the
    // stored column self-heals on the next authoritative envelope.
    result = result.copy(expiresAtRaw = result.expiresAtRaw ?: prior?.expiresAtRaw)
    val expiry = prior?.expiresAtEpochMs
    val incomingExpiry = result.expiresAtEpochMs
    if (incomingExpiry == null) {
        if (expiry != null) result = result.copy(expiresAtEpochMs = expiry)
    } else if (expiry != null) {
        result = when {
            incomingExpiry == Long.MIN_VALUE && expiry != Long.MIN_VALUE -> result.copy(expiresAtEpochMs = expiry)
            incomingExpiry != Long.MIN_VALUE && expiry == Long.MIN_VALUE -> result
            else -> result.copy(expiresAtEpochMs = minOf(incomingExpiry, expiry))
        }
    }
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
        result = when {
            // A later envelope whose snippet fails the certification gate must
            // not downgrade bounds the server already certified (monotonic
            // tightening - the same policy as expiry and tombstones).
            bounds == null && oldBounds?.version == 1 -> result.copy(quoteBounds = oldBounds)
            oldBounds?.version == 1 && bounds?.version == 1 -> result.copy(quoteBounds = bounds.copy(
                deleted = bounds.deleted || oldBounds.deleted,
                viewOnce = bounds.viewOnce || oldBounds.viewOnce,
                expiresAtEpochMs = listOfNotNull(bounds.expiresAtEpochMs, oldBounds.expiresAtEpochMs).minOrNull(),
                // Section-5 "Uncached child-root provenance": learned v2
                // provenance survives re-delivery of a v1 envelope, so the
                // root-only revocation scan keeps finding this row.
                sourceEditedAtEpochMs = bounds.sourceEditedAtEpochMs ?: oldBounds.sourceEditedAtEpochMs,
                sourceRootId = bounds.sourceRootId ?: oldBounds.sourceRootId,
            ))
            else -> result
        }
    }
    return result.redactQuote()
}

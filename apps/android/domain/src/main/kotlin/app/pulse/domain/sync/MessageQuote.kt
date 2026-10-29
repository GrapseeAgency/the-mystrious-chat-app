package app.pulse.domain.sync

import app.pulse.domain.model.Message

data class QuotePreview(val senderName: String, val content: String)

/** Legacy snippets have neither anonymous attribution nor an expiry witness. */
fun Message.visibleQuote(nowMillis: Long = System.currentTimeMillis()): QuotePreview? {
    if (replyToId == null || isDeleted) return null
    val bounds = quoteBounds
    if (bounds?.version != 1) return QuotePreview("Reply", "Quoted message unavailable")
    if (bounds.deleted || bounds.expiresAtEpochMs?.let { it <= nowMillis } == true)
        return QuotePreview("Unavailable message", "Quoted message unavailable")
    return QuotePreview(replyToAuthor ?: "Reply", if (bounds.viewOnce) "View-once message" else replyToBody.orEmpty())
}

/** Clear sensitive derived fields, retaining the source id for navigation. */
fun Message.redactQuote(nowMillis: Long = System.currentTimeMillis()): Message {
    if (replyToId == null) return this
    val bounds = quoteBounds
    if (bounds?.version != 1) return copy(replyToBody = "", replyToAuthor = "Reply")
    if (bounds.deleted || bounds.expiresAtEpochMs?.let { it <= nowMillis } == true)
        return copy(replyToBody = "", replyToAuthor = "Unavailable message", quoteBounds = bounds.copy(deleted = true))
    return if (bounds.viewOnce) copy(replyToBody = "View-once message") else this
}

/** A cached source may tighten a server quote, never certify a legacy snippet
 * or replace its text with a potentially older cached edit. Missing cache rows
 * are not proof of deletion; known tombstones/expiry/consumption are.
 * Section-5 "Cached quote edits": a source edit NEWER than the stamp the quote
 * body was last refreshed from propagates into the cached snippet (v2 bounds).
 * Older/equal stamps keep the server's own text; anonymous attribution still
 * wins over any cached real name. */
fun Message.restrictQuote(source: Message?, root: Message? = null, nowMillis: Long = System.currentTimeMillis()): Message {
    val bounds = quoteBounds ?: return redactQuote(nowMillis)
    if (bounds.version != 1 || source == null || source.id != replyToId) return redactQuote(nowMillis)
    val knownRoot = root?.takeIf { source.threadRootId == it.id }
    val invalid = source.id == id || source.conversationId != conversationId || source.isDeleted ||
        (knownRoot != null && (knownRoot.conversationId != conversationId || knownRoot.threadRootId != null || knownRoot.isDeleted))
    val expiry = listOfNotNull(bounds.expiresAtEpochMs, source.expiresAtEpochMs, knownRoot?.expiresAtEpochMs).minOrNull()
    // Provenance capture (v2): remember the child's thread root so a later
    // root-only revocation still reaches this quote when the child row is
    // no longer cached. Learned from the cache itself, never client input.
    val learnedRoot = knownRoot?.id ?: source.threadRootId ?: bounds.sourceRootId
    // Edit propagation: only a strictly newer source edit refreshes the body,
    // and never on view-once/deleted/expired quotes (they show fixed captions).
    val sourceEditedAt = source.editedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    val refreshed = sourceEditedAt != null && (bounds.sourceEditedAtEpochMs == null || sourceEditedAt > bounds.sourceEditedAtEpochMs) &&
        source.body.isNotBlank() && !invalid
    val nextBody = if (refreshed && !(bounds.viewOnce || source.viewOnce)) source.body else replyToBody
    return copy(
        quoteBounds = bounds.copy(deleted = bounds.deleted || invalid,
            viewOnce = bounds.viewOnce || source.viewOnce || source.viewedAt != null, expiresAtEpochMs = expiry,
            sourceEditedAtEpochMs = maxOf(bounds.sourceEditedAtEpochMs ?: Long.MIN_VALUE, sourceEditedAt ?: Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE },
            sourceRootId = learnedRoot),
        replyToBody = nextBody,
        // A partial/older source must not replace a server-provided anonymous alias with a real account name.
        replyToAuthor = if (source.anon) source.displayAuthorName else replyToAuthor,
    ).redactQuote(nowMillis)
}

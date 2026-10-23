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
 * are not proof of deletion; known tombstones/expiry/consumption are. */
fun Message.restrictQuote(source: Message?, root: Message? = null, nowMillis: Long = System.currentTimeMillis()): Message {
    val bounds = quoteBounds ?: return redactQuote(nowMillis)
    if (bounds.version != 1 || source == null || source.id != replyToId) return redactQuote(nowMillis)
    val knownRoot = root?.takeIf { source.threadRootId == it.id }
    val invalid = source.id == id || source.conversationId != conversationId || source.isDeleted ||
        (knownRoot != null && (knownRoot.conversationId != conversationId || knownRoot.threadRootId != null || knownRoot.isDeleted))
    val expiry = listOfNotNull(bounds.expiresAtEpochMs, source.expiresAtEpochMs, knownRoot?.expiresAtEpochMs).minOrNull()
    return copy(
        quoteBounds = bounds.copy(deleted = bounds.deleted || invalid,
            viewOnce = bounds.viewOnce || source.viewOnce || source.viewedAt != null, expiresAtEpochMs = expiry),
        // A partial/older source must not replace a server-provided anonymous alias with a real account name.
        replyToAuthor = if (source.anon) source.displayAuthorName else replyToAuthor,
    ).redactQuote(nowMillis)
}

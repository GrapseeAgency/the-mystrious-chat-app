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

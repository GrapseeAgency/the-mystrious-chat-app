package app.pulse.domain.model

/** Captured synchronously at Send; never put on the wire. */
data class ComposerDraftToken(val conversationId: String, val ownerId: String, val revision: Long)

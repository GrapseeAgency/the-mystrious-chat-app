package app.pulse.domain.usecase

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseRepository

/** Send a text message (inline quote via replyToId, thread reply via parentId — spec §1.1). */
class SendMessageUseCase(private val repo: PulseRepository) {
    suspend operator fun invoke(
        conversationId: String,
        body: String,
        replyToId: String? = null,
        /** Thread-root id — sent as the wire `parentId`, NEVER merged with replyToId. */
        parentId: String? = null,
        /** Wave 2 topic filing — the repo drops it on thread replies (spec §1 row 10). */
        topicId: String? = null,
    ): Result<Message> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message body is empty"))
        if (trimmed.length > MAX_LENGTH) return Result.failure(IllegalArgumentException("Message exceeds $MAX_LENGTH chars"))
        return repo.sendMessage(conversationId, trimmed, replyToId, parentId, topicId)
    }

    companion object {
        const val MAX_LENGTH = 4000
    }
}

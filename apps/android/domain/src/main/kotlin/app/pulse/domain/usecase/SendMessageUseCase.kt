package app.pulse.domain.usecase

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseRepository

/** Send a text message (reply threading is first-class, matching web). */
class SendMessageUseCase(private val repo: PulseRepository) {
    suspend operator fun invoke(
        conversationId: String,
        body: String,
        replyToId: String? = null,
    ): Result<Message> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message body is empty"))
        if (trimmed.length > MAX_LENGTH) return Result.failure(IllegalArgumentException("Message exceeds $MAX_LENGTH chars"))
        return repo.sendMessage(conversationId, trimmed, replyToId)
    }

    companion object {
        const val MAX_LENGTH = 4000
    }
}

package app.pulse.domain.usecase

import app.pulse.domain.model.SendReceipt
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
        /**
         * R3-B item 4 — F-MS-17 incognito (groups only, server clamps).
         * Rides the rich send path so the wire body carries anon:true; the
         * alias is derived server-side (web parity — chat-room.tsx:1670).
         * [anonAliasPreview] stamps the optimistic echo with the same
         * deterministic alias (web anonAliasPreview parity).
         */
        anon: Boolean = false,
        anonAliasPreview: String? = null,
    ): Result<SendReceipt> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message body is empty"))
        if (trimmed.length > MAX_LENGTH) return Result.failure(IllegalArgumentException("Message exceeds $MAX_LENGTH chars"))
        return if (anon) {
            repo.sendRichMessage(
                conversationId = conversationId,
                body = trimmed,
                replyToId = replyToId,
                parentId = parentId,
                topicId = topicId,
                anon = true,
                anonAliasPreview = anonAliasPreview,
            )
        } else {
            repo.sendMessage(conversationId, trimmed, replyToId, parentId, topicId)
        }
    }

    companion object {
        /** Server cap (serializers.ts MESSAGE_MAX) — audit D1: client was 4000, server 400s 2001-4000. */
        const val MAX_LENGTH = 2000
    }
}

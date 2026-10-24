package app.pulse.domain.model

import kotlinx.serialization.Serializable

/** Account-local, unsent selection. A restored draft is never an automatic send. */
@Serializable
data class AttachmentDraft(
    val selectionId: String,
    val conversationId: String,
    val kind: String,
    val dataUrl: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null,
    val caption: String = "",
    val viewOnce: Boolean = false,
    val topicId: String? = null,
    val revision: Long = 0,
    val replyToId: String? = null,
    val anon: Boolean = false,
)

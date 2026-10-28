package app.pulse.data.local

import app.pulse.domain.model.AttachmentDraft
import java.util.Base64

internal object AttachmentValidation {
    fun validated(draft: AttachmentDraft): AttachmentDraft {
        require(draft.selectionId.matches(Regex("[A-Za-z0-9_-]{16,128}")) && draft.conversationId.isNotBlank()) { "Attachment identity is missing" }
        val types = when (draft.kind) {
            "image" -> listOf("image/jpeg", "image/png", "image/webp")
            "audio" -> listOf("audio/mp4", "audio/aac", "audio/webm", "audio/mpeg", "audio/ogg", "audio/wav")
            "file" -> listOf("application/pdf", "application/zip", "text/plain", "text/csv")
            else -> error("Unsupported attachment kind")
        }
        val limit = if (draft.kind == "file") ((10 * 1024 * 1024 + 2) / 3) * 4 + 128 else 4_500_000
        require(draft.dataUrl.length <= limit) { "Attachment is too large; documents allow 10 MB, photos and voice notes about 3.3 MB" }
        val comma = draft.dataUrl.indexOf(',')
        require(comma > 0 && types.any { draft.dataUrl.startsWith("data:$it;base64,") }) { "Unsupported attachment format" }
        val bytes = Base64.getDecoder().decode(draft.dataUrl.substring(comma + 1))
        require(bytes.isNotEmpty() && (draft.kind != "file" || bytes.size <= 10 * 1024 * 1024)) { "Attachment is empty or too large" }
        // R42 parity - images/audio carry the same DECODED ceiling the server's
        // encoded media cap implies (4,500,000 base64 chars → 3,375,000 bytes),
        // matching the bounded source readers on both platforms.
        require(draft.kind == "file" || bytes.size <= 3_375_000) { "Attachment is too large; documents allow 10 MB, photos and voice notes about 3.3 MB" }
        require(draft.kind != "audio" || draft.durationMs?.let { it in 1L..600_000L } == true) { "Voice note duration is invalid" }
        require(draft.caption.trim().length <= 2000) { "Caption exceeds the 2000-character message limit" }
        val replyToId = draft.replyToId // Domain property is defined in another module; snapshot before narrowing.
        require(replyToId == null || (replyToId.isNotBlank() && !replyToId.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX))) {
            "Wait for the original message to send before replying to it"
        }
        require(!draft.viewOnce || draft.kind == "image") { "View-once is only supported for photos" }
        val name = draft.fileName?.trim()
        require(draft.kind != "file" || (!name.isNullOrEmpty() && name.length <= 120)) { "Document names must contain 1–120 characters" }
        return draft.copy(fileName = name, fileSize = bytes.size.toLong())
    }
}

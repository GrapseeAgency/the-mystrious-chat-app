package app.pulse.protocol

import kotlinx.serialization.Serializable

@Serializable
data class SavedIntentDto(
    val owner: String, val messageId: String, val conversationId: String,
    val saved: Boolean, val clientId: String,
    val expectedRevision: String? = null, val predecessorClientId: String? = null,
) {
    val prefix: String get() = "saved-intent:v1|$owner|$messageId|"
    val key: String get() = prefix + clientId
}
@Serializable
data class SavedStatesDto(val savedStateVersion: Int, val userId: String, val states: List<SavedStateReceiptDto>)
@Serializable
data class SavedChangedDto(val userId: String, val messageId: String)
@Serializable
data class SavedPageIndexDto(val ids: List<String>, val cursor: String?, val nextCursor: String?, val hasMore: Boolean)

fun SavedStateReceiptDto.validateSavedState(owner: String, id: String, intent: SavedIntentDto? = null) {
    require(savedReceiptVersion == 2 && userId == owner && messageId == id && !revision.isNullOrBlank() && revision.length <= 128)
    if (saved) java.time.Instant.parse(requireNotNull(savedAt)) else require(savedAt == null)
    if (intent != null) {
        require(clientId == intent.clientId && outcome in setOf("applied", "superseded", "conflict", "rejected"))
        if (outcome == "applied") require(saved == intent.saved)
    }
}

@Serializable
data class SavedOfflineCursorDto(val owner: String, val at: String, val id: String)

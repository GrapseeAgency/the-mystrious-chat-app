package app.pulse.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ComposerReceiptDto(
    val composerVersion: Int, val userId: String, val kind: String, val targetId: String,
    val rootId: String? = null, val conversationId: String, val text: String, val revision: String,
    val editedAt: String? = null, val clientId: String? = null, val outcome: String? = null,
    val textHash: String? = null,
)
@Serializable
data class ComposerFlight(val clientId: String, val text: String, val expectedRevision: String, val localEditRevision: String? = null)
@Serializable
data class ComposerIntentDto(
    val owner: String, val kind: String, val targetId: String, val conversationId: String,
    val rootId: String? = null, val desiredId: String, val text: String,
    val baseText: String, val baseEditedAt: String? = null, val ready: Boolean = true,
    val flight: ComposerFlight? = null, val blocked: String? = null, val baseRevision: String? = null, val localEditRevision: String? = null,
)
fun ComposerReceiptDto.validateComposer(intent: ComposerIntentDto, flight: ComposerFlight? = null) {
    require(composerVersion == 1 && userId == intent.owner && kind == intent.kind && targetId == intent.targetId &&
        rootId == intent.rootId && conversationId == intent.conversationId && text.length <= 2000 &&
        revision.matches(Regex("[A-Za-z0-9_.:-]{1,256}"))) { "Composer state was not confirmed" }
    if (editedAt != null) require(runCatching { java.time.Instant.parse(editedAt) }.isSuccess) { "Invalid edit timestamp" }
    if (kind == "edit" && flight != null && outcome == "applied") require(editedAt != null) { "Edit timestamp missing" }
    if (flight != null) require(clientId == flight.clientId && outcome in setOf("applied", "conflict", "superseded") &&
        (outcome != "applied" || text == flight.text)) { "Composer operation was not confirmed" }
}

@Serializable
data class ComposerLocalEditDto(val text: String, val baseText: String, val baseEditedAt: String? = null, val revision: String = java.util.UUID.randomUUID().toString())

fun validateComposerReceipt(receipt: ComposerReceiptDto, intent: ComposerIntentDto) = receipt.validateComposer(intent)

@Serializable
data class ScheduledCommandDto(val owner: String, val clientId: String, val kind: String,
    val conversationId: String = "", val text: String = "", val scheduledAt: String = "", val notice: String? = null)

/** Accepted schedules retain identity/metadata, never another copy of message text. */
@Serializable
data class ScheduledCommandAckDto(val version: Int = 1, val owner: String, val clientId: String,
    val kind: String, val requestHash: String, val item: ScheduledItemDto? = null)

/** An unknown draft baseline must not overwrite a remote clear/edit ABA. */
fun ComposerReceiptDto.hasSafeDraftBaseline(intent: ComposerIntentDto): Boolean =
    intent.kind != "draft" || intent.baseRevision != null || text == intent.text || revision.endsWith(":0")

private fun composerTextHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun ComposerReceiptDto.metadataBaseline(): ComposerReceiptDto = copy(text="",textHash=composerTextHash(text))
fun ComposerReceiptDto.matchesBaselineText(value: String): Boolean =
    textHash?.let { it == composerTextHash(value) } ?: (text == value)

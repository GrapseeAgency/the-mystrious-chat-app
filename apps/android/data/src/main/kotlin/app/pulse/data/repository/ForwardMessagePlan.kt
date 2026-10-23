package app.pulse.data.repository

import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.protocol.PulseJson
import kotlinx.serialization.json.*

/** A forward is a new owned message, not a copy of another room's routing,
 * reactions, pin, anonymous identity, expiry or delivery metadata. */
internal data class ForwardMessagePlan(val message: Message, val kind: String) {
    companion object {
        fun create(source: Message, target: String, owner: String, clientId: String, now: Long): ForwardMessagePlan {
            require(target.isNotBlank() && owner.isNotBlank() && clientId.isNotBlank()) { "A forward destination and owner are required." }
            require(!source.id.startsWith(TEMP_MESSAGE_PREFIX) && !source.viewOnce && !source.isDeleted &&
                source.expiresAtEpochMs?.let { it <= now } != true) { "This message can't be forwarded." }
            val kind = when (source.kind) {
                Message.Kind.TEXT -> "text"
                Message.Kind.IMAGE -> "image"
                Message.Kind.VOICE -> "audio"
                Message.Kind.FILE -> "file"
                Message.Kind.STICKER -> "sticker"
                Message.Kind.LOCATION -> "location"
                else -> throw IllegalArgumentException("This message type can't be forwarded. Open its original conversation instead.")
            }
            require(source.body.length <= 2000 && (kind != "text" || source.body.isNotBlank())) { "The forwarded text is empty or too long." }
            require(kind != "image" || !source.imagePath.isNullOrBlank()) { "The image is unavailable." }
            require(kind != "audio" || !source.audioPath.isNullOrBlank()) { "The audio is unavailable." }
            require(kind != "file" || (!source.filePath.isNullOrBlank() && !source.fileName.isNullOrBlank())) { "The file is unavailable." }
            val payload = source.payload?.let {
                require(it.length <= 2048) { "Forward metadata is too large." }
                PulseJson.parseToJsonElement(it) as? JsonObject ?: throw IllegalArgumentException("Forward metadata is invalid.")
            }
            if (kind == "sticker") {
                val emoji = (payload?.get("emoji") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                require(!emoji.isNullOrBlank() && emoji.length <= 12) { "The sticker is unavailable." }
            }
            if (kind == "location") {
                val lat = (payload?.get("lat") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                val lng = (payload?.get("lng") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                require(lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) { "The location is invalid." }
            }
            return ForwardMessagePlan(Message(
                id = TEMP_MESSAGE_PREFIX + clientId, conversationId = target, authorId = owner, authorName = owner,
                kind = source.kind, body = source.body, createdAt = java.time.Instant.ofEpochMilli(now).toString(),
                imagePath = source.imagePath, audioPath = source.audioPath, durationMs = source.durationMs,
                filePath = source.filePath, fileName = source.fileName, fileSize = source.fileSize, payload = app.pulse.domain.sync.MessageOrigin.forwardPayload(source.payload),
            ), kind)
        }
    }
}

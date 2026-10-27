package app.pulse.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Lenient JSON elsewhere must not turn quoted room-policy values into permission. */
object RoomPolicyBoolean : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || element.isString || element.booleanOrNull == null)
            throw SerializationException("Room policy requires a JSON boolean")
        return element
    }
}
object OptionalRoomPolicyBoolean : KSerializer<Boolean?> by RoomPolicyBoolean.nullable
object RoomPolicyInteger : JsonTransformingSerializer<Int>(Int.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || element.isString || element.intOrNull == null)
            throw SerializationException("Room policy requires a JSON integer")
        return element
    }
}
object OptionalRoomPolicyInteger : KSerializer<Int?> by RoomPolicyInteger.nullable

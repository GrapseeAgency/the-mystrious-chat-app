package app.pulse.data.remote

import app.pulse.core.result.PulseResult
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationsPageDto
import app.pulse.protocol.MessagesPageDto
import app.pulse.protocol.PulseJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putIfNotNull

/**
 * Ktor REST client — REAL wiring to the Next.js API (gateway :81 in dev).
 * The API identifies the caller with `userId` params (same as the web client);
 * every method maps failures onto PulseResult kinds identical to iOS.
 */
class PulseApi(private val http: HttpClient) {

    private suspend fun <T> call(path: String, parse: (String) -> T): PulseResult<T> = try {
        val res = http.get(app.pulse.core.PulseEndpoints.http(path))
        val text = res.bodyAsText()
        if (res.status.isSuccess()) PulseResult.Success(parse(text))
        else PulseResult.fromHttp(res.status.value, text.take(300))
    } catch (e: kotlinx.serialization.SerializationException) {
        PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    suspend fun conversations(userId: String): PulseResult<ConversationsPageDto> =
        call("/api/conversations?userId=$userId") {
            PulseJson.decodeFromString(ConversationsPageDto.serializer(), it)
        }

    suspend fun messages(conversationId: String, limit: Int = 200, before: String? = null): PulseResult<MessagesPageDto> {
        val cursor = before?.let { "&before=${it}" } ?: ""
        return call("/api/conversations/$conversationId/messages?limit=$limit$cursor") {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }
    }

    /** POST /api/conversations/{id}/messages { senderId, content } → ChatMessageDto */
    suspend fun sendMessage(conversationId: String, senderId: String, content: String): PulseResult<ChatMessageDto> =
        try {
            val payload: JsonObject = buildJsonObject {
                put("senderId", senderId)
                put("content", content)
                put("kind", "text")
            }
            val res = http.post(app.pulse.core.PulseEndpoints.http("/api/conversations/$conversationId/messages")) {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(PulseJson.decodeFromString(ChatMessageDto.serializer(), text))
            else PulseResult.fromHttp(res.status.value, text.take(300))
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }

    /** Small action POSTs (read/mute/pin/archive/block/report) share one runner. */
    suspend fun postAction(path: String, body: JsonObject? = null): PulseResult<Unit> = try {
        val res = http.post(app.pulse.core.PulseEndpoints.http(path)) {
            contentType(ContentType.Application.Json)
            if (body != null) setBody(body.toString())
        }
        if (res.status.isSuccess()) PulseResult.Success(Unit)
        else PulseResult.fromHttp(res.status.value, res.bodyAsText().take(300))
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    companion object {
        fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
            pairs.forEach { (k, v) -> putIfNotNull(k, v) }
        }
    }
}

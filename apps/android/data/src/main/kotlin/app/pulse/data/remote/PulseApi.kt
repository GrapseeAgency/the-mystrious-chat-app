package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.ConversationsPageDto
import app.pulse.protocol.HandleRegistryDto
import app.pulse.protocol.MessagesPageDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.UserDto
import app.pulse.protocol.UsernameCheckDto
import app.pulse.protocol.UsersPageDto
import app.pulse.protocol.unwrapOrRoot
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

/**
 * Ktor REST client — REAL wiring to the Next.js API (gateway :81 in dev).
 * The API identifies the caller with `userId` params (same as the web client);
 * every method maps failures onto PulseResult kinds identical to iOS.
 */
class PulseApi(private val http: HttpClient) {

    private suspend fun <T> get(path: String, parse: (String) -> T): PulseResult<T> = try {
        val res = http.get(PulseEndpoints.http(path))
        val text = res.bodyAsText()
        if (res.status.isSuccess()) PulseResult.Success(parse(text))
        else failureOf(res.status.value, text)
    } catch (e: kotlinx.serialization.SerializationException) {
        PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    private suspend fun <T> post(path: String, body: JsonObject?, parse: ((String) -> T)? = null): PulseResult<T> =
        try {
            val res = http.post(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body.toString())
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text)
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }

    /**
     * HTTP failure → Failure with the body's error/code/suggestion intact —
     * the onboarding username_taken flow needs the server's suggestion to
     * survive the trip (web parity: createUserRequest in onboarding-screen.tsx).
     */
    private fun failureOf(status: Int, body: String): PulseResult.Failure {
        var message: String? = body.take(300)
        var code: String? = null
        var suggestion: String? = null
        try {
            val json = PulseJson.parseToJsonElement(body)
            if (json is JsonObject) {
                (json["error"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) message = it.content
                }
                (json["code"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) code = it.content
                }
                (json["suggestion"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) suggestion = it.content
                }
            }
        } catch (_: Exception) {
            // non-JSON body — keep the raw text as the message
        }
        val base = PulseResult.fromHttp(status, message)
        return base.copy(code = code, suggestion = suggestion, status = status)
    }

    // ── conversations ───────────────────────────────────────────
    suspend fun conversations(userId: String): PulseResult<ConversationsPageDto> =
        get("/api/conversations?userId=$userId") {
            PulseJson.decodeFromString(ConversationsPageDto.serializer(), it)
        }

    suspend fun messages(conversationId: String, limit: Int = 200, before: String? = null): PulseResult<MessagesPageDto> {
        val cursor = before?.let { "&before=$it" } ?: ""
        return get("/api/conversations/$conversationId/messages?limit=$limit$cursor") {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }
    }

    /** POST /api/conversations/{id}/messages { senderId, content } → ChatMessageDto */
    suspend fun sendMessage(conversationId: String, senderId: String, content: String): PulseResult<ChatMessageDto> =
        post(
            "/api/conversations/$conversationId/messages",
            buildJsonObject {
                put("senderId", senderId)
                put("content", content)
                put("kind", "text")
            },
        ) { PulseJson.decodeFromString(ChatMessageDto.serializer(), it) }

    /** POST /api/conversations { creatorId, memberIds, isGroup } → tolerant conversation. */
    suspend fun createConversation(creatorId: String, memberIds: List<String>, isGroup: Boolean, name: String?): PulseResult<ConversationSummaryDto> =
        post(
            "/api/conversations",
            buildJsonObject {
                put("creatorId", creatorId)
                put("memberIds", kotlinx.serialization.json.JsonArray(memberIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("isGroup", isGroup)
                if (name != null) put("name", name)
            },
        ) { PulseJson.decodeFromString(ConversationSummaryDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString()) }

    // ── reactions ───────────────────────────────────────────────
    /** POST /api/messages/{id}/react { userId, emoji } — server toggles. */
    suspend fun react(messageId: String, userId: String, emoji: String): PulseResult<Unit> =
        post(
            "/api/messages/$messageId/react",
            buildJsonObject {
                put("userId", userId)
                put("emoji", emoji)
            },
        )

    // ── users / identity ────────────────────────────────────────
    /** GET /api/users → { users: [...] } (identity picker + contacts). */
    suspend fun users(): PulseResult<UsersPageDto> =
        get("/api/users") { PulseJson.decodeFromString(UsersPageDto.serializer(), it) }

    /** GET /api/users/check-username?username=x → { available, suggestion } (400 on invalid). */
    suspend fun checkUsername(username: String): PulseResult<UsernameCheckDto> =
        get("/api/users/check-username?username=" + java.net.URLEncoder.encode(username, "UTF-8")) {
            PulseJson.decodeFromString(UsernameCheckDto.serializer(), it)
        }

    /** GET registry/handles.json — the static CDN availability registry (the
     *  offline-first fallback when no live gateway answers). */
    suspend fun fetchHandleRegistry(): PulseResult<HandleRegistryDto> =
        get("/registry/handles.json") {
            PulseJson.decodeFromString(HandleRegistryDto.serializer(), it)
        }

    /** GET /api/users?name=X → { user } — case-insensitive lookup (404 when free). */
    suspend fun lookupUserByName(name: String): PulseResult<UserDto> =
        get("/api/users?name=" + java.net.URLEncoder.encode(name, "UTF-8")) {
            PulseJson.decodeFromString(UserDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("user").toString())
        }

    /** POST /api/users { name, color, username? } → 201 { user } | 409 username_taken | 409 name clash. */
    suspend fun createUser(name: String, color: String?, username: String? = null): PulseResult<UserDto> =
        post(
            "/api/users",
            buildJsonObject {
                put("name", name)
                if (color != null) put("color", color)
                if (!username.isNullOrBlank()) put("username", username)
            },
        ) { PulseJson.decodeFromString(UserDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("user").toString()) }

    /** Small action POSTs (read/mute/pin/archive/block/report) share one runner. */
    suspend fun postAction(path: String, body: JsonObject? = null): PulseResult<Unit> =
        post(path, body)

    companion object {
        fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
    }
}

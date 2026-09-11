package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import app.pulse.protocol.CallLogCreatedDto
import app.pulse.protocol.CallLogsPageDto
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.ConversationsPageDto
import app.pulse.protocol.FoldersPageDto
import app.pulse.protocol.HandleRegistryDto
import app.pulse.protocol.MentionsPageDto
import app.pulse.protocol.MessagesPageDto
import app.pulse.protocol.OkDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PinnedPageDto
import app.pulse.protocol.SavedPageDto
import app.pulse.protocol.SavedToggleDto
import app.pulse.protocol.SearchPageDto
import app.pulse.protocol.StoriesPageDto
import app.pulse.protocol.ThreadPageDto
import app.pulse.protocol.TopicDto
import app.pulse.protocol.TopicsPageDto
import app.pulse.protocol.TranscribeResultDto
import app.pulse.protocol.UploadResultDto
import app.pulse.protocol.UserDto
import app.pulse.protocol.UsernameCheckDto
import app.pulse.protocol.UsersPageDto
import app.pulse.protocol.unwrapOrRoot
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
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
     * PATCH verb — the conversation flag routes (pin/mute/archive/mark-unread) are
     * PATCH on the wire; POST was the N3-era wrong verb (spec §14 transport fixes).
     */
    private suspend fun <T> patch(path: String, body: JsonObject?, parse: ((String) -> T)? = null): PulseResult<T> =
        try {
            val res = http.patch(PulseEndpoints.http(path)) {
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

    suspend fun messages(
        conversationId: String,
        limit: Int = 200,
        before: String? = null,
        topicId: String? = null,
    ): PulseResult<MessagesPageDto> {
        val cursor = before?.let { "&before=$it" } ?: ""
        val topic = topicId?.let { "&topicId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return get("/api/conversations/$conversationId/messages?limit=$limit$cursor$topic") {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }
    }

    /**
     * POST /api/conversations/{id}/messages — the full Wave-1 wire body
     * (spec §1.1): thread replies ride `parentId`, inline quotes `replyToId`,
     * media rides imagePath/audioPath/filePath — only non-null keys are sent.
     * `kind` whitelist: text|image|audio|sticker|location|file.
     */
    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        content: String,
        replyToId: String? = null,
        parentId: String? = null,
        imagePath: String? = null,
        audioPath: String? = null,
        durationMs: Long? = null,
        filePath: String? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        kind: String? = null,
        viewOnce: Boolean? = null,
        topicId: String? = null,
    ): PulseResult<ChatMessageDto> =
        post(
            "/api/conversations/$conversationId/messages",
            buildJsonObject {
                put("senderId", senderId)
                put("content", content)
                put("kind", kind ?: "text")
                if (replyToId != null) put("replyToId", replyToId)
                if (parentId != null) put("parentId", parentId)
                if (imagePath != null) put("imagePath", imagePath)
                if (audioPath != null) put("audioPath", audioPath)
                if (durationMs != null) put("durationMs", durationMs)
                if (filePath != null) put("filePath", filePath)
                if (fileName != null) put("fileName", fileName)
                if (fileSize != null) put("fileSize", fileSize)
                if (viewOnce == true) put("viewOnce", true)
                if (topicId != null) put("topicId", topicId)
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

    /** Small action POSTs (read/react/block/report) share one runner. */
    suspend fun postAction(path: String, body: JsonObject? = null): PulseResult<Unit> =
        post(path, body)

    /** Small action PATCHes (pin/mute/archive/mark-unread/draft) share one runner. */
    suspend fun patchAction(path: String, body: JsonObject? = null): PulseResult<Unit> =
        patch(path, body)

    // ── Wave 1 messaging surface (spec §1.1) ───────────────────

    /** `{message: ChatMessage}` unwrap shared by the edit/pin actions. */
    private fun messageOf(json: String): ChatMessageDto =
        PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("message").toString(),
        )

    /** PATCH /api/messages/{id} { userId, content } → { message } (sender-only edit). */
    suspend fun editMessage(messageId: String, userId: String, content: String): PulseResult<ChatMessageDto> =
        patch("/api/messages/$messageId", jsonOf("userId" to userId, "content" to content)) { messageOf(it) }

    /** POST /api/messages/{id}/pin { userId } → { message } (toggle; relays message:pinned). */
    suspend fun toggleMessagePin(messageId: String, userId: String): PulseResult<ChatMessageDto> =
        post("/api/messages/$messageId/pin", jsonOf("userId" to userId)) { messageOf(it) }

    /** POST /api/messages/{id}/save { userId } → { saved } (per-user toggle). */
    suspend fun toggleMessageSave(messageId: String, userId: String): PulseResult<SavedToggleDto> =
        post("/api/messages/$messageId/save", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(SavedToggleDto.serializer(), it)
        }

    /** GET /api/conversations/{id}/pinned?userId= → { messages } (pinnedAt asc). */
    suspend fun pinnedMessages(conversationId: String, userId: String): PulseResult<PinnedPageDto> =
        get("/api/conversations/$conversationId/pinned?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(PinnedPageDto.serializer(), it)
        }

    /** GET /api/messages/{id}/thread?userId= → { parent, replies } (replies asc). */
    suspend fun thread(rootId: String, userId: String): PulseResult<ThreadPageDto> =
        get("/api/messages/$rootId/thread?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(ThreadPageDto.serializer(), it)
        }

    /** GET /api/conversations/{id}/messages?limit=100&q= — in-conversation search
     *  (case-insensitive substring on content + fileName, asc, hasMore:false). */
    suspend fun searchInConversation(conversationId: String, query: String, limit: Int = 100): PulseResult<MessagesPageDto> =
        get(
            "/api/conversations/$conversationId/messages?limit=$limit&q=" +
                java.net.URLEncoder.encode(query, "UTF-8"),
        ) {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }

    /** POST /api/uploads { dataUrl } → 201 { filePath, imagePath } — base64 data URL, NOT multipart. */
    suspend fun uploadMedia(dataUrl: String): PulseResult<UploadResultDto> =
        post("/api/uploads", jsonOf("dataUrl" to dataUrl)) {
            PulseJson.decodeFromString(UploadResultDto.serializer(), it)
        }

    /**
     * GET /api/uploads/{file} → raw bytes — the media-download leg of Wave 1
     * (file bubbles save to cacheDir/downloads and open through FileProvider).
     */
    suspend fun downloadMedia(filePath: String): PulseResult<ByteArray> = try {
        val res = http.get(PulseEndpoints.http("/api/uploads/$filePath"))
        if (res.status.isSuccess()) {
            PulseResult.Success(res.readBytes())
        } else {
            failureOf(res.status.value, res.bodyAsText())
        }
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    /** PATCH /api/conversations/{id}/draft { userId, draft } → { ok, draft } ('' clears). */
    suspend fun setDraft(conversationId: String, userId: String, draft: String): PulseResult<Unit> =
        patch("/api/conversations/$conversationId/draft", jsonOf("userId" to userId, "draft" to draft))

    /** GET /api/conversations/{id}?userId= → { conversation } (detail incl. members with lastReadAt). */
    suspend fun conversationDetail(conversationId: String, userId: String): PulseResult<ConversationSummaryDto> =
        get("/api/conversations/$conversationId?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            )
        }

    /** POST /api/conversations/self {userId} — the viewer's Note to Self chat. */
    suspend fun createSelfChat(userId: String): PulseResult<ConversationSummaryDto> =
        post(
            "/api/conversations/self",
            buildJsonObject { put("userId", userId) },
        ) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            )
        }

    /** GET /api/search?userId=&q= — server message search (≥2 chars upstream). */
    suspend fun search(userId: String, query: String): PulseResult<SearchPageDto> =
        get(
            "/api/search?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                "&q=" + java.net.URLEncoder.encode(query, "UTF-8"),
        ) {
            PulseJson.decodeFromString(SearchPageDto.serializer(), it)
        }

    /** GET /api/stories?requesterId= — 24h status rail (tolerant subset). */
    suspend fun stories(requesterId: String): PulseResult<StoriesPageDto> =
        get("/api/stories?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8")) {
            PulseJson.decodeFromString(StoriesPageDto.serializer(), it)
        }

    /** GET /api/folders?userId= — Signal-style folder rail. */
    suspend fun folders(userId: String): PulseResult<FoldersPageDto> =
        get("/api/folders?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(FoldersPageDto.serializer(), it)
        }

    /** GET /api/mentions?userId=&limit=50 — @mention feed (count consumer). */
    suspend fun mentions(userId: String): PulseResult<MentionsPageDto> =
        get(
            "/api/mentions?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + "&limit=50",
        ) {
            PulseJson.decodeFromString(MentionsPageDto.serializer(), it)
        }

    /** DELETE /api/messages/{id} {requesterId} — sender-gated soft delete (clear chat). */
    suspend fun deleteMessage(messageId: String, requesterId: String): PulseResult<Unit> = try {
        val res = http.delete(PulseEndpoints.http("/api/messages/$messageId")) {
            contentType(ContentType.Application.Json)
            setBody(jsonOf("requesterId" to requesterId).toString())
        }
        val text = res.bodyAsText()
        if (res.status.isSuccess()) PulseResult.Success(Unit) else failureOf(res.status.value, text)
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    // ── Wave 2 messaging depth (spec §0 — every route exists on the wire) ──

    /** POST /api/messages/{id}/transcribe {requesterId} → { transcript, transcribedAt, cached }. */
    suspend fun transcribe(messageId: String, requesterId: String): PulseResult<TranscribeResultDto> =
        post("/api/messages/$messageId/transcribe", jsonOf("requesterId" to requesterId)) {
            PulseJson.decodeFromString(TranscribeResultDto.serializer(), it)
        }

    /** POST /api/messages/{id}/viewed {userId} → { message } (idempotent burn stamp). */
    suspend fun markViewed(messageId: String, userId: String): PulseResult<ChatMessageDto> =
        post("/api/messages/$messageId/viewed", jsonOf("userId" to userId)) { messageOf(it) }

    /** POST /api/conversations/{id}/poll {senderId, question, options[]} → 201 { message } (poll attached). */
    suspend fun createPoll(
        conversationId: String,
        senderId: String,
        question: String,
        options: List<String>,
    ): PulseResult<ChatMessageDto> =
        post(
            "/api/conversations/$conversationId/poll",
            buildJsonObject {
                put("senderId", senderId)
                put("question", question)
                put("options", kotlinx.serialization.json.JsonArray(options.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        ) { messageOf(it) }

    /** POST /api/polls/{id}/vote {userId, optionId} → { message } (fresh tally). */
    suspend fun votePoll(pollId: String, userId: String, optionId: String): PulseResult<ChatMessageDto> =
        post(
            "/api/polls/$pollId/vote",
            jsonOf("userId" to userId, "optionId" to optionId),
        ) { messageOf(it) }

    /** POST /api/polls/{id}/close {userId} → { message } (creator-only; freezes the tally). */
    suspend fun closePoll(pollId: String, userId: String): PulseResult<ChatMessageDto> =
        post("/api/polls/$pollId/close", jsonOf("userId" to userId)) { messageOf(it) }

    /**
     * POST /api/messages/{id}/unfurl {userId} → { message: ChatMessage | null }.
     * null is a VALID result (nothing unfurled) — decoded tolerantly. Own
     * runner (the shared `post` helper cannot carry a null parse result).
     */
    suspend fun unfurl(messageId: String, userId: String): PulseResult<ChatMessageDto?> = try {
        val res = http.post(PulseEndpoints.http("/api/messages/$messageId/unfurl")) {
            contentType(ContentType.Application.Json)
            setBody(jsonOf("userId" to userId).toString())
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) {
            failureOf(res.status.value, text)
        } else {
            val root = PulseJson.parseToJsonElement(text)
            val inner = (root as? JsonObject)?.get("message") as? JsonObject
            PulseResult.Success(inner?.let { PulseJson.decodeFromJsonElement(ChatMessageDto.serializer(), it) })
        }
    } catch (e: kotlinx.serialization.SerializationException) {
        PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    /** GET /api/users/{id}/saved → { items: [{savedAt, conversation, message}] } (newest first, cap 100). */
    suspend fun savedList(userId: String): PulseResult<SavedPageDto> =
        get("/api/users/$userId/saved") {
            PulseJson.decodeFromString(SavedPageDto.serializer(), it)
        }

    /** GET /api/conversations/{id}/topics?userId= → { topics: [...] } (lastMessageAt desc, General NOT a row). */
    suspend fun topics(conversationId: String, userId: String): PulseResult<TopicsPageDto> =
        get(
            "/api/conversations/$conversationId/topics?userId=" +
                java.net.URLEncoder.encode(userId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(TopicsPageDto.serializer(), it)
        }

    /**
     * POST /api/conversations/{id}/topics {userId, name, emoji?} — 200 existing
     * (case-insensitive dedupe) / 201 new; both carry { topic }.
     */
    suspend fun createTopic(conversationId: String, userId: String, name: String, emoji: String?): PulseResult<TopicDto> =
        post(
            "/api/conversations/$conversationId/topics",
            buildJsonObject {
                put("userId", userId)
                put("name", name)
                if (!emoji.isNullOrBlank()) put("emoji", emoji)
            },
        ) {
            PulseJson.decodeFromString(
                TopicDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("topic").toString(),
            )
        }

    /** DELETE /api/topics/{id}?userId= → { ok: true } (creator/admin only — query-param identity). */
    suspend fun deleteTopic(topicId: String, userId: String): PulseResult<OkDto> = try {
        val res = http.delete(
            PulseEndpoints.http("/api/topics/$topicId?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")),
        )
        val text = res.bodyAsText()
        if (res.status.isSuccess()) {
            PulseResult.Success(runCatching { PulseJson.decodeFromString(OkDto.serializer(), text) }.getOrDefault(OkDto(ok = true)))
        } else {
            failureOf(res.status.value, text)
        }
    } catch (e: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
    }

    // ── Wave 3 native calls — REST /api/calls (call-types.ts parity) ──

    /** GET /api/calls?userId= → { items } — newest-first history, server cap 50. */
    suspend fun callLogs(userId: String): PulseResult<CallLogsPageDto> =
        get("/api/calls?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(CallLogsPageDto.serializer(), it)
        }

    /**
     * POST /api/calls { userId, conversationId, peerId, kind, status, durationSec? }
     * → 201 { item }. SINGLE-WRITER RULE: the viewer is always the CALLER
     * (callerId = userId, calleeId = peerId) — the callee never writes.
     */
    suspend fun createCallLog(
        userId: String,
        conversationId: String,
        peerId: String,
        kind: String,
        status: String,
        durationSec: Long,
    ): PulseResult<CallLogCreatedDto> =
        post(
            "/api/calls",
            buildJsonObject {
                put("userId", userId)
                put("conversationId", conversationId)
                put("peerId", peerId)
                put("kind", kind)
                put("status", status)
                put("durationSec", durationSec)
            },
        ) { PulseJson.decodeFromString(CallLogCreatedDto.serializer(), it) }

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

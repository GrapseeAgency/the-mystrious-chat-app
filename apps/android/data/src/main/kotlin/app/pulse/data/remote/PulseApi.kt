package app.pulse.data.remote

import app.pulse.core.PulseEndpoints
import app.pulse.core.result.PulseResult
import app.pulse.protocol.BlockedPageDto
import app.pulse.protocol.BlockStateDto
import app.pulse.protocol.CallLogCreatedDto
import app.pulse.protocol.CallLogsPageDto
import app.pulse.protocol.ChannelCreatedDto
import app.pulse.protocol.ChannelsPageDto
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.AppCommunityDto
import app.pulse.protocol.AppInstallResultDto
import app.pulse.protocol.AppInstallStateDto
import app.pulse.protocol.CheckinResultDto
import app.pulse.protocol.CheckinWalletResultDto
import app.pulse.protocol.EventsPageDto
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.GameMatchCreateResultDto
import app.pulse.protocol.GamesPageDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.GroupMutationAckDto
import app.pulse.protocol.HubLogsPageDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.HubTasksPageDto
import app.pulse.protocol.KanbanCardDto
import app.pulse.protocol.KanbanPageDto
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.MarketBuyResultDto
import app.pulse.protocol.MarketListingDto
import app.pulse.protocol.MarketPageDto
import app.pulse.protocol.ReminderItemDto
import app.pulse.protocol.ReminderResolveDto
import app.pulse.protocol.RemindersPageDto
import app.pulse.protocol.PhrasesPageDto
import app.pulse.protocol.PhraseEnvelopeDto
import app.pulse.protocol.RedPacketCreateResultDto
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.RedPacketGrabResultDto
import app.pulse.protocol.RedPacketStubDto
import app.pulse.protocol.RsvpResultDto
import app.pulse.protocol.SwapPageDto
import app.pulse.protocol.SwapResultDto
import app.pulse.protocol.TournamentCreateResultDto
import app.pulse.protocol.TournamentJoinResultDto
import app.pulse.protocol.TournamentSummaryDto
import app.pulse.protocol.TournamentsPageDto
import app.pulse.protocol.TransferResultDto
import app.pulse.protocol.WalletPageDto
import app.pulse.protocol.WhiteboardClearResultDto
import app.pulse.protocol.WhiteboardPageDto
import app.pulse.protocol.WhiteboardPostResultDto
import app.pulse.protocol.WhiteboardStrokePostDto
import app.pulse.protocol.WhiteboardUndoResultDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.ConversationsPageDto
import app.pulse.protocol.FolderDto
import app.pulse.protocol.FoldersPageDto
import app.pulse.protocol.FullUserDto
import app.pulse.protocol.HandleRegistryDto
import app.pulse.protocol.InviteEnvelopeDto
import app.pulse.protocol.InviteCodeDto
import app.pulse.protocol.InviteJoinResultDto
import app.pulse.protocol.InvitePreviewDto
import app.pulse.protocol.MentionsPageDto
import app.pulse.protocol.MembersAddedDto
import app.pulse.protocol.MessagesPageDto
import app.pulse.protocol.OkDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PinnedPageDto
import app.pulse.protocol.ReportAckDto
import app.pulse.protocol.ReportReasonsPageDto
import app.pulse.protocol.SavedPageDto
import app.pulse.protocol.SavedToggleDto
import app.pulse.protocol.SafetyStateDto
import app.pulse.protocol.ScheduledItemDto
import app.pulse.protocol.ScheduledItemEnvelopeDto
import app.pulse.protocol.ScheduledPageDto
import app.pulse.protocol.SearchPageDto
import app.pulse.protocol.SlowModeAckDto
import app.pulse.protocol.StatsEnvelopeDto
import app.pulse.protocol.StoriesPageDto
import app.pulse.protocol.StoryCreatedDto
import app.pulse.protocol.StoryViewAckDto
import app.pulse.protocol.StoryViewersDto
import app.pulse.protocol.SubscribeAckDto
import app.pulse.protocol.ThreadPageDto
import app.pulse.protocol.TopicDto
import app.pulse.protocol.TopicsPageDto
import app.pulse.protocol.TranscribeResultDto
import app.pulse.protocol.UnsubscribeAckDto
import app.pulse.protocol.UploadResultDto
import app.pulse.protocol.UserDto
import app.pulse.protocol.UserAuthEnvelopeDto
import app.pulse.protocol.SettingsEnvelopeDto
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.toPatchJson
import app.pulse.protocol.decodeSettingsEnvelope
import app.pulse.protocol.UserEnvelopeDto
import app.pulse.protocol.UsernameCheckDto
import app.pulse.protocol.UsersPageDto
import app.pulse.protocol.UserStatsDto
import app.pulse.protocol.VerifyAckDto
import app.pulse.protocol.VoiceTranscriptResultDto
import app.pulse.protocol.unwrapOrRoot
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ktor REST client — REAL wiring to the Next.js API (gateway :81 in dev).
 * The API identifies the caller with `userId` params (same as the web client);
 * every method maps failures onto PulseResult kinds identical to iOS.
 *
 * Wave 8 — session tokens: every request carries
 * `Authorization: Bearer <token>` when the [bearerToken] provider hands back
 * a credential (server-side optional-verify: no header = accepted for the
 * web-migration window, present-but-invalid = 401). A 401 response invokes
 * [onAuthInvalid] so the store layer clears the rotated credential and the
 * app surfaces the honest re-login path.
 */
private const val OFFLINE_COPY =
    "No gateway configured — set your server in Profile → Connection."

class PulseApi(
    private val http: HttpClient,
    /** Current session credential — null = ride header-less (web migration window). */
    private val bearerToken: () -> String? = { null },
    /** Fired on any 401 so the token store can clear + surface re-login. */
    private val onAuthInvalid: (String?) -> Unit = {},
) {

    /**
     * Honest offline-first gate. With NO configured gateway, `PulseEndpoints.http`
     * returns a bare relative path — and Ktor's URLBuilder resolves that against
     * its implicit `http://localhost` default (ktor-http URLBuilder host default),
     * so OkHttp fires a cleartext request at the PHONE ITSELF and Android's
     * network security policy blocks it: the "CLEARTEXT communication to
     * localhost not permitted" field report on the Home inbox. The request is
     * never fired now — users get actionable copy instead of a policy dump.
     * The security policy is untouched (cleartext stays blocked); the accidental
     * request is what's removed.
     */
    private val offlineFailure: PulseResult.Failure =
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, OFFLINE_COPY)

    private suspend fun <T> get(path: String, parse: (String) -> T): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.get(PulseEndpoints.http(path)) { authHeader() }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(parse(text))
            else failureOf(res.status.value, text)
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    private suspend fun <T> post(
        path: String,
        body: JsonObject?,
        /** Per-request requestTimeout override (Ktor 2 `timeout {}` extension) — null = plugin default. */
        timeoutMillis: Long? = null,
        parse: ((String) -> T)? = null,
    ): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.post(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body.toString())
                if (timeoutMillis != null) timeout { requestTimeoutMillis = timeoutMillis }
                authHeader()
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
    }

    /**
     * PATCH verb — the conversation flag routes (pin/mute/archive/mark-unread) are
     * PATCH on the wire; POST was the N3-era wrong verb (spec §14 transport fixes).
     */
    private suspend fun <T> patch(path: String, body: JsonObject?, parse: ((String) -> T)? = null): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.patch(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body.toString())
                authHeader()
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
    }

    /**
     * Wave 8 — attach `Authorization: Bearer <token>` when a credential is
     * present. The server's optional-verify proxy accepts header-less calls
     * (web migration window) but rejects PRESENT-but-invalid tokens with 401,
     * so we attach only when the store actually holds a token.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.authHeader() {
        bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
        var retryAfter: Int? = null
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
                // R44 slow mode — the 429 body carries { error, retryAfter } and a
                // Retry-After header (same value). The body wins (web parity).
                (json["retryAfter"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    retryAfter = runCatching { it.content.toInt() }.getOrNull()
                }
            }
        } catch (_: Exception) {
            // non-JSON body — keep the raw text as the message
        }
        // Wave 8 — a 401 means the presented token is invalid/rotated. The
        // typed Kind.AUTH failure rides back to the caller AND the hook lets
        // the store layer clear the credential + raise the re-login flow.
        if (status == 401) onAuthInvalid(message)
        val base = PulseResult.fromHttp(status, message)
        return base.copy(code = code, suggestion = suggestion, status = status, retryAfter = retryAfter)
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
     *
     * R5-B — the response is the server envelope `{ message, streak?, xpAwarded }`
     * (messages/route.ts:704-708); [app.pulse.protocol.decodeMessageSend]
     * unwraps it (bare-row bodies from alternate gateways still decode).
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
        /** R24-b incognito — group-only server-side; DMs silently ignore it. */
        anon: Boolean? = null,
        /** Rich payload blob — sticker {emoji,pack} · location {lat,lng,label} · effects {effect}. */
        payload: JsonObject? = null,
    ): PulseResult<app.pulse.protocol.MessageSendEnvelopeDto> =
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
                if (anon == true) put("anon", true)
                if (payload != null) put("payload", payload)
            },
        ) { app.pulse.protocol.decodeMessageSend(it) }

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

    /**
     * POST /api/users { name, color, username? } → 201 { user, token }
     * (Wave 8: the response now also carries the session token) |
     * 409 username_taken | 409 name clash.
     */
    suspend fun createUser(name: String, color: String?, username: String? = null): PulseResult<UserAuthEnvelopeDto> =
        post(
            "/api/users",
            buildJsonObject {
                put("name", name)
                if (color != null) put("color", color)
                if (!username.isNullOrBlank()) put("username", username)
            },
        ) { decodeUserAuth(it) }

    /**
     * POST /api/users/login { name } → 200 { user, token } (ROTATES the
     * stored hash — the old token goes invalid) | 400 "Name is required." |
     * 404 "No identity with that name on this Pulse." — the honest copy
     * surfaces verbatim through the Failure.
     */
    suspend fun login(name: String): PulseResult<UserAuthEnvelopeDto> =
        post("/api/users/login", jsonOf("name" to name)) { decodeUserAuth(it) }

    // ── settings (Wave 8 — src/app/api/settings contract) ───────

    /** GET /api/settings?userId= → { preferences } (defaults merged server-side). */
    suspend fun settings(userId: String): PulseResult<SettingsEnvelopeDto> =
        get("/api/settings?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            it.decodeSettingsEnvelope()
        }

    /** PATCH /api/settings { userId, preferences: Partial } → { preferences } (shallow-merged + clamped server-side). */
    suspend fun updateSettings(userId: String, patch: WirePulsePrefs): PulseResult<SettingsEnvelopeDto> =
        patch("/api/settings", patch.toPatchJson(userId)) {
            it.decodeSettingsEnvelope()
        }

    /**
     * Shared {user, token} decode. `user` unwraps from a nested envelope when
     * present, else the root (pre-Wave-8 servers returned `{user}` without
     * the token); `token` is the top-level 64-hex credential.
     */
    private fun decodeUserAuth(body: String): UserAuthEnvelopeDto {
        val root = PulseJson.parseToJsonElement(body)
        val user = PulseJson.decodeFromJsonElement(UserDto.serializer(), root.unwrapOrRoot("user"))
        val token = (root as? JsonObject)?.get("token") as? kotlinx.serialization.json.JsonPrimitive
        return UserAuthEnvelopeDto(user = user, token = token?.takeIf { it.isString }?.content)
    }

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
    suspend fun downloadMedia(filePath: String): PulseResult<ByteArray> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.get(PulseEndpoints.http("/api/uploads/$filePath")) { authHeader() }
            if (res.status.isSuccess()) {
                PulseResult.Success(res.readBytes())
            } else {
                failureOf(res.status.value, res.bodyAsText())
            }
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
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

    // ── Wave 4 stories — full native status surface (REST only, no sockets) ──

    /**
     * POST /api/stories { requesterId, caption?, background?, imagePath? } → 201 { story }.
     * Text mode sends caption + background (palette key), photo mode sends
     * imagePath (+ optional caption) — background is NEVER sent for photos
     * (the server forces "emerald" there and 400s on the combo).
     */
    suspend fun postStory(
        requesterId: String,
        caption: String? = null,
        background: String? = null,
        imagePath: String? = null,
    ): PulseResult<StoryCreatedDto> =
        post(
            "/api/stories",
            buildJsonObject {
                put("requesterId", requesterId)
                if (!caption.isNullOrBlank()) put("caption", caption)
                if (background != null && imagePath == null) put("background", background)
                if (imagePath != null) put("imagePath", imagePath)
            },
        ) {
            PulseJson.decodeFromString(StoryCreatedDto.serializer(), it)
        }

    /** POST /api/stories/{id}/view { requesterId } → { viewCount, owner? } (idempotent; owner short-circuits). */
    suspend fun markStoryViewed(storyId: String, requesterId: String): PulseResult<StoryViewAckDto> =
        post(
            "/api/stories/$storyId/view",
            jsonOf("requesterId" to requesterId),
        ) {
            PulseJson.decodeFromString(StoryViewAckDto.serializer(), it)
        }

    /** GET /api/stories/{id}/view?requesterId= — owner-only (403 otherwise), oldest first. */
    suspend fun storyViewers(storyId: String, requesterId: String): PulseResult<StoryViewersDto> =
        get(
            "/api/stories/$storyId/view?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(StoryViewersDto.serializer(), it)
        }

    /** DELETE /api/stories/{id}?requesterId= → { ok: true } (owner-only 403, unknown 404). */
    suspend fun deleteStory(storyId: String, requesterId: String): PulseResult<OkDto> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(
                PulseEndpoints.http("/api/stories/$storyId?requesterId=" +
                    java.net.URLEncoder.encode(requesterId, "UTF-8")),
            )
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                PulseResult.Success(
                    runCatching { PulseJson.decodeFromString(OkDto.serializer(), text) }.getOrDefault(OkDto(ok = true)),
                )
            } else {
                failureOf(res.status.value, text)
            }
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
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
    suspend fun deleteMessage(messageId: String, requesterId: String): PulseResult<Unit> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(PulseEndpoints.http("/api/messages/$messageId")) {
                contentType(ContentType.Application.Json)
                setBody(jsonOf("requesterId" to requesterId).toString())
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(Unit) else failureOf(res.status.value, text)
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
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

    /**
     * R1-W2F — POST /api/messages/{id}/translate {userId, lang?} → { message }.
     * The LLM result persists per language server-side (translate/route.ts);
     * the mapped fresh row carries `translations: [{lang, text}]` — a field
     * ChatMessageDto (tolerantly) ignores, so the TEXT is lifted straight from
     * the wire JSON here, same manual extraction as [unfurl]. `lang` is
     * omitted — the server defaults to "en". LLM round-trips are slow: 30 s
     * cap (iOS parity, timeoutCap: 30).
     */
    suspend fun translate(messageId: String, userId: String): PulseResult<String> =
        post(
            "/api/messages/" + java.net.URLEncoder.encode(messageId, "UTF-8") + "/translate",
            jsonOf("userId" to userId),
            timeoutMillis = TRANSLATE_TIMEOUT_MS,
        ) { json ->
            val message = (PulseJson.parseToJsonElement(json) as? JsonObject)
                ?.get("message") as? JsonObject
            val first = (message?.get("translations") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull() as? JsonObject
            (first?.get("text") as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?: throw kotlinx.serialization.SerializationException("translate: empty translation")
        }

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
    suspend fun unfurl(messageId: String, userId: String): PulseResult<ChatMessageDto?> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
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
    suspend fun deleteTopic(topicId: String, userId: String): PulseResult<OkDto> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
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

    // ── Wave 5 voice rooms — live-caption transcription ─────────

    /**
     * POST /api/voice/transcribe {conversationId, requesterId, audioBase64} →
     * { transcript } (≤280 chars). Server: 400 missing fields, 403
     * non-participant, 413 >512K b64, 422 empty, 502 service failure.
     * ASR is slow by nature — this ONE route carries a 60s per-request
     * timeout (Ktor 2 `timeout {}` request extension); every other call keeps
     * the plugin defaults untouched.
     */
    suspend fun transcribeVoice(
        conversationId: String,
        requesterId: String,
        audioBase64: String,
    ): PulseResult<VoiceTranscriptResultDto> =
        post(
            "/api/voice/transcribe",
            buildJsonObject {
                put("conversationId", conversationId)
                put("requesterId", requesterId)
                put("audioBase64", audioBase64)
            },
            timeoutMillis = TRANSCRIBE_TIMEOUT_MS,
        ) { PulseJson.decodeFromString(VoiceTranscriptResultDto.serializer(), it) }

    // ── Wave 6 — social graph & discovery (users / safety / blocks / reports / invites / channels / folders) ──

    /** PUT helper — the folder membership full-replace is the one PUT on the wire. */
    private suspend fun <T> put(path: String, body: JsonObject, parse: ((String) -> T)? = null): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.put(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                authHeader()
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
    }

    /** DELETE helper with a `userId` query param (safety unverify / unblock). */
    private suspend fun <T> deleteWithQuery(path: String, userId: String, parse: ((String) -> T)? = null): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(
                PulseEndpoints.http("$path?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")),
            ) { authHeader() }
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
    }

    /** DELETE helper with a JSON body { userId } (channel unsubscribe). */
    private suspend fun <T> deleteWithBody(path: String, userId: String, parse: ((String) -> T)? = null): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                setBody(jsonOf("userId" to userId).toString())
                authHeader()
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
    }

    /** DELETE helper with a caller-shaped JSON body (group kick/leave + scheduled cancel — { requesterId }). */
    private suspend fun <T> deleteWithJson(path: String, body: JsonObject, parse: ((String) -> T)? = null): PulseResult<T> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(PulseEndpoints.http(path)) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                authHeader()
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
    }

    /** GET /api/users/{id} → { user: AppUser } (the full profile page payload). */
    suspend fun fullUser(userId: String): PulseResult<FullUserDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(UserEnvelopeDto.serializer(), it).user ?: FullUserDto()
        }

    /** PATCH /api/users/{id} — only non-null fields ride the body ('' clears where the wire allows). */
    suspend fun patchUser(userId: String, body: JsonObject): PulseResult<FullUserDto> =
        patch("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8"), body) {
            PulseJson.decodeFromString(UserEnvelopeDto.serializer(), it).user ?: FullUserDto()
        }

    /** GET /api/users/{id}/stats → { stats } (messages/reactions/photos/voiceNotes/chats/groups). */
    suspend fun userStats(userId: String): PulseResult<UserStatsDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/stats") {
            PulseJson.decodeFromString(StatsEnvelopeDto.serializer(), it).stats ?: UserStatsDto()
        }

    /** GET /api/users/{id}/safety?userId= → 12×5 digits + this viewer's verify stamp. */
    suspend fun safetyState(peerId: String, viewerId: String): PulseResult<SafetyStateDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety?userId=" +
                java.net.URLEncoder.encode(viewerId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(SafetyStateDto.serializer(), it)
        }

    /** POST /api/users/{id}/safety { userId } → upsert verification. */
    suspend fun safetyVerify(peerId: String, viewerId: String): PulseResult<VerifyAckDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety",
            jsonOf("userId" to viewerId),
        ) { PulseJson.decodeFromString(VerifyAckDto.serializer(), it) }

    /** DELETE /api/users/{id}/safety?userId= → reset verification (idempotent). */
    suspend fun safetyUnverify(peerId: String, viewerId: String): PulseResult<VerifyAckDto> =
        deleteWithQuery(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety",
            viewerId,
        ) { PulseJson.decodeFromString(VerifyAckDto.serializer(), it) }

    /** GET /api/users/{id}/block?userId= → pair state (drives Block/Unblock label). */
    suspend fun blockState(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block?userId=" +
                java.net.URLEncoder.encode(actorId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(BlockStateDto.serializer(), it)
        }

    /** POST /api/users/{id}/block { userId } — idempotent (actor in the BODY). */
    suspend fun blockUser(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block",
            jsonOf("userId" to actorId),
        ) { PulseJson.decodeFromString(BlockStateDto.serializer(), it) }

    /** DELETE /api/users/{id}/block?userId= — idempotent unblock (POST /unblock does NOT exist). */
    suspend fun unblockUser(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        deleteWithQuery(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block",
            actorId,
        ) { PulseJson.decodeFromString(BlockStateDto.serializer(), it) }

    /** GET /api/users/{id}/blocks?userId= — self-service list (403 otherwise). */
    suspend fun blockedAccounts(ownerId: String): PulseResult<BlockedPageDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(ownerId, "UTF-8") + "/blocks?userId=" +
                java.net.URLEncoder.encode(ownerId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(BlockedPageDto.serializer(), it)
        }

    /** POST /api/users/{id}/report { userId, reason, details? } → 201/200 { reported }. */
    suspend fun reportUser(targetId: String, reporterId: String, reason: String, details: String?): PulseResult<ReportAckDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/report",
            buildJsonObject {
                put("userId", reporterId)
                put("reason", reason)
                if (!details.isNullOrBlank()) put("details", details)
            },
        ) { PulseJson.decodeFromString(ReportAckDto.serializer(), it) }

    /** GET /api/users/{id}/report?userId= — the reporter's OWN prior reasons (hint). */
    suspend fun reportReasons(targetId: String, reporterId: String): PulseResult<ReportReasonsPageDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/report?userId=" +
                java.net.URLEncoder.encode(reporterId, "UTF-8"),
        ) {
            PulseJson.decodeFromString(ReportReasonsPageDto.serializer(), it)
        }

    /** GET /api/invite/[code]?userId= → public preview (404 unknown code). */
    suspend fun invitePreview(code: String, userId: String?): PulseResult<InvitePreviewDto> =
        get(
            "/api/invite/" + java.net.URLEncoder.encode(code, "UTF-8") +
                (userId?.let { "?userId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""),
        ) {
            PulseJson.decodeFromString(InviteEnvelopeDto.serializer(), it).invite ?: InvitePreviewDto()
        }

    /** POST /api/invite/[code]/join { userId } → { conversationId, alreadyMember }. */
    suspend fun inviteJoin(code: String, userId: String): PulseResult<InviteJoinResultDto> =
        post(
            "/api/invite/" + java.net.URLEncoder.encode(code, "UTF-8") + "/join",
            jsonOf("userId" to userId),
        ) { PulseJson.decodeFromString(InviteJoinResultDto.serializer(), it) }

    /** GET /api/channels?userId=[&mine=1] — directory with viewer-aware flags. */
    suspend fun channels(userId: String, mineOnly: Boolean): PulseResult<ChannelsPageDto> =
        get(
            "/api/channels?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                if (mineOnly) "&mine=1" else "",
        ) {
            PulseJson.decodeFromString(ChannelsPageDto.serializer(), it)
        }

    /** POST /api/channels { userId, name, description?, photo? } → 201 { channel }. */
    suspend fun createChannel(userId: String, name: String, description: String?, photo: String?): PulseResult<ChannelCreatedDto> =
        post(
            "/api/channels",
            buildJsonObject {
                put("userId", userId)
                put("name", name)
                if (!description.isNullOrBlank()) put("description", description)
                if (!photo.isNullOrBlank()) put("photo", photo)
            },
        ) { PulseJson.decodeFromString(ChannelCreatedDto.serializer(), it) }

    /** POST /api/channels/[id]/subscribe { userId } → { already, memberCount }. */
    suspend fun subscribeChannel(channelId: String, userId: String): PulseResult<SubscribeAckDto> =
        post(
            "/api/channels/" + java.net.URLEncoder.encode(channelId, "UTF-8") + "/subscribe",
            jsonOf("userId" to userId),
        ) { PulseJson.decodeFromString(SubscribeAckDto.serializer(), it) }

    /** DELETE /api/channels/[id]/subscribe { userId } → last-admin leave is a 403. */
    suspend fun unsubscribeChannel(channelId: String, userId: String): PulseResult<UnsubscribeAckDto> =
        deleteWithBody(
            "/api/channels/" + java.net.URLEncoder.encode(channelId, "UTF-8") + "/subscribe",
            userId,
        ) { PulseJson.decodeFromString(UnsubscribeAckDto.serializer(), it) }

    /** POST /api/folders { userId, name, emoji? } → 201 { folder }. */
    suspend fun createFolder(userId: String, name: String, emoji: String): PulseResult<FolderDto> =
        post(
            "/api/folders",
            buildJsonObject {
                put("userId", userId)
                put("name", name)
                if (emoji.isNotBlank()) put("emoji", emoji)
            },
        ) {
            PulseJson.decodeFromString(FolderDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("folder").toString())
        }

    /** PATCH /api/folders/[id] { name?, emoji?, position? } → { folder }. */
    suspend fun patchFolder(folderId: String, name: String?, emoji: String?, position: Int?): PulseResult<FolderDto> =
        patch(
            "/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8"),
            buildJsonObject {
                if (name != null) put("name", name)
                if (emoji != null) put("emoji", emoji)
                if (position != null) put("position", position)
            },
        ) {
            PulseJson.decodeFromString(FolderDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("folder").toString())
        }

    /** DELETE /api/folders/[id] → { ok } — membership rows cascade, chats stay. */
    suspend fun deleteFolder(folderId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8"), "") {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    /** PUT /api/folders/[id]/conversations { conversationIds[] } — FULL ordered replace. */
    suspend fun setFolderConversations(folderId: String, conversationIds: List<String>): PulseResult<OkDto> =
        put(
            "/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8") + "/conversations",
            buildJsonObject {
                put(
                    "conversationIds",
                    kotlinx.serialization.json.JsonArray(conversationIds.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            },
        ) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    // ── Wave 7 — collaboration & hub (red packets / whiteboard / kanban / events / reminders / games / tournaments / leaderboard / hub economy) ──

    private fun redPacketStubOf(json: String): RedPacketStubDto =
        PulseJson.decodeFromString(
            RedPacketStubDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("packet").toString(),
        )

    private fun cardOf(json: String): KanbanCardDto =
        PulseJson.decodeFromString(
            KanbanCardDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("card").toString(),
        )

    private fun eventOf(json: String): GroupEventDto =
        PulseJson.decodeFromString(
            GroupEventDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("event").toString(),
        )

    private fun taskOf(json: String): HubTaskDto =
        PulseJson.decodeFromString(
            HubTaskDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("task").toString(),
        )

    private fun listingOf(json: String): MarketListingDto =
        PulseJson.decodeFromString(
            MarketListingDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("listing").toString(),
        )

    private fun matchOf(json: String): GameDetailDto =
        PulseJson.decodeFromString(GameDetailDto.serializer(), json)

    private fun tournamentOf(json: String): TournamentSummaryDto =
        PulseJson.decodeFromString(
            TournamentSummaryDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("tournament").toString(),
        )

    /** POST /api/redpackets { userId, conversationId, total, count, note? } → { message, packet }. */
    suspend fun createRedPacket(
        userId: String,
        conversationId: String,
        total: Long,
        count: Int,
        note: String?,
    ): PulseResult<RedPacketCreateResultDto> =
        post(
            "/api/redpackets",
            buildJsonObject {
                put("userId", userId)
                put("conversationId", conversationId)
                put("total", total)
                put("count", count)
                if (!note.isNullOrBlank()) put("note", note)
            },
        ) { PulseJson.decodeFromString(RedPacketCreateResultDto.serializer(), it) }

    /** GET /api/redpackets/{id}?userId= — lazy refund settles on first read after expiry. */
    suspend fun redPacket(packetId: String, userId: String): PulseResult<RedPacketDetailDto> =
        get("/api/redpackets/" + java.net.URLEncoder.encode(packetId, "UTF-8") + "?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(RedPacketDetailDto.serializer(), it)
        }

    /** POST /api/redpackets/{id}/grab { userId } — atomic; 409 copy verbatim from the server. */
    suspend fun grabRedPacket(packetId: String, userId: String): PulseResult<RedPacketGrabResultDto> =
        post("/api/redpackets/" + java.net.URLEncoder.encode(packetId, "UTF-8") + "/grab", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(RedPacketGrabResultDto.serializer(), it)
        }

    /** GET /api/conversations/{id}/whiteboard?requesterId=&since= (since = epoch ms | null = full). */
    suspend fun whiteboard(conversationId: String, requesterId: String, since: Long?): PulseResult<WhiteboardPageDto> {
        val sinceQ = since?.let { "&since=$it" } ?: ""
        return get("/api/conversations/$conversationId/whiteboard?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8") + sinceQ) {
            PulseJson.decodeFromString(WhiteboardPageDto.serializer(), it)
        }
    }

    /** POST /api/conversations/{id}/whiteboard — strokes: 1..40 per call, 2..500 points each, 0..1 coords. */
    suspend fun postWhiteboardStrokes(
        conversationId: String,
        requesterId: String,
        strokes: List<WhiteboardStrokePostDto>,
    ): PulseResult<WhiteboardPostResultDto> =
        post(
            "/api/conversations/$conversationId/whiteboard",
            buildJsonObject {
                put("requesterId", requesterId)
                put("strokes", kotlinx.serialization.json.JsonArray(strokes.map { s ->
                    buildJsonObject {
                        put("color", s.color)
                        put("width", s.width)
                        put("points", kotlinx.serialization.json.JsonArray(s.points.map { pt ->
                            kotlinx.serialization.json.JsonArray(pt.map { kotlinx.serialization.json.JsonPrimitive(it) })
                        }))
                    }
                }))
            },
        ) { PulseJson.decodeFromString(WhiteboardPostResultDto.serializer(), it) }

    /** POST /api/conversations/{id}/whiteboard {action:'undo'} — deletes only the caller's latest stroke. */
    suspend fun undoWhiteboardStroke(conversationId: String, requesterId: String): PulseResult<WhiteboardUndoResultDto> =
        post("/api/conversations/$conversationId/whiteboard", jsonOf("action" to "undo", "requesterId" to requesterId)) {
            PulseJson.decodeFromString(WhiteboardUndoResultDto.serializer(), it)
        }

    /** DELETE /api/conversations/{id}/whiteboard?requesterId= — clear all + resetAt watermark. */
    suspend fun clearWhiteboard(conversationId: String, requesterId: String): PulseResult<WhiteboardClearResultDto> {
        if (!PulseEndpoints.isConfigured) return offlineFailure
        return try {
            val res = http.delete(
                PulseEndpoints.http("/api/conversations/$conversationId/whiteboard?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8")),
            )
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(PulseJson.decodeFromString(WhiteboardClearResultDto.serializer(), text))
            else failureOf(res.status.value, text)
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** GET /api/conversations/{id}/kanban?userId= → { cards } ordered column → position → createdAt. */
    suspend fun kanbanBoard(conversationId: String, userId: String): PulseResult<KanbanPageDto> =
        get("/api/conversations/$conversationId/kanban?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(KanbanPageDto.serializer(), it)
        }

    /** POST /api/conversations/{id}/kanban { userId, title?, column?, assigneeId?, messageId? }. */
    suspend fun createKanbanCard(
        conversationId: String,
        userId: String,
        title: String?,
        column: String?,
        assigneeId: String?,
        messageId: String?,
    ): PulseResult<KanbanCardDto> =
        post(
            "/api/conversations/$conversationId/kanban",
            buildJsonObject {
                put("userId", userId)
                if (!title.isNullOrBlank()) put("title", title)
                if (!column.isNullOrBlank()) put("column", column)
                if (!assigneeId.isNullOrBlank()) put("assigneeId", assigneeId)
                if (!messageId.isNullOrBlank()) put("messageId", messageId)
            },
        ) { cardOf(it) }

    /** PATCH /api/kanban/{cardId} { userId, title?, column?, assigneeId?, position? }. */
    suspend fun updateKanbanCard(
        cardId: String,
        userId: String,
        title: String? = null,
        column: String? = null,
        assigneeId: String? = null,
        clearAssignee: Boolean = false,
        position: Long? = null,
    ): PulseResult<KanbanCardDto> =
        patch(
            "/api/kanban/" + java.net.URLEncoder.encode(cardId, "UTF-8"),
            buildJsonObject {
                put("userId", userId)
                if (title != null) put("title", title)
                if (column != null) put("column", column)
                if (clearAssignee) put("assigneeId", null as String?)
                else if (!assigneeId.isNullOrBlank()) put("assigneeId", assigneeId)
                if (position != null) put("position", position)
            },
        ) { cardOf(it) }

    /** DELETE /api/kanban/{cardId}?userId= — creator OR group admin (403 copy verbatim). */
    suspend fun deleteKanbanCard(cardId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/kanban/" + java.net.URLEncoder.encode(cardId, "UTF-8"), userId) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    /** GET /api/conversations/{id}/events?userId= — upcoming asc then past desc, merged ≤50. */
    suspend fun events(conversationId: String, userId: String): PulseResult<EventsPageDto> =
        get("/api/conversations/$conversationId/events?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(EventsPageDto.serializer(), it)
        }

    /** POST /api/conversations/{id}/events { userId, title, startsAt, description?, location? }. */
    suspend fun createEvent(
        conversationId: String,
        userId: String,
        title: String,
        startsAtIso: String,
        description: String?,
        location: String?,
    ): PulseResult<GroupEventDto> =
        post(
            "/api/conversations/$conversationId/events",
            buildJsonObject {
                put("userId", userId)
                put("title", title)
                put("startsAt", startsAtIso)
                if (!description.isNullOrBlank()) put("description", description)
                if (!location.isNullOrBlank()) put("location", location)
            },
        ) { eventOf(it) }

    /** DELETE /api/events/{id}?userId= — creator OR group admin. */
    suspend fun deleteEvent(eventId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8"), userId) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    /** POST /api/events/{id}/rsvp { userId, status: going|maybe|no } → { rsvp, counts }. */
    suspend fun rsvpEvent(eventId: String, userId: String, status: String): PulseResult<RsvpResultDto> =
        post("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8") + "/rsvp", jsonOf("userId" to userId, "status" to status)) {
            PulseJson.decodeFromString(RsvpResultDto.serializer(), it)
        }

    /** POST /api/events/{id}/checkin { userId } — window +15 XP; 409 outside window is a real failure. */
    suspend fun checkinEvent(eventId: String, userId: String): PulseResult<CheckinResultDto> =
        post("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8") + "/checkin", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(CheckinResultDto.serializer(), it)
        }

    /** GET /api/reminders?userId=[&due=1] — due = remindAt ≤ now && firedAt null. */
    suspend fun reminders(userId: String, dueOnly: Boolean): PulseResult<RemindersPageDto> =
        get("/api/reminders?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + if (dueOnly) "&due=1" else "") {
            PulseJson.decodeFromString(RemindersPageDto.serializer(), it)
        }

    /** POST /api/reminders { userId, conversationId, messageId?, note?, remindAt } → { item }. */
    suspend fun createReminder(
        userId: String,
        conversationId: String,
        messageId: String?,
        note: String?,
        remindAtIso: String,
    ): PulseResult<ReminderItemDto> =
        post(
            "/api/reminders",
            buildJsonObject {
                put("userId", userId)
                put("conversationId", conversationId)
                if (!messageId.isNullOrBlank()) put("messageId", messageId)
                if (!note.isNullOrBlank()) put("note", note)
                put("remindAt", remindAtIso)
            },
        ) {
            PulseJson.decodeFromString(
                ReminderItemDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("item").toString(),
            )
        }

    /** PATCH /api/reminders/{id} { userId } — owner-only resolve; the due loop calls this after the nudge. */
    suspend fun resolveReminder(reminderId: String, userId: String): PulseResult<ReminderResolveDto> =
        patch("/api/reminders/" + java.net.URLEncoder.encode(reminderId, "UTF-8"), jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(ReminderResolveDto.serializer(), it)
        }

    /** DELETE /api/reminders/{id} { userId } — owner-only cancel. */
    suspend fun deleteReminder(reminderId: String, userId: String): PulseResult<OkDto> =
        deleteWithBody("/api/reminders/" + java.net.URLEncoder.encode(reminderId, "UTF-8"), userId) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    // ── quick phrases (F-MS-29) ────────────────────────────────────

    /** GET /api/users/{id}/phrases → { phrases: [{id,text,position}] } (position asc). */
    suspend fun phrases(userId: String): PulseResult<PhrasesPageDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases") {
            PulseJson.decodeFromString(PhrasesPageDto.serializer(), it)
        }

    /** POST /api/users/{id}/phrases { text } → 201 { phrase } (server caps 12 rows × 120 chars). */
    suspend fun createPhrase(userId: String, text: String): PulseResult<PhraseEnvelopeDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases",
            jsonOf("text" to text),
        ) {
            PulseJson.decodeFromString(
                PhraseEnvelopeDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("phrase").toString(),
            )
        }

    /** DELETE /api/users/{id}/phrases?phraseId=X → { ok } (owner-guarded server-side). */
    suspend fun deletePhrase(userId: String, phraseId: String): PulseResult<OkDto> =
        deleteWithJson(
            "/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases?phraseId=" +
                java.net.URLEncoder.encode(phraseId, "UTF-8"),
            buildJsonObject { },
        ) { PulseJson.decodeFromString(OkDto.serializer(), it) }

    /** POST /api/games { userId, conversationId, game?='tictactoe', opponentId? } → { match, message }. */
    suspend fun createGame(userId: String, conversationId: String, opponentId: String?): PulseResult<GameMatchCreateResultDto> =
        post(
            "/api/games",
            buildJsonObject {
                put("userId", userId)
                put("conversationId", conversationId)
                put("game", "tictactoe")
                if (!opponentId.isNullOrBlank()) put("opponentId", opponentId)
            },
        ) { PulseJson.decodeFromString(GameMatchCreateResultDto.serializer(), it) }

    /** GET /api/games?conversationId= → { matches } newest 25 (list page for the room games view). */
    suspend fun games(conversationId: String): PulseResult<GamesPageDto> =
        get("/api/games?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8")) {
            PulseJson.decodeFromString(GamesPageDto.serializer(), it)
        }

    /** GET /api/games/{id} → { match, playerX, playerO }. */
    suspend fun game(matchId: String): PulseResult<GameDetailDto> =
        get("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8")) { matchOf(it) }

    /** POST /api/games/{id}/move { userId, cell 0..8 } — 409s for turn/occupied/race surface verbatim. */
    suspend fun gameMove(matchId: String, userId: String, cell: Int): PulseResult<GameDetailDto> =
        post("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8") + "/move", jsonOf("userId" to userId, "cell" to cell)) {
            matchOf(it)
        }

    /** POST /api/games/{id}/join { userId } — first-come O seat; 409 when taken. */
    suspend fun joinGame(matchId: String, userId: String): PulseResult<GameDetailDto> =
        post("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8") + "/join", jsonOf("userId" to userId)) {
            matchOf(it)
        }

    /** POST /api/tournaments { userId, conversationId, name ≤40 } → { tournament, message }. */
    suspend fun createTournament(userId: String, conversationId: String, name: String): PulseResult<TournamentCreateResultDto> =
        post("/api/tournaments", jsonOf("userId" to userId, "conversationId" to conversationId, "name" to name, "game" to "tictactoe")) {
            PulseJson.decodeFromString(TournamentCreateResultDto.serializer(), it)
        }

    /** GET /api/tournaments?conversationId= → { tournaments } newest 5 with playerCount. */
    suspend fun tournaments(conversationId: String): PulseResult<TournamentsPageDto> =
        get("/api/tournaments?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8")) {
            PulseJson.decodeFromString(TournamentsPageDto.serializer(), it)
        }

    /** GET /api/tournaments/{id} → { tournament } standings (points → wins → joinedAt). */
    suspend fun tournament(tournamentId: String): PulseResult<TournamentSummaryDto> =
        get("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8")) { tournamentOf(it) }

    /** PATCH /api/tournaments/{id} { userId, status:'finished' } — creator/admin, idempotent. */
    suspend fun finishTournament(tournamentId: String, userId: String): PulseResult<TournamentSummaryDto> =
        patch("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8"), jsonOf("userId" to userId, "status" to "finished")) {
            tournamentOf(it)
        }

    /** POST /api/tournaments/{id}/join { userId } — idempotent upsert. */
    suspend fun joinTournament(tournamentId: String, userId: String): PulseResult<TournamentJoinResultDto> =
        post("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8") + "/join", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(TournamentJoinResultDto.serializer(), it)
        }

    /** GET /api/leaderboard?conversationId=&userId= (room) or bare (global top 50). */
    suspend fun leaderboard(conversationId: String?, userId: String?): PulseResult<LeaderboardPageDto> {
        val q = StringBuilder()
        if (conversationId != null) {
            q.append("?conversationId=").append(java.net.URLEncoder.encode(conversationId, "UTF-8"))
            q.append("&userId=").append(java.net.URLEncoder.encode(userId ?: "", "UTF-8"))
        }
        return get("/api/leaderboard$q") { PulseJson.decodeFromString(LeaderboardPageDto.serializer(), it) }
    }

    /** GET /api/hub/wallet?userId=[&ledger=30] — upserts a zero wallet; ledger desc. */
    suspend fun wallet(userId: String, ledger: Int = 30): PulseResult<WalletPageDto> =
        get("/api/hub/wallet?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + "&ledger=$ledger") {
            PulseJson.decodeFromString(WalletPageDto.serializer(), it)
        }

    /** POST /api/hub/wallet/checkin { userId } — 409 body carries { error, wallet }. */
    suspend fun checkinWallet(userId: String): PulseResult<CheckinWalletResultDto> =
        post("/api/hub/wallet/checkin", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(CheckinWalletResultDto.serializer(), it)
        }

    /** POST /api/hub/wallet/transfer { userId, toUsername, amount, note? } — handle lowercased/@-stripped client-side too. */
    suspend fun transferCoins(userId: String, toUsername: String, amount: Long, note: String?): PulseResult<TransferResultDto> =
        post(
            "/api/hub/wallet/transfer",
            buildJsonObject {
                put("userId", userId)
                put("toUsername", toUsername.trim().removePrefix("@").lowercase())
                put("amount", amount)
                if (!note.isNullOrBlank()) put("note", note)
            },
        ) { PulseJson.decodeFromString(TransferResultDto.serializer(), it) }

    /** GET /api/hub/swap → { rates, stats }. */
    suspend fun swapRates(): PulseResult<SwapPageDto> =
        get("/api/hub/swap") { PulseJson.decodeFromString(SwapPageDto.serializer(), it) }

    /** POST /api/hub/swap { userId, direction: pc2gem|gem2pc, amount }. */
    suspend fun swap(userId: String, direction: String, amount: Long): PulseResult<SwapResultDto> =
        post("/api/hub/swap", jsonOf("userId" to userId, "direction" to direction, "amount" to amount)) {
            PulseJson.decodeFromString(SwapResultDto.serializer(), it)
        }

    /** GET /api/hub/tasks?userId= → { tasks } ordered doing → todo → done. */
    suspend fun hubTasks(userId: String): PulseResult<HubTasksPageDto> =
        get("/api/hub/tasks?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(HubTasksPageDto.serializer(), it)
        }

    /** POST /api/hub/tasks { userId, title 1..120, status? } → { task }. */
    suspend fun createHubTask(userId: String, title: String, status: String? = null): PulseResult<HubTaskDto> =
        post("/api/hub/tasks", jsonOf("userId" to userId, "title" to title, "status" to (status ?: "todo"))) {
            taskOf(it)
        }

    /** PATCH /api/hub/tasks/{id} { userId, title?, status? } — owner-only. */
    suspend fun updateHubTask(taskId: String, userId: String, title: String?, status: String?): PulseResult<HubTaskDto> =
        patch(
            "/api/hub/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"),
            buildJsonObject {
                put("userId", userId)
                if (title != null) put("title", title)
                if (status != null) put("status", status)
            },
        ) { taskOf(it) }

    /** DELETE /api/hub/tasks/{id}?userId= — owner-only. */
    suspend fun deleteHubTask(taskId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/hub/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"), userId) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    /** GET /api/hub/market?userId= — open listings + viewer's own (any status), ≤60. */
    suspend fun market(userId: String): PulseResult<MarketPageDto> =
        get("/api/hub/market?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(MarketPageDto.serializer(), it)
        }

    /** POST /api/hub/market { userId, title 1..80, description?, price 1..100000 } → { listing }. */
    suspend fun createListing(userId: String, title: String, description: String?, price: Long): PulseResult<MarketListingDto> =
        post(
            "/api/hub/market",
            buildJsonObject {
                put("userId", userId)
                put("title", title)
                if (!description.isNullOrBlank()) put("description", description)
                put("price", price)
            },
        ) { listingOf(it) }

    /** POST /api/hub/market/{id}/buy { userId } → { ok, wallet } (atomic escrow-less debit/credit). */
    suspend fun buyListing(listingId: String, userId: String): PulseResult<MarketBuyResultDto> =
        post("/api/hub/market/" + java.net.URLEncoder.encode(listingId, "UTF-8") + "/buy", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(MarketBuyResultDto.serializer(), it)
        }

    /** GET /api/hub/logs?limit=&kind= → { logs } desc; `meta` stays a raw JSON string. */
    suspend fun hubLogs(limit: Int = 60, kind: String? = null): PulseResult<HubLogsPageDto> {
        val q = StringBuilder("?limit=$limit")
        if (!kind.isNullOrBlank()) q.append("&kind=").append(java.net.URLEncoder.encode(kind, "UTF-8"))
        return get("/api/hub/logs$q") { PulseJson.decodeFromString(HubLogsPageDto.serializer(), it) }
    }

    /** GET /api/hub/apps/{appId}/install?userId= — appId is the numeric matrix id as a string. */
    suspend fun appInstallState(appId: String, userId: String): PulseResult<AppInstallStateDto> =
        get("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(AppInstallStateDto.serializer(), it)
        }

    /** POST /api/hub/apps/{appId}/install { userId } — idempotent connect. */
    suspend fun installApp(appId: String, userId: String): PulseResult<AppInstallResultDto> =
        post("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(AppInstallResultDto.serializer(), it)
        }

    /** DELETE /api/hub/apps/{appId}/install { userId } — hard remove. */
    suspend fun uninstallApp(appId: String, userId: String): PulseResult<AppInstallResultDto> =
        deleteWithBody("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install", userId) {
            PulseJson.decodeFromString(AppInstallResultDto.serializer(), it)
        }

    /** GET /api/hub/apps/{appId}/community?userId= → { conversation|null, memberCount, joined }. */
    suspend fun appCommunity(appId: String, userId: String): PulseResult<AppCommunityDto> =
        get("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/community?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(AppCommunityDto.serializer(), it)
        }

    /** POST /api/hub/apps/{appId}/community { userId } — auto-provisions the group; founder = admin. */
    suspend fun joinAppCommunity(appId: String, userId: String): PulseResult<AppCommunityDto> =
        post("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/community", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(AppCommunityDto.serializer(), it)
        }

    // ── REM-A group governance (web group-info-sheet parity) ──────────

    /**
     * PATCH /api/conversations/{id} { requesterId, name?/broadcast?/photo?/screenPrivacy? }
     * Group meta changes — name/broadcast/photo are ADMIN-only server-side,
     * screenPrivacy is any-participant. → { conversation: ConversationDetail }.
     */
    suspend fun patchConversation(
        conversationId: String,
        requesterId: String,
        name: String? = null,
        broadcast: Boolean? = null,
        photo: String? = null,
        screenPrivacy: Boolean? = null,
    ): PulseResult<ConversationSummaryDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8"),
            buildJsonObject {
                put("requesterId", requesterId)
                if (name != null) put("name", name)
                if (broadcast != null) put("broadcast", broadcast)
                if (photo != null) put("photo", photo)
                if (screenPrivacy != null) put("screenPrivacy", screenPrivacy)
            },
        ) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            )
        }

    /** POST /api/conversations/{id}/members { requesterId, userIds[] } — admin-only add. */
    suspend fun addMembers(conversationId: String, requesterId: String, userIds: List<String>): PulseResult<MembersAddedDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            buildJsonObject {
                put("requesterId", requesterId)
                put("userIds", kotlinx.serialization.json.JsonArray(userIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        ) { PulseJson.decodeFromString(MembersAddedDto.serializer(), it) }

    /** PATCH /api/conversations/{id}/members { requesterId, userId, role } — promote/demote (admin-only). */
    suspend fun setMemberRole(conversationId: String, requesterId: String, userId: String, role: String): PulseResult<Unit> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            jsonOf("requesterId" to requesterId, "userId" to userId, "role" to role),
        )

    /**
     * PATCH /api/conversations/{id}/members/{userId} { requesterId, action } —
     * the same promote/demote contract on the member-scoped path (the web
     * group-info sheet uses THIS route; both exist server-side).
     */
    suspend fun promoteDemote(conversationId: String, requesterId: String, userId: String, promote: Boolean): PulseResult<ConversationSummaryDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members/" +
                java.net.URLEncoder.encode(userId, "UTF-8"),
            jsonOf("requesterId" to requesterId, "action" to if (promote) "promote" else "demote"),
        ) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            )
        }

    /** DELETE /api/conversations/{id}/members/{userId} { requesterId } — kick a NON-admin member. */
    suspend fun kickMember(conversationId: String, requesterId: String, userId: String): PulseResult<GroupMutationAckDto> =
        deleteWithJson(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members/" +
                java.net.URLEncoder.encode(userId, "UTF-8"),
            jsonOf("requesterId" to requesterId),
        ) { PulseJson.decodeFromString(GroupMutationAckDto.serializer(), it) }

    /**
     * DELETE /api/conversations/{id}/members { requesterId } — LEAVE group.
     * Self-removal path with last-admin succession; kicking yourself on the
     * member-scoped route is a 400 with a "Leave group" pointer (web parity).
     */
    suspend fun leaveGroup(conversationId: String, requesterId: String): PulseResult<GroupMutationAckDto> =
        deleteWithJson(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            jsonOf("requesterId" to requesterId),
        ) { PulseJson.decodeFromString(GroupMutationAckDto.serializer(), it) }

    /** POST /api/conversations/{id}/invite { requesterId, regenerate? } — admin-only lazy create/regenerate. */
    suspend fun createInvite(conversationId: String, requesterId: String, regenerate: Boolean): PulseResult<InviteCodeDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/invite",
            jsonOf("requesterId" to requesterId, "regenerate" to regenerate),
        ) { PulseJson.decodeFromString(InviteCodeDto.serializer(), it) }

    /** PATCH /api/conversations/{id}/disappearing { userId, ttlSeconds } — any participant; presets 0/1d/7d/30d. */
    suspend fun setDisappearingTtl(conversationId: String, userId: String, ttlSeconds: Int): PulseResult<ConversationSummaryDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/disappearing",
            jsonOf("userId" to userId, "ttlSeconds" to ttlSeconds),
        ) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            )
        }

    /** PATCH /api/conversations/{id}/slow-mode { userId, seconds } — admin-only; presets 0/5/10/30/60/300. */
    suspend fun setSlowMode(conversationId: String, userId: String, seconds: Int): PulseResult<SlowModeAckDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/slow-mode",
            jsonOf("userId" to userId, "seconds" to seconds),
        ) { PulseJson.decodeFromString(SlowModeAckDto.serializer(), it) }

    // ── REM-A scheduled sends (F-MS-18) ─────────────────────────────

    /** GET /api/conversations/{id}/scheduled?userId= — the caller's OWN pending rows, soonest first. */
    suspend fun scheduledMessages(conversationId: String, userId: String): PulseResult<ScheduledPageDto> =
        get(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
                "/scheduled?userId=" + java.net.URLEncoder.encode(userId, "UTF-8"),
        ) { PulseJson.decodeFromString(ScheduledPageDto.serializer(), it) }

    /** POST /api/conversations/{id}/scheduled { senderId, content, scheduledAt } → 201 { item }. */
    suspend fun scheduleMessage(conversationId: String, senderId: String, content: String, scheduledAtIso: String): PulseResult<ScheduledItemDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/scheduled",
            jsonOf("senderId" to senderId, "content" to content, "scheduledAt" to scheduledAtIso),
        ) { PulseJson.decodeFromString(ScheduledItemDto.serializer(), PulseJson.parseToJsonElement(it).unwrapOrRoot("item").toString()) }

    /** DELETE /api/scheduled/{id} { requesterId } → { ok: true } — cancel a pending delayed send. */
    suspend fun cancelScheduled(scheduledId: String, requesterId: String): PulseResult<OkDto> =
        deleteWithJson(
            "/api/scheduled/" + java.net.URLEncoder.encode(scheduledId, "UTF-8"),
            jsonOf("requesterId" to requesterId),
        ) { PulseJson.decodeFromString(OkDto.serializer(), it) }

    companion object {
        /** Per-request cap for the slow voice-caption ASR round-trip. */
        private const val TRANSCRIBE_TIMEOUT_MS = 60_000L

        /** R1-W2F — LLM translate round-trip cap (iOS parity, timeoutCap: 30). */
        private const val TRANSLATE_TIMEOUT_MS = 30_000L

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

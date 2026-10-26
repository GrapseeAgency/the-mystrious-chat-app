package app.pulse.data.remote

import app.pulse.protocol.objectPayloadText
import app.pulse.protocol.GamePayloadDto
import app.pulse.protocol.TournamentPayloadDto

import kotlinx.coroutines.ensureActive
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
import app.pulse.protocol.AiRecapDto
import app.pulse.protocol.AutomationEnvelopeDto
import app.pulse.protocol.AutomationsPageDto
import app.pulse.protocol.CheckinResultDto
import app.pulse.protocol.CheckinWalletResultDto
import app.pulse.protocol.EventsPageDto
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.GameMatchCreateResultDto
import app.pulse.protocol.GamesPageDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.GroupCallStatePayload
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
import app.pulse.protocol.ScreenPrivacyAckDto
import app.pulse.protocol.SubscribeAckDto
import app.pulse.protocol.ThreadPageDto
import app.pulse.protocol.WebhookDto
import app.pulse.protocol.WebhooksPageDto
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ktor REST client - REAL wiring to the Next.js API (gateway :81 in dev).
 * The API identifies the caller with `userId` params (same as the web client);
 * every method maps failures onto PulseResult kinds identical to iOS.
 *
 * Wave 8 - session tokens: every request carries
 * `Authorization: Bearer <token>` when the [bearerToken] provider hands back
 * a credential (server-side optional-verify: no header = accepted for the
 * web-migration window, present-but-invalid = 401). A 401 response invokes
 * [onAuthInvalid] so the store layer clears the rotated credential and the
 * app surfaces the honest re-login path.
 */
private const val OFFLINE_COPY =
    "No gateway configured - set your server in Profile → Connection."

class PulseApi(
    private val http: HttpClient,
    /** Current session credential - null = ride header-less (web migration window). */
    private val bearerToken: () -> String? = { null },
    /** Fired on any 401 so the token store can clear + surface re-login. */
    private val onAuthInvalid: (String?) -> Unit = {},
    private val gateway: () -> String = { PulseEndpoints.gatewayHttpUrl },
) {
    private fun url(path: String): String = gateway().trimEnd('/') + if (path.startsWith("/")) path else "/$path"

    /**
     * Honest offline-first gate. With NO configured gateway, `PulseEndpoints.http`
     * returns a bare relative path - and Ktor's URLBuilder resolves that against
     * its implicit `http://localhost` default (ktor-http URLBuilder host default),
     * so OkHttp fires a cleartext request at the PHONE ITSELF and Android's
     * network security policy blocks it: the "CLEARTEXT communication to
     * localhost not permitted" field report on the Home inbox. The request is
     * never fired now - users get actionable copy instead of a policy dump.
     * The security policy is untouched (cleartext stays blocked); the accidental
     * request is what's removed.
     */
    private val offlineFailure: PulseResult.Failure =
        PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, OFFLINE_COPY)

    private suspend fun <T> get(path: String, parse: (String) -> T): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.get(url(path)) { authHeader() }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(parse(text))
            else failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    private suspend fun <T> post(
        path: String,
        body: JsonObject?,
        /** Per-request requestTimeout override (Ktor 2 `timeout {}` extension) - null = plugin default. */
        timeoutMillis: Long? = null,
        parse: ((String) -> T)? = null,
    ): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.post(url(path)) {
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
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "The gateway acknowledgement could not be decoded; the action may have completed")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /**
     * PATCH verb - the conversation flag routes (pin/mute/archive/mark-unread) are
     * PATCH on the wire; POST was the N3-era wrong verb (spec §14 transport fixes).
     */
    private suspend fun <T> patch(path: String, body: JsonObject?, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.patch(url(path)) {
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body.toString())
                authHeader()
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "The gateway did not confirm the change; refresh before retrying")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /**
     * Wave 8 - attach `Authorization: Bearer <token>` when a credential is
     * present. The server's optional-verify proxy accepts header-less calls
     * (web migration window) but rejects PRESENT-but-invalid tokens with 401,
     * so we attach only when the store actually holds a token.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.authHeader() {
        bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * HTTP failure → Failure with the body's error/code/suggestion intact -
     * the onboarding username_taken flow needs the server's suggestion to
     * survive the trip (web parity: createUserRequest in onboarding-screen.tsx).
     */
    private fun failureOf(status: Int, body: String, retryHeader: String? = null, serverDate: String? = null): PulseResult.Failure {
        var message: String? = body.take(300)
        var code: String? = null
        var suggestion: String? = null
        var retryAfter: Int? = null
        var receiptClientId: String? = null
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
                (json["clientId"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) receiptClientId = it.content
                }
                // R44 slow mode - the 429 body carries { error, retryAfter } and a
                // Retry-After header (same value). The body wins (web parity).
                (json["retryAfter"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    retryAfter = runCatching { it.content.toInt() }.getOrNull()
                }
            }
        } catch (_: Exception) {
            // non-JSON body - keep the raw text as the message
        }
        // Wave 8 - a 401 means the presented token is invalid/rotated. The
        // typed Kind.AUTH failure rides back to the caller AND the hook lets
        // the store layer clear the credential + raise the re-login flow.
        if (status == 401) onAuthInvalid(message)
        retryAfter = listOfNotNull(retryAfter?.takeIf { it > 0 }, retryAfterSeconds(retryHeader, serverDate)).maxOrNull()
        val base = PulseResult.fromHttp(status, message)
        return base.copy(code = code, suggestion = suggestion, status = status, retryAfter = retryAfter, receiptClientId = receiptClientId)
    }

    // conversations
    suspend fun conversations(userId: String): PulseResult<ConversationsPageDto> =
        get("/api/conversations?userId=$userId") {
            PulseJson.decodeFromString(ConversationsPageDto.serializer(), it)
        }

    suspend fun messages(
        conversationId: String,
        limit: Int = 200,
        before: String? = null,
        topicId: String? = null,
        /**
         * R2-C item 2 (D47 delta sync) - ISO-date cursor; the server returns
         * only messages strictly NEWER than it, same { messages, hasMore,
         * total } shape and limits as the newest window (route contract:
         * when both after=/since= arrive the LATER one wins). Null = the
         * unchanged full-window fetch.
         */
        since: String? = null,
        syncFrom: String? = null,
        syncCursor: String? = null,
        beforeId: String? = null,
    ): PulseResult<MessagesPageDto> {
        val cursor = before?.let { "&before=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val topic = topicId?.let { "&topicId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val delta = since?.let { "&since=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val sync = listOf("syncFrom" to syncFrom, "syncCursor" to syncCursor, "beforeId" to beforeId)
            .filter { it.second != null }.joinToString("") { (key, value) ->
                "&$key=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
        return get("/api/conversations/$conversationId/messages?limit=$limit$cursor$topic$delta$sync") {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }
    }

    /**
     * POST /api/conversations/{id}/messages - the full Wave-1 wire body
     * (spec §1.1): thread replies ride `parentId`, inline quotes `replyToId`,
     * media rides imagePath/audioPath/filePath - only non-null keys are sent.
     * `kind` whitelist: text|image|audio|sticker|location|file.
     *
     * R5-B - the response is the server envelope `{ message, streak?, xpAwarded }`
     * (messages/route.ts:704-708); [app.pulse.protocol.decodeMessageSend]
     * unwraps it (bare-row bodies from alternate gateways still decode).
     */
    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        content: String,
        clientId: String = java.util.UUID.randomUUID().toString(),
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
        /** R24-b incognito - group-only server-side; DMs silently ignore it. */
        anon: Boolean? = null,
        /** Rich payload blob - sticker {emoji,pack} · location {lat,lng,label} · effects {effect}. */
        payload: JsonObject? = null,
    ): PulseResult<app.pulse.protocol.MessageSendEnvelopeDto> =
        post(
            "/api/conversations/$conversationId/messages",
            buildJsonObject {
                put("senderId", senderId)
                put("clientId", clientId)
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
    suspend fun createConversation(creatorId: String, memberIds: List<String>, isGroup: Boolean, name: String?, clientId: String? = null): PulseResult<ConversationSummaryDto> =
        post(
            if (clientId == null) "/api/conversations" else "/api/conversations/create-v2",
            buildJsonObject {
                put("creatorId", creatorId)
                if (clientId != null) put("clientId", clientId)
                put("memberIds", kotlinx.serialization.json.JsonArray(memberIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("isGroup", isGroup)
                if (name != null) put("name", name)
            },
        ) { raw ->
            val root = PulseJson.parseToJsonElement(raw)
            val row = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), root.unwrapOrRoot("conversation").toString())
            if (clientId != null) {
                val envelope = requireNotNull(root as? JsonObject)
                require(envelope["clientId"] == JsonPrimitive(clientId) && envelope["replayed"] in listOf(JsonPrimitive(true), JsonPrimitive(false))) { "Creation receipt was not confirmed; keep the same retry identity" }
                require(row.id.isNotBlank() && row.isGroup && row.isSelf != true && row.broadcastMode != true && row.members.any { it.id == creatorId }) { "Creation returned an unrelated room" }
                if (envelope["replayed"] != JsonPrimitive(true)) {
                    val expected = (memberIds + creatorId).toSet()
                    require(row.members.size == expected.size && row.members.map { it.id }.toSet() == expected) { "Creation members were not confirmed" }
                }
            }
            row
        }

    /** Exact owner/room/desired-state receipt before changing a cached badge. */
    suspend fun markUnread(conversationId: String, userId: String, on: Boolean): PulseResult<Unit> =
        patch("/api/conversations/$conversationId/mark-unread", jsonOf("userId" to userId, "on" to on)) { raw ->
            // Even the strict decoder accepts quoted booleans. Check JSON token
            // types before deserialization: a string is not a committed boolean.
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val fields = json.parseToJsonElement(raw) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Unread receipt must be an object")
            for (key in listOf("ok", "manualUnread")) {
                val value = fields[key] as? JsonPrimitive
                if (value == null || value.isString || value.content !in listOf("true", "false")) {
                    throw kotlinx.serialization.SerializationException("Unread receipt requires boolean $key")
                }
            }
            val version = fields["unreadReceiptVersion"] as? JsonPrimitive
            if (version == null || version.isString || version.content != "1" ||
                listOf("conversationId", "userId").any { (fields[it] as? JsonPrimitive)?.isString != true }) {
                throw kotlinx.serialization.SerializationException("Unread receipt identity or version is invalid")
            }
            val receipt = json.decodeFromString(app.pulse.protocol.ManualUnreadReceiptDto.serializer(), raw)
            if (!receipt.ok || receipt.unreadReceiptVersion != 1 || receipt.conversationId != conversationId ||
                receipt.userId != userId || receipt.manualUnread != on) {
                throw kotlinx.serialization.SerializationException("Unread state was not confirmed. Refresh before retrying.")
            }
            Unit
        }

    /** Visible attention and manual mark-all are separate, versioned intents. */
    suspend fun markRead(conversationId: String, userId: String, messageId: String? = null,
        minimumReadAt: String? = null): PulseResult<Unit> =
        post("/api/conversations/$conversationId/read-state", jsonOf("userId" to userId,
            "mode" to if (messageId == null) "all" else "visible", "messageId" to messageId)) { raw ->
            val receipt = PulseJson.decodeFromString(app.pulse.protocol.ReadReceiptDto.serializer(), raw)
            require(receipt.ok && receipt.readReceiptVersion == 1 && receipt.conversationId == conversationId &&
                receipt.userId == userId && receipt.messageId == messageId &&
                receipt.mode == if (messageId == null) "all" else "visible") { "Read state was not confirmed." }
            val acknowledged = java.time.Instant.parse(receipt.lastReadAt)
            require(minimumReadAt == null || !acknowledged.isBefore(java.time.Instant.parse(minimumReadAt))) {
                "The displayed read boundary was not confirmed."
            }
            Unit
        }

    // reactions
    /** Explicit state: no legacy toggle fallback after an uncertain reply. */
    suspend fun react(messageId: String, userId: String, emoji: String, present: Boolean): PulseResult<ChatMessageDto> =
        post(
            "/api/messages/$messageId/react-state",
            buildJsonObject {
                put("userId", userId)
                put("emoji", emoji)
                put("present", present)
            },
        ) { raw ->
            val envelope = requireNotNull(PulseJson.parseToJsonElement(raw) as? JsonObject) { "Reaction response is missing" }
            val receipt = requireNotNull(envelope["reaction"] as? JsonObject) { "Reaction state was not confirmed" }
            require(receipt["messageId"] == JsonPrimitive(messageId) && receipt["emoji"] == JsonPrimitive(emoji) &&
                receipt["present"] == JsonPrimitive(present)) { "Reaction state acknowledgement did not match" }
            messageOf(raw).also { row ->
                require(row.id == messageId) { "Reaction returned another message" }
                val confirmed = row.reactions.any { it.emoji == emoji && (it.userId == userId || it.userIds?.contains(userId) == true) }
                require(confirmed == present) { "Returned message did not contain the confirmed reaction state" }
            }
        }

    // users / identity
    /** GET /api/users → { users: [...] } (identity picker + contacts). */
    suspend fun users(): PulseResult<UsersPageDto> =
        get("/api/users") { PulseJson.decodeFromString(UsersPageDto.serializer(), it) }

    /** GET /api/users/check-username?username=x → { available, suggestion } (400 on invalid). */
    suspend fun checkUsername(username: String): PulseResult<UsernameCheckDto> =
        get("/api/users/check-username?username=" + java.net.URLEncoder.encode(username, "UTF-8")) {
            PulseJson.decodeFromString(UsernameCheckDto.serializer(), it)
        }

    /** GET registry/handles.json - the static CDN availability registry (the
     *  offline-first fallback when no live gateway answers). */
    suspend fun fetchHandleRegistry(): PulseResult<HandleRegistryDto> =
        get("/registry/handles.json") {
            PulseJson.decodeFromString(HandleRegistryDto.serializer(), it)
        }

    /** GET /api/users?name=X → { user } - case-insensitive lookup (404 when free). */
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
     * stored hash - the old token goes invalid) | 400 "Name is required." |
     * 404 "No identity with that name on this Pulse." - the honest copy
     * surfaces verbatim through the Failure.
     */
    suspend fun login(name: String): PulseResult<UserAuthEnvelopeDto> =
        post("/api/users/login", jsonOf("name" to name)) { decodeUserAuth(it) }

    // settings (Wave 8 - src/app/api/settings contract)

    /** GET /api/settings?userId= → { preferences } (defaults merged server-side). */
    suspend fun settings(userId: String): PulseResult<SettingsEnvelopeDto> =
        get("/api/settings?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            it.decodeSettingsEnvelope()
        }

    /**
     * GET /api/users/[id]/phrases -> { phrases: [{id,text,position}] }.
     * R59 mirror parity: the quick-phrase chip rail above the room composer
     * reads the same server-backed rows the web renders (F-MS-29).
     */
    suspend fun quickPhrases(userId: String): PulseResult<List<Pair<String, String>>> =
        when (val result = phrases(userId)) {
            is PulseResult.Success -> PulseResult.Success(result.value.phrases.map { it.id to it.text })
            is PulseResult.Failure -> result
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

    data class ConversationPreferenceAcknowledgement(val timestamp: String?, val epochMillis: Long?)

    private fun preferenceReceipt(text: String, room: String, owner: String, field: String,
        expected: kotlinx.serialization.json.JsonElement, timestampField: String, enabled: Boolean): ConversationPreferenceAcknowledgement {
        val body = PulseJson.parseToJsonElement(text) as? JsonObject
            ?: throw kotlinx.serialization.SerializationException("Conversation setting receipt is missing")
        if (body["ok"] != JsonPrimitive(true) || body["conversationPreferenceVersion"] != JsonPrimitive(1) ||
            body["conversationId"] != JsonPrimitive(room) || body["userId"] != JsonPrimitive(owner) || body[field] != expected)
            throw kotlinx.serialization.SerializationException("Conversation setting owner or value was not confirmed")
        val raw = body[timestampField]
        if (!enabled) {
            if (raw != JsonNull) throw kotlinx.serialization.SerializationException("Cleared setting was not confirmed")
            return ConversationPreferenceAcknowledgement(null, null)
        }
        val stamp = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw kotlinx.serialization.SerializationException("Setting timestamp was not confirmed")
        val millis = runCatching { java.time.Instant.parse(stamp).toEpochMilli() }.getOrNull()
            ?: throw kotlinx.serialization.SerializationException("Setting timestamp is invalid")
        return ConversationPreferenceAcknowledgement(stamp, millis)
    }
    private fun preferencePath(room: String, action: String) = "/api/conversations/" +
        java.net.URLEncoder.encode(room, "UTF-8").replace("+", "%20") + "/" + action

    suspend fun setConversationPinned(room: String, owner: String, pinned: Boolean): PulseResult<ConversationPreferenceAcknowledgement> =
        patch(preferencePath(room, "pin"), jsonOf("userId" to owner, "pinned" to pinned)) {
            preferenceReceipt(it, room, owner, "pinned", JsonPrimitive(pinned), "pinnedAt", pinned)
        }
    suspend fun setConversationArchived(room: String, owner: String, archived: Boolean): PulseResult<ConversationPreferenceAcknowledgement> =
        patch(preferencePath(room, "archive"), jsonOf("userId" to owner, "archived" to archived)) {
            preferenceReceipt(it, room, owner, "archived", JsonPrimitive(archived), "archivedAt", archived)
        }
    suspend fun setConversationMuted(room: String, owner: String, until: String?): PulseResult<ConversationPreferenceAcknowledgement> {
        if (until !in listOf(null, "8h", "1w", "always"))
            return PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "Unknown mute duration")
        val preset = until?.let { JsonPrimitive(it) } ?: JsonNull
        return patch(preferencePath(room, "mute"), buildJsonObject { put("userId", owner); put("until", preset) }) {
            preferenceReceipt(it, room, owner, "mutePreset", preset, "mutedUntil", until != null)
        }
    }

    // Wave 1 messaging surface (spec §1.1)

    /** `{message: ChatMessage}` unwrap shared by the edit/pin actions. */
    private fun messageOf(json: String): ChatMessageDto =
        PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("message").toString(),
        )

    /** PATCH /api/messages/{id} { userId, content } → { message } (sender-only edit). */
    suspend fun editMessage(messageId: String, userId: String, content: String): PulseResult<ChatMessageDto> =
        patch("/api/messages/$messageId", jsonOf("userId" to userId, "content" to content)) { messageOf(it) }

    /** Explicit addition; no legacy toggle fallback on an uncertain response. */
    suspend fun pinMessage(messageId: String, userId: String): PulseResult<ChatMessageDto> =
        post("/api/messages/$messageId/pin-state", jsonOf("userId" to userId, "pinned" to true)) { raw ->
            val envelope = requireNotNull(PulseJson.parseToJsonElement(raw) as? JsonObject) { "Pin response is missing" }
            val receipt = requireNotNull(envelope["pin"] as? JsonObject) { "Pin state was not confirmed" }
            require(receipt["messageId"] == JsonPrimitive(messageId) && receipt["pinned"] == JsonPrimitive(true)) {
                "Pin state acknowledgement did not match"
            }
            messageOf(raw).also { row ->
                require(row.id == messageId && row.deletedAt == null && row.viewOnce != true && row.pinnedAt != null) { "The pin update was not confirmed" }
            }
        }

    suspend fun unpinMessage(messageId: String, userId: String): PulseResult<Unit> =
        deleteWithBody("/api/messages/$messageId/pin", userId) {
            val ack = PulseJson.decodeFromString(app.pulse.protocol.UnpinAckDto.serializer(), it)
            if (!ack.unpinned) throw kotlinx.serialization.SerializationException("Unpin was not acknowledged")
            Unit
        }

    /** Explicit state only; never fall back to a toggle after response loss. */
    suspend fun setMessageSaved(messageId: String, userId: String, saved: Boolean): PulseResult<app.pulse.protocol.SavedStateReceiptDto> =
        post("/api/messages/$messageId/save-state", jsonOf("userId" to userId, "saved" to saved)) { raw ->
            PulseJson.decodeFromString(app.pulse.protocol.SavedStateReceiptDto.serializer(), raw).also { receipt ->
                require(receipt.savedReceiptVersion == 1 && receipt.userId == userId && receipt.messageId == messageId && receipt.saved == saved) {
                    "Saved state was not confirmed."
                }
                val stamp = receipt.savedAt
                if (saved) java.time.Instant.parse(requireNotNull(stamp) { "Saved timestamp was not confirmed." })
                else require(stamp == null) { "Removal returned a saved timestamp." }
            }
        }

    /** GET /api/conversations/{id}/pinned?userId= → { messages } (pinnedAt asc). */
    suspend fun pinnedMessages(conversationId: String, userId: String): PulseResult<PinnedPageDto> =
        get("/api/conversations/$conversationId/pinned?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(PinnedPageDto.serializer(), it)
        }

    private class ThreadPageFailure(val failure: PulseResult.Failure) : Exception(failure.message)

    /** Complete native thread assembled from bounded, reauthorized server pages. */
    suspend fun thread(rootId: String, userId: String): PulseResult<ThreadPageDto> = try {
        PulseResult.Success(CompleteThread.load(rootId) { cursor ->
            val path = "/api/messages/$rootId/thread-page?limit=100&userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            when (val result = get(path) { PulseJson.decodeFromString(app.pulse.protocol.ThreadSliceDto.serializer(), it) }) {
                is PulseResult.Success -> result.value
                is PulseResult.Failure -> {
                    // A missing route or credential/proxy refusal is NOT proof
                    // of revoked thread access; do not poison the denial ledger.
                    val expected = when (result.status) { 403 -> "thread_access"; 404 -> "thread_missing"; 410 -> "thread_gone"; else -> null }
                    throw ThreadPageFailure(if (expected != null && result.code != expected)
                        result.copy(kind = PulseResult.Failure.Kind.VALIDATION, status = 409,
                            message = "The gateway could not verify this thread. Update it and try again.") else result)
                }
            }
        })
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: ThreadPageFailure) {
        error.failure
    } catch (error: Exception) {
        PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, error.message ?: "Complete thread history was not confirmed.")
    }

    /** GET /api/conversations/{id}/messages?limit=100&q= - in-conversation search
     *  (case-insensitive substring on content + fileName, asc, hasMore:false). */
    suspend fun searchInConversation(conversationId: String, query: String, limit: Int = 100): PulseResult<MessagesPageDto> =
        get(
            "/api/conversations/$conversationId/messages?limit=$limit&q=" +
                java.net.URLEncoder.encode(query, "UTF-8"),
        ) {
            PulseJson.decodeFromString(MessagesPageDto.serializer(), it)
        }

    /** POST /api/uploads { dataUrl } → 201 { filePath, imagePath } - base64 data URL, NOT multipart. */
    suspend fun uploadMedia(dataUrl: String): PulseResult<UploadResultDto> =
        post("/api/uploads", jsonOf("dataUrl" to dataUrl)) {
            PulseJson.decodeFromString(UploadResultDto.serializer(), it)
        }

    /**
     * GET /api/uploads/{file} → raw bytes - the media-download leg of Wave 1
     * (file bubbles save to cacheDir/downloads and open through FileProvider).
     */
    suspend fun downloadMedia(filePath: String): PulseResult<ByteArray> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.get(url("/api/uploads/$filePath")) { authHeader() }
            if (res.status.isSuccess()) {
                PulseResult.Success(res.readBytes())
            } else {
                failureOf(res.status.value, res.bodyAsText(), res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** PATCH /api/conversations/{id}/draft { userId, draft } → { ok, draft } ('' clears). */
    suspend fun setDraft(conversationId: String, userId: String, draft: String): PulseResult<Unit> {
        if (conversationId.isBlank() || userId.isBlank() || draft.length > 2000) {
            return PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "Draft cannot be mirrored; complete local text is kept.")
        }
        val room = java.net.URLEncoder.encode(conversationId, "UTF-8").replace("+", "%20")
        return patch("/api/conversations/$room/draft", jsonOf("userId" to userId, "draft" to draft)) { text ->
            val receipt = PulseJson.parseToJsonElement(text) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Draft receipt is missing")
            if (receipt["ok"] != JsonPrimitive(true) || receipt["draftReceiptVersion"] != JsonPrimitive(1) ||
                receipt["conversationId"] != JsonPrimitive(conversationId) || receipt["userId"] != JsonPrimitive(userId) ||
                receipt["draft"] != JsonPrimitive(draft)) {
                throw kotlinx.serialization.SerializationException("Draft mirror was not confirmed")
            }
            Unit
        }
    }

    /** GET /api/conversations/{id}?userId= → { conversation } (detail incl. members with lastReadAt). */
    suspend fun conversationDetail(conversationId: String, userId: String): PulseResult<ConversationSummaryDto> =
        get("/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8").replace("+", "%20") + "?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            ).also { detail ->
                if (detail.id != conversationId || userId.isBlank() || detail.members.none { member -> member.id == userId })
                    throw kotlinx.serialization.SerializationException("Room detail did not confirm your membership")
            }
        }

    /** POST /api/conversations/self {userId} - the viewer's Note to Self chat. */
    suspend fun createSelfChat(userId: String): PulseResult<ConversationSummaryDto> =
        post("/api/conversations/self", buildJsonObject { put("userId", userId) }) {
            val raw = PulseJson.parseToJsonElement(it) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Notebook receipt is missing")
            if (userId.isBlank() || raw["notebookReceiptVersion"] != JsonPrimitive(1) ||
                raw["userId"] != JsonPrimitive(userId) || raw["conversation"] !is JsonObject)
                throw kotlinx.serialization.SerializationException("Notebook owner was not confirmed")
            val row = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), raw.getValue("conversation").toString())
            if (row.id.isBlank() || row.isGroup || row.isSelf != true || row.members.size != 1 || row.members.single().id != userId)
                throw kotlinx.serialization.SerializationException("The returned notebook is not private to this account")
            row
        }

    /** GET /api/search?userId=&q= - server message search (≥2 chars upstream). */
    suspend fun search(userId: String, query: String): PulseResult<SearchPageDto> {
        if (query.trim().length > 64) return PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "Search text must be 64 UTF-16 units or fewer.")
        return get(
            "/api/search?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                "&q=" + java.net.URLEncoder.encode(query, "UTF-8"),
        ) {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject
            if (root?.get("messages") !is kotlinx.serialization.json.JsonArray)
                throw kotlinx.serialization.SerializationException("Search results are missing")
            PulseJson.decodeFromString(SearchPageDto.serializer(), it)
        }
    }

    /** GET /api/stories?requesterId= - 24h status rail (tolerant subset). */
    suspend fun stories(requesterId: String): PulseResult<StoriesPageDto> =
        get("/api/stories?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8")) {
            val root = PulseJson.parseToJsonElement(it).jsonObject
            if (root["groups"] !is kotlinx.serialization.json.JsonArray) throw kotlinx.serialization.SerializationException("Story feed missing")
            if ((root["groups"] as kotlinx.serialization.json.JsonArray).any { group -> group.jsonObject["stories"] !is kotlinx.serialization.json.JsonArray })
                throw kotlinx.serialization.SerializationException("Story rows missing")
            val page = PulseJson.decodeFromString(StoriesPageDto.serializer(), it)
            val rows = page.groups.flatMap { group -> group.stories }
            if (page.groups.any { group -> group.user?.id.isNullOrBlank() } ||
                page.groups.map { group -> group.user?.id }.distinct().size != page.groups.size ||
                rows.any { row -> row.id.isBlank() } || rows.map { row -> row.id }.distinct().size != rows.size)
                throw kotlinx.serialization.SerializationException("Invalid story identities")
            page
        }

    // Wave 4 stories - full native status surface (REST only, no sockets)

    /**
     * POST /api/stories { requesterId, caption?, background?, imagePath? } → 201 { story }.
     * Text mode sends caption + background (palette key), photo mode sends
     * imagePath (+ optional caption) - background is NEVER sent for photos
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
            val raw = PulseJson.parseToJsonElement(it).jsonObject["viewCount"] as? kotlinx.serialization.json.JsonPrimitive
            if (raw == null || raw.isString || raw.content.toIntOrNull()?.let { count -> count >= 0 } != true)
                throw kotlinx.serialization.SerializationException("View acknowledgement missing")
            PulseJson.decodeFromString(StoryViewAckDto.serializer(), it)
        }

    /** GET /api/stories/{id}/view?requesterId= - owner-only (403 otherwise), oldest first. */
    suspend fun storyViewers(storyId: String, requesterId: String): PulseResult<StoryViewersDto> =
        get(
            "/api/stories/$storyId/view?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8"),
        ) {
            val root = PulseJson.parseToJsonElement(it).jsonObject
            if (root["viewers"] !is kotlinx.serialization.json.JsonArray) throw kotlinx.serialization.SerializationException("Viewers list missing")
            val page = PulseJson.decodeFromString(StoryViewersDto.serializer(), it)
            if (page.viewers.any { row -> row.userId.isBlank() } || page.viewers.map { row -> row.userId }.distinct().size != page.viewers.size)
                throw kotlinx.serialization.SerializationException("Invalid viewers list")
            page
        }

    /** DELETE /api/stories/{id}?requesterId= → { ok: true } (owner-only 403, unknown 404). */
    suspend fun deleteStory(storyId: String, requesterId: String): PulseResult<OkDto> =
        deleteAt("/api/stories/$storyId?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8")) { text ->
            val receipt = PulseJson.parseToJsonElement(text).jsonObject
            if (receipt["ok"] != JsonPrimitive(true)) throw kotlinx.serialization.SerializationException("Story deletion was not confirmed")
            OkDto(ok = true)
        }

    /** GET /api/folders?userId= - Signal-style folder rail. */
    suspend fun folders(userId: String): PulseResult<FoldersPageDto> =
        get("/api/folders?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            val root = requireFolderScope(it, userId)
            require(root["folders"] is kotlinx.serialization.json.JsonArray) { "Folder list was not confirmed." }
            (root["folders"] as kotlinx.serialization.json.JsonArray).forEach { row ->
                require(row is kotlinx.serialization.json.JsonObject && row["conversationIds"] is kotlinx.serialization.json.JsonArray) { "Folder membership is missing." }
            }
            val page = PulseJson.decodeFromString(FoldersPageDto.serializer(), it)
            page.folders.forEach { folder -> requireFolderRow(folder) }
            require(page.folders.map { folder -> folder.id }.distinct().size == page.folders.size) { "Duplicate folders." }
            page
        }

    /** GET /api/mentions?userId=&limit=50 - @mention feed (count consumer). */
    suspend fun mentions(userId: String): PulseResult<MentionsPageDto> =
        get(
            "/api/mentions?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + "&limit=50",
        ) {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject
            if (root?.get("items") !is kotlinx.serialization.json.JsonArray)
                throw kotlinx.serialization.SerializationException("Mention feed is missing")
            PulseJson.decodeFromString(MentionsPageDto.serializer(), it)
        }

    /**
     * DELETE /api/messages/{id} {requesterId} - sender-gated soft delete
     * (clear chat). The route echoes the tombstoned row as { message } -
     * R2-C item 2 upserts it repo-side (the tombstone's ORIGINAL createdAt
     * can never re-arrive through a since= delta refetch).
     */
    suspend fun deleteMessage(messageId: String, requesterId: String): PulseResult<ChatMessageDto> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(url("/api/messages/$messageId")) {
                contentType(ContentType.Application.Json)
                setBody(jsonOf("requesterId" to requesterId).toString())
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(messageOf(text)) else failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    // Wave 2 messaging depth (spec §0 - every route exists on the wire)

    /** POST /api/messages/{id}/transcribe {requesterId} → { transcript, transcribedAt, cached }. */
    suspend fun transcribe(messageId: String, requesterId: String): PulseResult<TranscribeResultDto> =
        post("/api/messages/$messageId/transcribe", jsonOf("requesterId" to requesterId)) {
            PulseJson.decodeFromString(TranscribeResultDto.serializer(), it)
        }

    /** POST /api/messages/{id}/viewed {userId} → { message } (idempotent burn stamp). */
    suspend fun markViewed(messageId: String, userId: String): PulseResult<app.pulse.protocol.ViewOnceReceiptDto> =
        post("/api/messages/$messageId/viewed", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(app.pulse.protocol.ViewOnceReceiptDto.serializer(), it)
        }

    /**
     * R1-W2F - POST /api/messages/{id}/translate {userId, lang?} → { message }.
     * The LLM result persists per language server-side (translate/route.ts);
     * the mapped fresh row carries `translations: [{lang, text}]` - a field
     * ChatMessageDto (tolerantly) ignores, so the TEXT is lifted straight from
     * the wire JSON here, same manual extraction as [unfurl]. `lang` is
     * omitted - the server defaults to "en". LLM round-trips are slow: 30 s
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
     * null is a VALID result (nothing unfurled) - decoded tolerantly. Own
     * runner (the shared `post` helper cannot carry a null parse result).
     */
    suspend fun unfurl(messageId: String, userId: String): PulseResult<ChatMessageDto?> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.post(url("/api/messages/$messageId/unfurl")) {
                contentType(ContentType.Application.Json)
                setBody(jsonOf("userId" to userId).toString())
            }
            val text = res.bodyAsText()
            if (!res.status.isSuccess()) {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            } else {
                val root = PulseJson.parseToJsonElement(text)
                val inner = (root as? JsonObject)?.get("message") as? JsonObject
                PulseResult.Success(inner?.let { PulseJson.decodeFromJsonElement(ChatMessageDto.serializer(), it) })
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
     * POST /api/conversations/{id}/topics {userId, name, emoji?} - 200 existing
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

    /** DELETE /api/topics/{id}?userId= → { ok: true } (creator/admin only - query-param identity). */
    suspend fun deleteTopic(topicId: String, userId: String): PulseResult<OkDto> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(
                url("/api/topics/$topicId?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")),
            )
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                PulseResult.Success(runCatching { PulseJson.decodeFromString(OkDto.serializer(), text) }.getOrDefault(OkDto(ok = true)))
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    // Wave 3 native calls - REST /api/calls (call-types.ts parity)

    /** GET /api/calls?userId= → { items } - newest-first history, server cap 50. */
    suspend fun callLogs(userId: String): PulseResult<CallLogsPageDto> =
        get("/api/calls?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(CallLogsPageDto.serializer(), it)
        }

    /**
     * POST /api/calls { userId, conversationId, peerId, kind, status, durationSec? }
     * → 201 { item }. SINGLE-WRITER RULE: the viewer is always the CALLER
     * (callerId = userId, calleeId = peerId) - the callee never writes.
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

    // R8 Task 3-c - group calls + remote push (web R8 parity)

    /**
     * POST /api/conversations/{id}/calls/ring { userId, kind } - group-call
     * ring fanout: the starting member calls this AFTER gcall:join; online
     * members get the `gcall:ring` relay, offline ones the push. Fire-and-
     * forget for the caller UX - only validation errors matter here.
     */
    suspend fun postGroupCallRing(
        conversationId: String,
        userId: String,
        kind: String,
        callId: String,
    ): PulseResult<Unit> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/calls/ring",
            buildJsonObject {
                put("userId", userId)
                put("kind", kind)
                put("callId", callId)
            },
        )

    /**
     * GET /api/group-call-state?conversationId= - probe for an ONGOING group
     * call (late room open). A versioned viewer/room receipt distinguishes
     * a verified empty roster from an unavailable or mismatched response.
     */
    suspend fun groupCallState(conversationId: String, userId: String): PulseResult<GroupCallStatePayload> =
        get("/api/group-call-state?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
            "&userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) { raw ->
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val fields = json.parseToJsonElement(raw) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Call state must be an object")
            val version = fields["callStateVersion"] as? JsonPrimitive
            if (version == null || version.isString || version.content != "1") {
                throw kotlinx.serialization.SerializationException("Call state version is not supported")
            }
            for ((key, expected) in listOf("conversationId" to conversationId, "userId" to userId)) {
                val value = fields[key] as? JsonPrimitive
                if (value == null || !value.isString || value.content != expected) {
                    throw kotlinx.serialization.SerializationException("Call state identity did not match")
                }
            }
            val receipt = json.decodeFromString(app.pulse.protocol.GroupCallProbeReceiptDto.serializer(), raw)
            if (receipt.conversationId != conversationId || receipt.userId != userId ||
                receipt.kind !in listOf("voice", "video") || receipt.members.size > 8 ||
                receipt.members.any { it.id.isBlank() } || receipt.members.map { it.id }.distinct().size != receipt.members.size ||
                (receipt.members.isEmpty() != receipt.callId.isEmpty()) ||
                (receipt.members.isNotEmpty() && (receipt.callId.isBlank() || (receipt.startedAt ?: -1L) < 0))) {
                throw kotlinx.serialization.SerializationException("Call state did not match this viewer and conversation")
            }
            GroupCallStatePayload(callId = receipt.callId, conversationId = conversationId, kind = receipt.kind,
                startedAt = receipt.startedAt, members = receipt.members)
        }

    /** POST /api/push/register { userId, platform: 'android', token } - upsert the FCM token. */
    suspend fun registerPushToken(userId: String, token: String): PulseResult<Unit> =
        post(
            "/api/push/register",
            buildJsonObject {
                put("userId", userId)
                put("platform", "android")
                put("token", token)
            },
        )

    /** DELETE /api/push/register { token } - unregister on rotation/sign-out. */
    suspend fun unregisterPushToken(token: String): PulseResult<Unit> =
        deleteWithJson(
            "/api/push/register",
            buildJsonObject { put("token", token) },
        )

    // Wave 5 voice rooms - live-caption transcription

    /**
     * POST /api/voice/transcribe {conversationId, requesterId, audioBase64} →
     * { transcript } (≤280 chars). Server: 400 missing fields, 403
     * non-participant, 413 >512K b64, 422 empty, 502 service failure.
     * ASR is slow by nature - this ONE route carries a 60s per-request
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

    // Wave 6 - social graph & discovery (users / safety / blocks / reports / invites / channels / folders)

    /** PUT helper - the folder membership full-replace is the one PUT on the wire. */
    private suspend fun <T> put(path: String, body: JsonObject, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.put(url(path)) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                authHeader()
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** DELETE helper with a `userId` query param (safety unverify / unblock). */
    private suspend fun <T> deleteWithQuery(path: String, userId: String, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(
                url("$path?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")),
            ) { authHeader() }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** DELETE helper with the FULL path (query already embedded - webhook delete's requesterId). */
    private suspend fun <T> deleteAt(path: String, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(url(path)) { authHeader() }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** DELETE helper with a JSON body { userId } (channel unsubscribe). */
    private suspend fun <T> deleteWithBody(path: String, userId: String, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(url(path)) {
                contentType(ContentType.Application.Json)
                setBody(jsonOf("userId" to userId).toString())
                authHeader()
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "The gateway did not confirm the change; refresh before retrying")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** DELETE helper with a caller-shaped JSON body (group kick/leave + scheduled cancel - { requesterId }). */
    private suspend fun <T> deleteWithJson(path: String, body: JsonObject, parse: ((String) -> T)? = null): PulseResult<T> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(url(path)) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                authHeader()
            }
            val text = res.bodyAsText()
            if (res.status.isSuccess()) {
                @Suppress("UNCHECKED_CAST")
                PulseResult.Success((parse?.invoke(text) ?: Unit) as T)
            } else {
                failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "The gateway did not confirm the change; refresh before retrying")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** GET /api/users/{id} → { user: AppUser } (the full profile page payload). */
    suspend fun fullUser(userId: String): PulseResult<FullUserDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(UserEnvelopeDto.serializer(), it).user ?: FullUserDto()
        }

    /** PATCH /api/users/{id} - only non-null fields ride the body ('' clears where the wire allows). */
    suspend fun patchUser(userId: String, body: JsonObject): PulseResult<FullUserDto> =
        patch("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8"), body) {
            PulseJson.decodeFromString(UserEnvelopeDto.serializer(), it).user ?: FullUserDto()
        }

    /** GET /api/users/{id}/stats → { stats } (messages/reactions/photos/voiceNotes/chats/groups). */
    suspend fun userStats(userId: String): PulseResult<UserStatsDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/stats") {
            PulseJson.decodeFromString(StatsEnvelopeDto.serializer(), it).stats ?: UserStatsDto()
        }

    private fun safetyReceipt(text: String, owner: String, peer: String, desired: Boolean? = null): SafetyStateDto {
        val raw = PulseJson.parseToJsonElement(text) as? JsonObject
            ?: throw kotlinx.serialization.SerializationException("Comparison receipt is missing")
        val state = PulseJson.decodeFromString(SafetyStateDto.serializer(), text)
        if (raw["ok"] != JsonPrimitive(true) || raw["safetyReceiptVersion"] != JsonPrimitive(1) ||
            raw["ownerId"] != JsonPrimitive(owner) || raw["peerId"] != JsonPrimitive(peer) ||
            raw["verified"] != JsonPrimitive(state.verified) || (desired != null && state.verified != desired) ||
            !Regex("[0-9]{5}( [0-9]{5}){11}").matches(state.safetyNumber) ||
            (if (state.verified) runCatching { java.time.Instant.parse(state.verifiedAt) }.isFailure else raw["verifiedAt"] != JsonNull))
            throw kotlinx.serialization.SerializationException("Account comparison state was not confirmed")
        return state
    }

    /** GET /api/users/{id}/safety?userId= → 12×5 digits + this viewer's verify stamp. */
    suspend fun safetyState(peerId: String, viewerId: String): PulseResult<SafetyStateDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety?userId=" +
                java.net.URLEncoder.encode(viewerId, "UTF-8"),
        ) {
            safetyReceipt(it, viewerId, peerId)
        }

    /** POST /api/users/{id}/safety { userId } → upsert verification. */
    suspend fun safetyVerify(peerId: String, viewerId: String): PulseResult<VerifyAckDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety",
            jsonOf("userId" to viewerId),
        ) { safetyReceipt(it, viewerId, peerId, true); PulseJson.decodeFromString(VerifyAckDto.serializer(), it) }

    /** DELETE /api/users/{id}/safety?userId= → reset verification (idempotent). */
    suspend fun safetyUnverify(peerId: String, viewerId: String): PulseResult<VerifyAckDto> =
        deleteWithQuery(
            "/api/users/" + java.net.URLEncoder.encode(peerId, "UTF-8") + "/safety",
            viewerId,
        ) { safetyReceipt(it, viewerId, peerId, false); PulseJson.decodeFromString(VerifyAckDto.serializer(), it) }

    /** GET /api/users/{id}/block?userId= → pair state (drives Block/Unblock label). */
    private fun blockReceipt(text: String, actor: String, target: String, desired: Boolean? = null): BlockStateDto {
        val raw = PulseJson.parseToJsonElement(text) as? JsonObject
            ?: throw kotlinx.serialization.SerializationException("Block receipt is missing")
        val state = PulseJson.decodeFromString(BlockStateDto.serializer(), text)
        if (raw["ok"] != JsonPrimitive(true) || raw["blockReceiptVersion"] != JsonPrimitive(1) ||
            raw["actorId"] != JsonPrimitive(actor) || raw["targetId"] != JsonPrimitive(target) ||
            raw["blocked"] != JsonPrimitive(state.blocked) || (desired != null && state.blocked != desired))
            throw kotlinx.serialization.SerializationException("The requested block state was not confirmed")
        return state
    }
    suspend fun blockState(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block?userId=" +
                java.net.URLEncoder.encode(actorId, "UTF-8"),
        ) {
            blockReceipt(it, actorId, targetId)
        }

    /** POST /api/users/{id}/block { userId } - idempotent (actor in the BODY). */
    suspend fun blockUser(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block",
            jsonOf("userId" to actorId),
        ) { blockReceipt(it, actorId, targetId, true) }

    /** DELETE /api/users/{id}/block?userId= - idempotent unblock (POST /unblock does NOT exist). */
    suspend fun unblockUser(targetId: String, actorId: String): PulseResult<BlockStateDto> =
        deleteWithQuery(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/block",
            actorId,
        ) { blockReceipt(it, actorId, targetId, false) }

    /** GET /api/users/{id}/blocks?userId= - self-service list (403 otherwise). */
    suspend fun blockedAccounts(ownerId: String): PulseResult<BlockedPageDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(ownerId, "UTF-8") + "/blocks?userId=" +
                java.net.URLEncoder.encode(ownerId, "UTF-8"),
        ) {
            val raw = PulseJson.parseToJsonElement(it) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Blocked accounts envelope is missing")
            val page = PulseJson.decodeFromString(BlockedPageDto.serializer(), it)
            if (raw["blockListVersion"] != JsonPrimitive(1) || raw["userId"] != JsonPrimitive(ownerId) ||
                raw["blocks"] !is JsonArray || page.blocks.any { row -> row.id.isBlank() || row.id == ownerId ||
                    runCatching { java.time.Instant.parse(row.blockedAt) }.isFailure } ||
                page.blocks.map { row -> row.id }.distinct().size != page.blocks.size)
                throw kotlinx.serialization.SerializationException("Blocked accounts were not confirmed for this owner")
            page
        }

    /** POST /api/users/{id}/report { userId, reason, details? } → 201/200 { reported }. */
    suspend fun reportUser(targetId: String, reporterId: String, reason: String, details: String?): PulseResult<ReportAckDto> =
        post(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/report",
            buildJsonObject {
                put("userId", reporterId)
                put("reason", reason)
                if (details != null) put("details", details)
            },
        ) {
            val raw = PulseJson.parseToJsonElement(it) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Report receipt is missing")
            val receipt = PulseJson.decodeFromString(ReportAckDto.serializer(), it)
            if (raw["ok"] != JsonPrimitive(true) || raw["reported"] != JsonPrimitive(true) ||
                raw["reportReceiptVersion"] != JsonPrimitive(1) || raw["reporterId"] != JsonPrimitive(reporterId) ||
                raw["reportedId"] != JsonPrimitive(targetId) || raw["reason"] != JsonPrimitive(reason) ||
                raw["details"] != JsonPrimitive(details.orEmpty()) || raw["updated"] != JsonPrimitive(receipt.updated) ||
                runCatching { java.time.Instant.parse(raw["reportedAt"]?.jsonPrimitive?.content) }.isFailure)
                throw kotlinx.serialization.SerializationException("The report was not confirmed for this account and content")
            receipt
        }

    /** GET /api/users/{id}/report?userId= - the reporter's OWN prior reasons (hint). */
    suspend fun reportReasons(targetId: String, reporterId: String): PulseResult<ReportReasonsPageDto> =
        get(
            "/api/users/" + java.net.URLEncoder.encode(targetId, "UTF-8") + "/report?userId=" +
                java.net.URLEncoder.encode(reporterId, "UTF-8"),
        ) {
            val raw = PulseJson.parseToJsonElement(it) as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Report history is missing")
            val page = PulseJson.decodeFromString(ReportReasonsPageDto.serializer(), it)
            if (raw["reportHistoryVersion"] != JsonPrimitive(1) || raw["reporterId"] != JsonPrimitive(reporterId) ||
                raw["reportedId"] != JsonPrimitive(targetId) || raw["reasons"] !is JsonArray ||
                page.reasons.map { row -> row.reason }.distinct().size != page.reasons.size ||
                page.reasons.any { row -> row.reason !in setOf("spam", "harassment", "impersonation", "inappropriate", "scam", "other") ||
                    runCatching { java.time.Instant.parse(row.at) }.isFailure })
                throw kotlinx.serialization.SerializationException("Report history was not confirmed for this account")
            page
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

    /** GET /api/channels?userId=[&mine=1] - directory with viewer-aware flags. */
    suspend fun channels(userId: String, mineOnly: Boolean): PulseResult<ChannelsPageDto> =
        get(
            "/api/channels?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                if (mineOnly) "&mine=1" else "",
        ) {
            PulseJson.decodeFromString(ChannelsPageDto.serializer(), it)
        }

    /** POST /api/channels { userId, name, description?, photo? } → 201 { channel }. */
    suspend fun createChannel(userId: String, name: String, description: String?, photo: String?, clientId: String? = null): PulseResult<ChannelCreatedDto> =
        post(
            if (clientId == null) "/api/channels" else "/api/channels/create-v2",
            buildJsonObject {
                put("userId", userId)
                if (clientId != null) put("clientId", clientId)
                put("name", name)
                if (!description.isNullOrBlank()) put("description", description)
                if (!photo.isNullOrBlank()) put("photo", photo)
            },
        ) { raw ->
            val result = PulseJson.decodeFromString(ChannelCreatedDto.serializer(), raw)
            if (clientId != null) {
                val root = requireNotNull(PulseJson.parseToJsonElement(raw) as? JsonObject)
                require(root["clientId"] == JsonPrimitive(clientId) && root["replayed"] in listOf(JsonPrimitive(true), JsonPrimitive(false))) { "Channel creation receipt was not confirmed" }
                val room = result.conversation
                val channel = result.channel
                require(room != null && channel != null && room.id.isNotBlank() && channel.id == room.id &&
                    (root["channel"] as? JsonObject)?.get("conversationId") == JsonPrimitive(room.id) &&
                    room.isGroup && room.isSelf != true && room.broadcastMode == true && room.members.any { it.id == userId } &&
                    channel.isSubscribed && channel.memberCount > 0) { "Channel creation returned an unrelated room" }
            }
            result
        }

    /** POST /api/channels/[id]/subscribe { userId } → { already, memberCount }. */
    suspend fun subscribeChannel(channelId: String, userId: String): PulseResult<SubscribeAckDto> =
        post(
            "/api/channels/" + java.net.URLEncoder.encode(channelId, "UTF-8") + "/subscribe",
            jsonOf("userId" to userId),
        ) {
            val body = PulseJson.parseToJsonElement(it) as? JsonObject
            val result = PulseJson.decodeFromString(SubscribeAckDto.serializer(), it)
            if (body == null || body["already"] != JsonPrimitive(result.already) ||
                body["memberCount"] != JsonPrimitive(result.memberCount) || result.memberCount < 1) {
                throw kotlinx.serialization.SerializationException("Channel membership was not confirmed")
            }
            result
        }

    /** DELETE /api/channels/[id]/subscribe { userId } → last-admin leave is a 403. */
    suspend fun unsubscribeChannel(channelId: String, userId: String): PulseResult<UnsubscribeAckDto> =
        deleteWithBody(
            "/api/channels/" + java.net.URLEncoder.encode(channelId, "UTF-8") + "/subscribe",
            userId,
        ) {
            val body = PulseJson.parseToJsonElement(it) as? JsonObject
            val result = PulseJson.decodeFromString(UnsubscribeAckDto.serializer(), it)
            if (body == null || body["ok"] != JsonPrimitive(true) ||
                body["memberCount"] != JsonPrimitive(result.memberCount) || result.memberCount < 0) {
                throw kotlinx.serialization.SerializationException("Leaving the channel was not confirmed")
            }
            result
        }

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
            folderOf(it, userId).also { folder ->
                require(folder.name == name.trim() && folder.emoji == app.pulse.protocol.folderIconId(emoji)) { "Folder creation was not confirmed." }
            }
        }

    /** PATCH /api/folders/[id] { name?, emoji?, position? } → { folder }. */
    suspend fun patchFolder(folderId: String, name: String?, emoji: String?, position: Int?, userId: String): PulseResult<FolderDto> =
        patch(
            "/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8"),
            buildJsonObject {
                if (name != null) put("name", name)
                if (emoji != null) put("emoji", emoji)
                if (position != null) put("position", position)
            },
        ) {
            folderOf(it, userId, folderId).also { folder ->
                require((name == null || folder.name == name.trim()) &&
                    (emoji == null || folder.emoji == app.pulse.protocol.folderIconId(emoji)) &&
                    (position == null || folder.position == position)) { "Folder changes were not confirmed." }
            }
        }

    /** DELETE /api/folders/[id] → { ok } - membership rows cascade, chats stay. */
    suspend fun deleteFolder(folderId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8"), "") {
            val root = requireFolderScope(it, userId)
            require((root["folderId"] as? kotlinx.serialization.json.JsonPrimitive)?.content == folderId &&
                root["deleted"]?.toString() == "true" && root["ok"]?.toString() == "true") { "Folder deletion was not confirmed." }
            OkDto(ok = true)
        }

    /** PUT /api/folders/[id]/conversations { conversationIds[] } - FULL ordered replace. */
    suspend fun setFolderConversations(folderId: String, conversationIds: List<String>, userId: String): PulseResult<OkDto> =
        put(
            "/api/folders/" + java.net.URLEncoder.encode(folderId, "UTF-8") + "/conversations",
            buildJsonObject {
                put(
                    "conversationIds",
                    kotlinx.serialization.json.JsonArray(conversationIds.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            },
        ) {
            val folder = folderOf(it, userId, folderId)
            require(conversationIds.all { id -> id.isNotBlank() } &&
                folder.conversationIds == conversationIds.map { id -> id.trim() }.distinct()) { "Folder chats were not confirmed." }
            OkDto(ok = true)
        }

    private fun requireFolderScope(text: String, userId: String): kotlinx.serialization.json.JsonObject {
        val root = PulseJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            ?: error("Folder response is missing.")
        val owner = root["userId"] as? kotlinx.serialization.json.JsonPrimitive
        require(userId.isNotBlank() && root["folderVersion"]?.toString() == "1" && owner?.isString == true && owner.content == userId) {
            "Folder ownership was not confirmed."
        }
        return root
    }

    private fun requireFolderRow(folder: FolderDto) {
        require(folder.id.isNotBlank() && folder.name.isNotBlank() && folder.emoji.isNotBlank() &&
            (folder.position ?: -1) >= 0 && folder.conversationIds.all { it.isNotBlank() } &&
            folder.conversationIds.distinct().size == folder.conversationIds.size) { "Invalid folder snapshot." }
    }

    private fun folderOf(text: String, userId: String, folderId: String? = null): FolderDto {
        val root = requireFolderScope(text, userId)
        val row = root["folder"] as? kotlinx.serialization.json.JsonObject ?: error("Folder was not confirmed.")
        require(row["conversationIds"] is kotlinx.serialization.json.JsonArray) { "Folder membership was not confirmed." }
        return PulseJson.decodeFromString(FolderDto.serializer(), row.toString()).also { folder ->
            requireFolderRow(folder)
            require(folderId == null || folder.id == folderId) { "Wrong folder returned." }
        }
    }

    // Wave 7 - collaboration & hub (red packets / whiteboard / kanban / events / reminders / games / tournaments / leaderboard / hub economy)

    private fun redPacketStubOf(json: String): RedPacketStubDto =
        PulseJson.decodeFromString(
            RedPacketStubDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("packet").toString(),
        )

    private fun cardOf(json: String, userId: String, room: String? = null): KanbanCardDto {
        val receipt = PulseJson.decodeFromString(app.pulse.protocol.KanbanCardReceiptDto.serializer(), json)
        require(receipt.boardReceiptVersion == 1 && receipt.userId == userId && receipt.conversationId.isNotBlank() &&
            (room == null || receipt.conversationId == room)) { "Board action owner was not confirmed." }
        return receipt.card.also { requireBoardCard(it, receipt.conversationId) }
    }

    private fun requireBoardCard(card: KanbanCardDto, room: String) {
        require(card.id.isNotBlank() && card.conversationId == room && card.title.isNotBlank() && card.title.length <= 120 &&
            card.column in listOf("todo", "doing", "done") && card.position in 0..2_147_483_647L) { "Board card was not confirmed." }
        java.time.Instant.parse(requireNotNull(card.createdAt))
        java.time.Instant.parse(requireNotNull(card.updatedAt))
    }

    private fun eventOf(json: String): GroupEventDto =
        PulseJson.decodeFromString(
            GroupEventDto.serializer(),
            PulseJson.parseToJsonElement(json).unwrapOrRoot("event").toString(),
        )

    private fun taskOf(json: String, userId: String): HubTaskDto =
        PulseJson.decodeFromString(HubTaskDto.serializer(), PulseJson.parseToJsonElement(json).unwrapOrRoot("task").toString())
            .also { requireHubTask(it, userId) }

    private fun requireHubTask(task: HubTaskDto, userId: String) {
        require(task.ownerId == userId && task.id.isNotBlank() && task.title.isNotBlank() && task.title.length <= 120 &&
            task.status in listOf("todo", "doing", "done")) { "Personal task was not confirmed." }
        java.time.Instant.parse(requireNotNull(task.createdAt)); java.time.Instant.parse(requireNotNull(task.updatedAt))
    }

    private fun requireMarketListing(listing: MarketListingDto, owner: String) {
        require(listing.id.isNotBlank() && listing.title.isNotBlank() && listing.title.length <= 80 &&
            listing.price in 1..100_000 && listing.asset == "PC" && listing.seller.id.isNotBlank() &&
            listing.mine == (listing.seller.id == owner)) { "Market listing could not be verified." }
        java.time.Instant.parse(listing.createdAt)
        val buyer = listing.buyer // local capture permits safe cross-module null checking
        require(if (listing.status == "open") buyer == null else listing.status == "sold" &&
            listing.mine && (buyer == null || (buyer.id.isNotBlank() && buyer.id != listing.seller.id))) {
            "Market listing state could not be verified."
        }
    }

    private fun requireGameRead(json: String, owner: String, expectedRoom: String? = null): String {
        val fields = PulseJson.parseToJsonElement(json) as? JsonObject
        val roomField = fields?.get("conversationId") as? JsonPrimitive
        val ownerField = fields?.get("userId") as? JsonPrimitive
        val room = roomField?.content
        require(fields?.get("gameReadVersion")?.toString() == "1" && ownerField?.isString == true &&
            ownerField.content == owner && roomField?.isString == true && !room.isNullOrBlank() &&
            (expectedRoom == null || room == expectedRoom)) { "Private game state could not be verified. Update the gateway before retrying." }
        return requireNotNull(room)
    }

    private fun matchOf(json: String, id: String): GameDetailDto {
        val fields = (PulseJson.parseToJsonElement(json) as? JsonObject)?.get("match") as? JsonObject
        val result = PulseJson.decodeFromString(GameDetailDto.serializer(), json)
        val match = result.match
        if (fields == null || !fields.keys.containsAll(listOf("board", "turn", "status", "moveCount", "playerXId")) ||
            match.id != id || match.playerXId.isBlank() || match.board.length != 9 || match.board.any { it !in "XO " } ||
            match.moveCount != match.board.count { it != ' ' } || match.turn !in listOf("X", "O") ||
            match.status !in listOf("active", "x_won", "o_won", "draw", "abandoned")) {
            throw kotlinx.serialization.SerializationException("Invalid game state; refresh before playing")
        }
        return result
    }

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

    /** GET /api/redpackets/{id}?userId= - lazy refund settles on first read after expiry. */
    suspend fun redPacket(packetId: String, userId: String): PulseResult<RedPacketDetailDto> =
        get("/api/redpackets/" + java.net.URLEncoder.encode(packetId, "UTF-8") + "?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(RedPacketDetailDto.serializer(), it)
        }

    /** POST /api/redpackets/{id}/grab { userId } - atomic; 409 copy verbatim from the server. */
    suspend fun grabRedPacket(packetId: String, userId: String): PulseResult<RedPacketGrabResultDto> =
        post("/api/redpackets/" + java.net.URLEncoder.encode(packetId, "UTF-8") + "/grab", jsonOf("userId" to userId)) {
            val result = PulseJson.decodeFromString(RedPacketGrabResultDto.serializer(), it)
            if (result.amount !in 1L..2_147_483_647L || result.count <= 0 || result.grabbed !in 1..result.count) {
                throw kotlinx.serialization.SerializationException("Red packet payout was not confirmed. Refresh before retrying")
            }
            result
        }

    /** GET /api/conversations/{id}/whiteboard?requesterId=&since= (since = epoch ms | null = full). */
    suspend fun whiteboard(conversationId: String, requesterId: String, since: Long?): PulseResult<WhiteboardPageDto> {
        val sinceQ = since?.let { "&since=$it" } ?: ""
        return get("/api/conversations/$conversationId/whiteboard?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8") + sinceQ) {
            PulseJson.decodeFromString(WhiteboardPageDto.serializer(), it)
        }
    }

    /** POST /api/conversations/{id}/whiteboard - strokes: 1..40 per call, 2..500 points each, 0..1 coords. */
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
                        s.clientId?.let { put("clientId", it) }
                        s.baseResetAt?.let { put("baseResetAt", it) }
                        put("color", s.color)
                        put("width", s.width)
                        put("points", kotlinx.serialization.json.JsonArray(s.points.map { pt ->
                            kotlinx.serialization.json.JsonArray(pt.map { kotlinx.serialization.json.JsonPrimitive(it) })
                        }))
                    }
                }))
            },
        ) { PulseJson.decodeFromString(WhiteboardPostResultDto.serializer(), it) }

    /** POST /api/conversations/{id}/whiteboard {action:'undo'} - deletes only the caller's latest stroke. */
    suspend fun undoWhiteboardStroke(conversationId: String, requesterId: String): PulseResult<WhiteboardUndoResultDto> =
        post("/api/conversations/$conversationId/whiteboard", jsonOf("action" to "undo", "requesterId" to requesterId)) {
            PulseJson.decodeFromString(WhiteboardUndoResultDto.serializer(), it)
        }

    /** DELETE /api/conversations/{id}/whiteboard?requesterId= - clear all + resetAt watermark. */
    suspend fun clearWhiteboard(conversationId: String, requesterId: String): PulseResult<WhiteboardClearResultDto> {
        if (gateway().isBlank()) return offlineFailure
        return try {
            val res = http.delete(
                url("/api/conversations/$conversationId/whiteboard?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8")),
            )
            val text = res.bodyAsText()
            if (res.status.isSuccess()) PulseResult.Success(PulseJson.decodeFromString(WhiteboardClearResultDto.serializer(), text))
            else failureOf(res.status.value, text, res.headers[HttpHeaders.RetryAfter], res.headers[HttpHeaders.Date])
        } catch (e: kotlinx.serialization.SerializationException) {
            PulseResult.Failure(PulseResult.Failure.Kind.VALIDATION, "bad payload: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, e.message)
        }
    }

    /** GET /api/conversations/{id}/kanban?userId= → { cards } ordered column → position → createdAt. */
    suspend fun kanbanBoard(conversationId: String, userId: String): PulseResult<KanbanPageDto> =
        get("/api/conversations/$conversationId/kanban?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(KanbanPageDto.serializer(), it).also { page ->
                require(page.boardReceiptVersion == 1 && page.userId == userId && page.conversationId == conversationId &&
                    page.cards.size <= 500 && page.cards.map { card -> card.id }.distinct().size == page.cards.size) { "Complete board was not confirmed." }
                page.cards.forEach { card -> requireBoardCard(card, conversationId) }
            }
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
        ) { raw ->
            cardOf(raw, userId, conversationId).also { card ->
                require(card.createdById == userId && card.column == column?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty { "todo" } &&
                    card.assigneeId == assigneeId?.takeIf { it.isNotBlank() } && card.sourceMessageId == messageId?.takeIf { it.isNotBlank() } &&
                    ((title.isNullOrBlank() && !messageId.isNullOrBlank()) || card.title == title?.trim())) { "Task creation was not confirmed." }
            }
        }

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
        ) { raw ->
            cardOf(raw, userId).also { card ->
                require(card.id == cardId && (title == null || card.title == title.trim()) &&
                    (column == null || card.column == column) && (position == null || card.position == position) &&
                    (if (clearAssignee) card.assigneeId == null else assigneeId.isNullOrBlank() || card.assigneeId == assigneeId)) {
                    "Task update was not confirmed."
                }
            }
        }

    /** DELETE /api/kanban/{cardId}?userId= - creator OR group admin (403 copy verbatim). */
    suspend fun deleteKanbanCard(cardId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/kanban/" + java.net.URLEncoder.encode(cardId, "UTF-8"), userId) {
            val receipt = PulseJson.decodeFromString(app.pulse.protocol.KanbanDeleteReceiptDto.serializer(), it)
            require(receipt.boardReceiptVersion == 1 && receipt.userId == userId && receipt.cardId == cardId &&
                receipt.conversationId.isNotBlank() && receipt.ok) { "Task deletion was not confirmed." }
            OkDto(ok = true)
        }

    /** GET /api/conversations/{id}/events?userId= - upcoming asc then past desc, merged ≤50. */
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
        ) { raw ->
            eventOf(raw).also { event ->
                require(event.id.isNotBlank() && event.title == title.trim() && event.createdById == userId &&
                    java.time.Instant.parse(event.startsAt) == java.time.Instant.parse(startsAtIso)) {
                    "Event creation was not confirmed."
                }
            }
        }

    /** DELETE /api/events/{id}?userId= - creator OR group admin. */
    suspend fun deleteEvent(eventId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8"), userId) {
            val receipt = PulseJson.decodeFromString(app.pulse.protocol.EventDeleteReceiptDto.serializer(), it)
            require(receipt.eventReceiptVersion == 1 && receipt.eventId == eventId && receipt.userId == userId && receipt.ok) {
                "Event deletion was not confirmed."
            }
            OkDto(ok = true)
        }

    /** POST /api/events/{id}/rsvp { userId, status: going|maybe|no } → { rsvp, counts }. */
    suspend fun rsvpEvent(eventId: String, userId: String, status: String): PulseResult<RsvpResultDto> =
        post("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8") + "/rsvp", jsonOf("userId" to userId, "status" to status)) {
            PulseJson.decodeFromString(RsvpResultDto.serializer(), it).also { receipt ->
                val rsvp = receipt.rsvp
                require(receipt.eventReceiptVersion == 1 && receipt.eventId == eventId && receipt.userId == userId &&
                    rsvp != null && !rsvp.id.isNullOrBlank() && rsvp.eventId == eventId && rsvp.userId == userId && rsvp.status == status && status in listOf("going", "maybe", "no") &&
                    receipt.counts.going >= 0 && receipt.counts.maybe >= 0 && receipt.counts.no >= 0 &&
                    (when (status) { "going" -> receipt.counts.going; "maybe" -> receipt.counts.maybe; else -> receipt.counts.no }) > 0) {
                    "Your RSVP was not confirmed."
                }
            }
        }

    /** POST /api/events/{id}/checkin { userId } - window +15 XP; 409 outside window is a real failure. */
    suspend fun checkinEvent(eventId: String, userId: String): PulseResult<CheckinResultDto> =
        post("/api/events/" + java.net.URLEncoder.encode(eventId, "UTF-8") + "/checkin", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(CheckinResultDto.serializer(), it).also { receipt ->
                val stamp = receipt.checkIn
                require(receipt.eventReceiptVersion == 1 && receipt.eventId == eventId && receipt.userId == userId &&
                    stamp != null && stamp.id.isNotBlank() && stamp.eventId == eventId && stamp.userId == userId &&
                    stamp.status in listOf("going", "maybe", "no") && receipt.checkedInCount > 0 &&
                    receipt.xpAwarded != receipt.alreadyCheckedIn) { "Your attendance was not confirmed." }
                java.time.Instant.parse(requireNotNull(stamp?.checkedInAt))
            }
        }

    /** GET /api/reminders?userId=[&due=1] - due = remindAt ≤ now && firedAt null. */
    suspend fun reminders(userId: String, dueOnly: Boolean): PulseResult<RemindersPageDto> {
        val base = "/api/reminders?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + if (dueOnly) "&due=1" else ""
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        val items = linkedMapOf<String, ReminderItemDto>()
        do {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val suffix = cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            when (val page = get(base + suffix) { PulseJson.decodeFromString(RemindersPageDto.serializer(), it) }) {
                is PulseResult.Failure -> return page // never adopt a partial list as complete
                is PulseResult.Success -> {
                    page.value.items.forEach { items[it.id] = it }
                    cursor = page.value.nextCursor
                    if (cursor != null && (!seen.add(cursor!!) || seen.size > 1000)) {
                        return PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "Invalid reminder pagination")
                    }
                }
            }
        } while (cursor != null)
        return PulseResult.Success(RemindersPageDto(items.values.toList()))
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

    /** PATCH /api/reminders/{id} { userId } - owner-only resolve; the due loop calls this after the nudge. */
    suspend fun resolveReminder(reminderId: String, userId: String): PulseResult<ReminderResolveDto> =
        patch("/api/reminders/" + java.net.URLEncoder.encode(reminderId, "UTF-8"), jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(ReminderResolveDto.serializer(), it)
        }

    /** DELETE /api/reminders/{id} { userId } - owner-only cancel. */
    suspend fun deleteReminder(reminderId: String, userId: String): PulseResult<OkDto> =
        deleteWithBody("/api/reminders/" + java.net.URLEncoder.encode(reminderId, "UTF-8"), userId) {
            PulseJson.decodeFromString(OkDto.serializer(), it)
        }

    // quick phrases (F-MS-29)

    /** GET /api/users/{id}/phrases → { phrases: [{id,text,position}] } (position asc). */
    suspend fun phrases(userId: String): PulseResult<PhrasesPageDto> =
        get("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases") {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject
            require(root?.get("phrases") is JsonArray) { "Quick phrases were not confirmed." }
            PulseJson.decodeFromString(PhrasesPageDto.serializer(), it).also { page ->
                require(page.phrases.all { row -> row.id.isNotBlank() && row.text.isNotBlank() && row.position >= 0 } &&
                    page.phrases.map { row -> row.id }.distinct().size == page.phrases.size) { "Invalid quick-phrase list." }
            }
        }

    suspend fun createPhrase(userId: String, text: String): PulseResult<PhraseEnvelopeDto> =
        post("/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases", jsonOf("text" to text)) {
            // Decode the envelope once. Unwrapping first silently decoded a null
            // phrase, which the repository used to turn into a blank-ID success.
            PulseJson.decodeFromString(PhraseEnvelopeDto.serializer(), it).also { receipt ->
                val phrase = requireNotNull(receipt.phrase) { "Phrase creation was not confirmed." }
                require(phrase.id.isNotBlank() && phrase.text == text.trim() && phrase.text.isNotBlank() && phrase.position >= 0) {
                    "Phrase creation was not confirmed."
                }
            }
        }

    suspend fun deletePhrase(userId: String, phraseId: String): PulseResult<OkDto> =
        deleteWithJson(
            "/api/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/phrases?phraseId=" + java.net.URLEncoder.encode(phraseId, "UTF-8"),
            buildJsonObject { },
        ) {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject ?: error("Phrase receipt is missing.")
            require(root["ok"]?.toString() == "true" && root["deleted"]?.toString() == "true" &&
                (root["userId"] as? JsonPrimitive)?.content == userId &&
                (root["phraseId"] as? JsonPrimitive)?.content == phraseId) { "Phrase deletion was not confirmed." }
            OkDto(ok = true)
        }

    /** POST /api/games { userId, conversationId, game?='tictactoe', opponentId? } → { match, message }. */
    suspend fun createGame(userId: String, conversationId: String, opponentId: String?, clientId: String? = null): PulseResult<GameMatchCreateResultDto> =
        post(
            if (clientId == null) "/api/games" else "/api/games/create",
            buildJsonObject {
                if (clientId != null) put("clientId", clientId)
                put("userId", userId)
                put("conversationId", conversationId)
                put("game", "tictactoe")
                if (!opponentId.isNullOrBlank()) put("opponentId", opponentId)
            },
        ) {
            val result = PulseJson.decodeFromString(GameMatchCreateResultDto.serializer(), it)
            val match = result.match ?: throw kotlinx.serialization.SerializationException("Challenge was not confirmed")
            matchOf(it, match.id)
            if (match.id.isBlank() || match.conversationId != conversationId || match.playerXId != userId || (match.playerOId != opponentId && !(clientId != null && result.replayed && opponentId == null)) ||
                result.message?.id.isNullOrBlank() || result.message?.conversationId != conversationId || result.message?.senderId != userId || result.message?.kind != "game") {
                throw kotlinx.serialization.SerializationException("Challenge was not confirmed; check the chat before retrying")
            }
            if (clientId != null && result.clientId != clientId) throw kotlinx.serialization.SerializationException("Creation receipt was not confirmed; retry the same instructions")
            if (clientId != null && PulseJson.decodeFromString(GamePayloadDto.serializer(), result.message?.payload.objectPayloadText().orEmpty()).matchId != match.id) {
                throw kotlinx.serialization.SerializationException("Challenge carrier does not match its board")
            }
            result
        }

    /** GET /api/games?conversationId= → { matches } newest 25 (list page for the room games view). */
    suspend fun games(conversationId: String, userId: String): PulseResult<GamesPageDto> =
        get("/api/games?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "&userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            requireGameRead(it, userId, conversationId)
            require(PulseJson.parseToJsonElement(it).jsonObject["matches"] is JsonArray)
            PulseJson.decodeFromString(GamesPageDto.serializer(), it).also { page ->
                require(page.matches.map { row -> row.id }.distinct().size == page.matches.size)
                page.matches.forEach { row -> require(row.id.isNotBlank() && row.conversationId == conversationId) }
            }
        }

    /** GET /api/games/{id} → { match, playerX, playerO }. */
    suspend fun game(matchId: String, userId: String): PulseResult<GameDetailDto> =
        get("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8") + "?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            val room = requireGameRead(it, userId)
            matchOf(it, matchId).also { row -> require(row.match.conversationId == room) }
        }

    /** POST /api/games/{id}/move { userId, cell 0..8 } - 409s for turn/occupied/race surface verbatim. */
    suspend fun gameMove(matchId: String, userId: String, cell: Int): PulseResult<GameDetailDto> =
        post("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8") + "/move", jsonOf("userId" to userId, "cell" to cell)) {
            val result = matchOf(it, matchId)
            val side = if (result.match.playerXId == userId) 'X' else if (result.match.playerOId == userId) 'O' else null
            if (cell !in 0..8 || side == null || result.match.board[cell] != side) throw kotlinx.serialization.SerializationException("Move was not confirmed; refresh the board")
            result
        }

    /** POST /api/games/{id}/join { userId } - first-come O seat; 409 when taken. */
    suspend fun joinGame(matchId: String, userId: String): PulseResult<GameDetailDto> =
        post("/api/games/" + java.net.URLEncoder.encode(matchId, "UTF-8") + "/join", jsonOf("userId" to userId)) {
            val result = matchOf(it, matchId)
            if (result.match.playerOId != userId) throw kotlinx.serialization.SerializationException("Seat was not confirmed; refresh the board")
            result
        }

    /** POST /api/tournaments { userId, conversationId, name ≤40 } → { tournament, message }. */
    suspend fun createTournament(userId: String, conversationId: String, name: String, clientId: String? = null): PulseResult<TournamentCreateResultDto> =
        post(if (clientId == null) "/api/tournaments" else "/api/tournaments/create", jsonOf("clientId" to clientId, "userId" to userId, "conversationId" to conversationId, "name" to name, "game" to "tictactoe")) {
            val result = PulseJson.decodeFromString(TournamentCreateResultDto.serializer(), it)
            if (result.tournament?.id.isNullOrBlank() || result.tournament?.name != name.trim() || result.message?.id.isNullOrBlank() ||
                result.message?.kind != "tournament" || result.message?.conversationId != conversationId || result.message?.senderId != userId) {
                throw kotlinx.serialization.SerializationException("Season was not confirmed; check the chat before retrying")
            }
            if (clientId != null && result.clientId != clientId) throw kotlinx.serialization.SerializationException("Creation receipt was not confirmed; retry the same instructions")
            if (clientId != null && PulseJson.decodeFromString(TournamentPayloadDto.serializer(), result.message?.payload.objectPayloadText().orEmpty()).tournamentId != result.tournament?.id) {
                throw kotlinx.serialization.SerializationException("Season carrier does not match its tournament")
            }
            result
        }

    /** GET /api/tournaments?conversationId= → { tournaments } newest 5 with playerCount. */
    suspend fun tournaments(conversationId: String, userId: String): PulseResult<TournamentsPageDto> =
        get("/api/tournaments?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
            "&userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            requireGameRead(it, userId, conversationId)
            if ((PulseJson.parseToJsonElement(it) as? JsonObject)?.get("tournaments") !is kotlinx.serialization.json.JsonArray) {
                throw kotlinx.serialization.SerializationException("Tournament list was not returned")
            }
            val result = PulseJson.decodeFromString(TournamentsPageDto.serializer(), it)
            if (result.tournaments.map { row -> row.id }.distinct().size != result.tournaments.size || result.tournaments.any { season -> season.id.isBlank() || season.conversationId != conversationId || season.joined == null || season.status !in listOf("running", "finished") }) {
                throw kotlinx.serialization.SerializationException("Invalid tournament list")
            }
            result
        }

    /** GET /api/tournaments/{id} → { tournament } standings (points → wins → joinedAt). */
    suspend fun tournament(tournamentId: String, userId: String): PulseResult<TournamentSummaryDto> =
        get("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8") + "?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            val room = requireGameRead(it, userId)
            val season = PulseJson.parseToJsonElement(it).jsonObject["tournament"] as? JsonObject
            require(season?.get("entries") is JsonArray)
            tournamentOf(it).also { row ->
                require(row.id == tournamentId && row.conversationId == room && row.status in listOf("running", "finished"))
            }
        }

    /** PATCH /api/tournaments/{id} { userId, status:'finished' } - creator/admin, idempotent. */
    suspend fun finishTournament(tournamentId: String, userId: String): PulseResult<TournamentSummaryDto> =
        patch("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8"), jsonOf("userId" to userId, "status" to "finished")) {
            val result = tournamentOf(it)
            if (result.id != tournamentId || result.status != "finished") throw kotlinx.serialization.SerializationException("Season closure was not confirmed")
            result
        }

    /** POST /api/tournaments/{id}/join { userId } - idempotent upsert. */
    suspend fun joinTournament(tournamentId: String, userId: String): PulseResult<TournamentJoinResultDto> =
        post("/api/tournaments/" + java.net.URLEncoder.encode(tournamentId, "UTF-8") + "/join", jsonOf("userId" to userId)) {
            val result = PulseJson.decodeFromString(TournamentJoinResultDto.serializer(), it)
            if (result.entry?.id.isNullOrBlank() || result.entry?.tournamentId != tournamentId || result.entry?.userId != userId) {
                throw kotlinx.serialization.SerializationException("Season membership was not confirmed")
            }
            result
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

    /** GET /api/hub/wallet?userId=[&ledger=30] - upserts a zero wallet; ledger desc. */
    suspend fun wallet(userId: String, ledger: Int = 30): PulseResult<WalletPageDto> =
        get("/api/hub/wallet?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + "&ledger=$ledger") {
            requireWalletAck(it, userId)
            require((PulseJson.parseToJsonElement(it) as? JsonObject)?.get("ledger") is kotlinx.serialization.json.JsonArray) { "Wallet history was not confirmed." }
            PulseJson.decodeFromString(WalletPageDto.serializer(), it)
        }

    /** POST /api/hub/wallet/checkin { userId } - 409 body carries { error, wallet }. */
    suspend fun checkinWallet(userId: String): PulseResult<CheckinWalletResultDto> =
        post("/api/hub/wallet/checkin", jsonOf("userId" to userId)) {
            requireWalletAck(it, userId)
            val result = PulseJson.decodeFromString(CheckinWalletResultDto.serializer(), it)
            if (result.streak <= 0 || result.wallet.streak != result.streak || result.wallet.lastCheckIn.isNullOrBlank() ||
                result.reward != 25L + minOf(result.streak - 1, 10) * 2L) {
                throw kotlinx.serialization.SerializationException("Daily reward was not confirmed; refresh the wallet before retrying")
            }
            result
        }

    /** POST /api/hub/wallet/transfer { userId, toUsername, amount, note? } - handle lowercased/@-stripped client-side too. */
    suspend fun transferCoins(userId: String, toUsername: String, amount: Long, note: String?, clientId: String? = null): PulseResult<TransferResultDto> =
        post(
            if (clientId == null) "/api/hub/wallet/transfer" else "/api/hub/wallet/operations/transfer",
            buildJsonObject {
                if (clientId != null) put("clientId", clientId)
                put("userId", userId)
                put("toUsername", toUsername.trim().removePrefix("@").lowercase(java.util.Locale.ROOT))
                put("amount", amount)
                if (!note.isNullOrBlank()) put("note", note)
            },
        ) {
            requireWalletAck(it, userId)
            val result = PulseJson.decodeFromString(TransferResultDto.serializer(), it)
            if (clientId != null && result.clientId != clientId) throw kotlinx.serialization.SerializationException("Unconfirmed wallet operation; retry the same instructions")
            if (result.to.id.isBlank() || result.to.id == userId) throw kotlinx.serialization.SerializationException("Unconfirmed transfer recipient; refresh the ledger before retrying")
            result
        }

    /** GET /api/hub/swap → { rates, stats }. */
    suspend fun swapRates(): PulseResult<SwapPageDto> =
        get("/api/hub/swap") { PulseJson.decodeFromString(SwapPageDto.serializer(), it) }

    /** POST /api/hub/swap { userId, direction: pc2gem|gem2pc, amount }. */
    suspend fun swap(userId: String, direction: String, amount: Long, clientId: String? = null): PulseResult<SwapResultDto> =
        post(if (clientId == null) "/api/hub/swap" else "/api/hub/wallet/operations/swap", jsonOf("userId" to userId, "direction" to direction, "amount" to amount, "clientId" to clientId)) {
            requireWalletAck(it, userId)
            val result = PulseJson.decodeFromString(SwapResultDto.serializer(), it)
            if (clientId != null && result.clientId != clientId) throw kotlinx.serialization.SerializationException("Unconfirmed wallet operation; retry the same instructions")
            result
        }

    private fun requireWalletAck(raw: String, userId: String) {
        val root = PulseJson.parseToJsonElement(raw) as? JsonObject
        val wallet = root?.get("wallet") as? JsonObject
        if (wallet == null || !wallet.keys.containsAll(listOf("userId", "coins", "gems"))) {
            throw kotlinx.serialization.SerializationException("Unconfirmed wallet outcome; refresh the ledger before retrying")
        }
        val parsed = PulseJson.decodeFromString(app.pulse.protocol.WalletDto.serializer(), wallet.toString())
        if (parsed.userId != userId || parsed.coins !in 0L..2_147_483_647L || parsed.gems !in 0L..2_147_483_647L) {
            throw kotlinx.serialization.SerializationException("Invalid wallet acknowledgement; refresh the ledger before retrying")
        }
    }

    /** GET /api/hub/tasks?userId= → { tasks } ordered doing → todo → done. */
    suspend fun hubTasks(userId: String): PulseResult<HubTasksPageDto> =
        get("/api/hub/tasks?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(HubTasksPageDto.serializer(), it).also { page ->
                require(page.userId == userId && page.tasks.map { task -> task.id }.distinct().size == page.tasks.size) { "Task history owner was not confirmed." }
                page.tasks.forEach { task -> requireHubTask(task, userId) }
            }
        }

    /** POST /api/hub/tasks { userId, title 1..120, status? } → { task }. */
    suspend fun createHubTask(userId: String, title: String, status: String? = null): PulseResult<HubTaskDto> =
        post("/api/hub/tasks", jsonOf("userId" to userId, "title" to title, "status" to (status ?: "todo"))) {
            taskOf(it, userId).also { task -> require(task.title == title.trim() && task.status == (status ?: "todo")) { "Task creation was not confirmed." } }
        }

    /** PATCH /api/hub/tasks/{id} { userId, title?, status? } - owner-only. */
    suspend fun updateHubTask(taskId: String, userId: String, title: String?, status: String?): PulseResult<HubTaskDto> =
        patch(
            "/api/hub/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"),
            buildJsonObject {
                put("userId", userId)
                if (title != null) put("title", title)
                if (status != null) put("status", status)
            },
        ) { taskOf(it, userId).also { task -> require(task.id == taskId && (title == null || task.title == title.trim()) && (status == null || task.status == status)) { "Task update was not confirmed." } } }

    /** DELETE /api/hub/tasks/{id}?userId= - owner-only. */
    suspend fun deleteHubTask(taskId: String, userId: String): PulseResult<OkDto> =
        deleteWithQuery("/api/hub/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"), userId) {
            val row = PulseJson.parseToJsonElement(it).jsonObject
            require(row["ok"] == JsonPrimitive(true) && row["taskId"] == JsonPrimitive(taskId) && row["userId"] == JsonPrimitive(userId)) { "Task deletion was not confirmed." }
            OkDto(ok = true)
        }

    /** GET /api/hub/market?userId= - open listings + viewer's own (any status), ≤60. */
    suspend fun market(userId: String): PulseResult<MarketPageDto> =
        get("/api/hub/market?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(MarketPageDto.serializer(), it).also { page ->
                require(page.marketVersion == 1 && page.userId == userId && page.listings.map { row -> row.id }.distinct().size == page.listings.size) {
                    "Market ownership could not be verified. Update the gateway before retrying."
                }
                page.listings.forEach { row -> requireMarketListing(row, userId) }
            }
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
        ) {
            val receipt = PulseJson.decodeFromString(app.pulse.protocol.MarketListingEnvelopeDto.serializer(), it)
            require(receipt.marketVersion == 1 && receipt.userId == userId) { "Listing ownership was not confirmed." }
            receipt.listing.also { row ->
                requireMarketListing(row, userId)
                require(row.mine && row.status == "open" && row.title == title.trim() && row.price == price) { "Listing details were not confirmed." }
            }
        }

    /** POST /api/hub/market/{id}/buy { userId } → { ok, wallet } (atomic escrow-less debit/credit). */
    suspend fun buyListing(listingId: String, userId: String): PulseResult<MarketBuyResultDto> =
        post("/api/hub/market/" + java.net.URLEncoder.encode(listingId, "UTF-8") + "/buy", jsonOf("userId" to userId)) {
            requireWalletAck(it, userId)
            val result = PulseJson.decodeFromString(MarketBuyResultDto.serializer(), it)
            if (result.marketVersion != 1 || result.userId != userId || result.listingId != listingId || !result.ok) throw kotlinx.serialization.SerializationException("Purchase was not confirmed; refresh the market before retrying")
            result
        }

    /** GET /api/hub/logs?limit=&kind= → { logs } desc; `meta` stays a raw JSON string. */
    suspend fun hubLogs(userId: String, limit: Int = 60, kind: String? = null): PulseResult<HubLogsPageDto> {
        val q = StringBuilder("?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") + "&limit=$limit")
        if (!kind.isNullOrBlank()) q.append("&kind=").append(java.net.URLEncoder.encode(kind, "UTF-8"))
        return get("/api/hub/logs$q") {
            PulseJson.decodeFromString(HubLogsPageDto.serializer(), it).also { page ->
                require(page.activityVersion == 1 && page.userId == userId && page.logs.all { row -> row.user?.id == userId }) {
                    "Private activity could not be verified. Update the gateway before retrying."
                }
            }
        }
    }

    private fun requireAppIdentity(version: Int, app: String, owner: String, expectedApp: String, expectedOwner: String) {
        require(version == 1 && app == expectedApp && owner == expectedOwner) { "App response was not confirmed for this account. Update the gateway before retrying." }
    }
    private fun requireInstallState(installed: Boolean, status: String?, count: Int) {
        require(count >= 0 && if (installed) status == "connected" && count > 0 else status == null) { "Connection state was not confirmed." }
    }
    private fun requireAppCommunity(row: AppCommunityDto, appId: String, userId: String) {
        requireAppIdentity(row.appVersion, row.appId, row.userId, appId, userId)
        val room = row.conversation
        require(row.memberCount >= 0)
        if (room == null) { require(!row.joined && row.memberCount == 0L); return }
        require(room.id.isNotBlank() && room.isGroup && room.myDraft == null && room.inviteCode == null)
        require(room.members.all { it.id.isNotBlank() } && room.members.map { it.id }.distinct().size == room.members.size)
        require(if (row.joined) room.members.size.toLong() == row.memberCount && room.members.any { it.id == userId } else room.members.isEmpty()) {
            "Community membership was not confirmed."
        }
    }

    /** GET /api/hub/apps/{appId}/install?userId= - appId is the numeric matrix id as a string. */
    suspend fun appInstallState(appId: String, userId: String): PulseResult<AppInstallStateDto> =
        get("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(AppInstallStateDto.serializer(), it).also { row ->
                requireAppIdentity(row.appVersion, row.appId, row.userId, appId, userId)
                requireInstallState(row.installed, row.status, row.installs)
                if (row.installed) java.time.Instant.parse(requireNotNull(row.installedAt))
                require(row.installers.size <= 6 && row.installers.size <= row.installs && row.installers.all { person -> person.id.isNotBlank() } && row.installers.map { person -> person.id }.distinct().size == row.installers.size)
            }
        }

    /** POST /api/hub/apps/{appId}/install { userId } - idempotent connect. */
    suspend fun installApp(appId: String, userId: String): PulseResult<AppInstallResultDto> =
        post("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(AppInstallResultDto.serializer(), it).also { row ->
                requireAppIdentity(row.appVersion, row.appId, row.userId, appId, userId)
                requireInstallState(row.installed, row.status, row.installs)
                require(row.installed == true) { "Connection change was not confirmed." }
            }
        }

    /** DELETE /api/hub/apps/{appId}/install { userId } - hard remove. */
    suspend fun uninstallApp(appId: String, userId: String): PulseResult<AppInstallResultDto> =
        deleteWithBody("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/install", userId) {
            PulseJson.decodeFromString(AppInstallResultDto.serializer(), it).also { row ->
                requireAppIdentity(row.appVersion, row.appId, row.userId, appId, userId)
                requireInstallState(row.installed, row.status, row.installs)
                require(row.installed == false) { "Connection change was not confirmed." }
            }
        }

    /** GET /api/hub/apps/{appId}/community?userId= → { conversation|null, memberCount, joined }. */
    suspend fun appCommunity(appId: String, userId: String): PulseResult<AppCommunityDto> =
        get("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/community?userId=" + java.net.URLEncoder.encode(userId, "UTF-8")) {
            PulseJson.decodeFromString(AppCommunityDto.serializer(), it).also { row -> requireAppCommunity(row, appId, userId) }
        }

    /** POST /api/hub/apps/{appId}/community { userId } - auto-provisions the group; founder = admin. */
    suspend fun joinAppCommunity(appId: String, userId: String): PulseResult<AppCommunityDto> =
        post("/api/hub/apps/" + java.net.URLEncoder.encode(appId, "UTF-8") + "/community", jsonOf("userId" to userId)) {
            PulseJson.decodeFromString(AppCommunityDto.serializer(), it).also { row ->
                requireAppCommunity(row, appId, userId)
                require(row.joined && row.conversation != null) { "Community membership was not confirmed." }
            }
        }

    // REM-A group governance (web group-info-sheet parity)

    /**
     * PATCH /api/conversations/{id} { requesterId, name?/broadcast?/photo?/screenPrivacy? }
     * Group meta changes - name/broadcast/photo are ADMIN-only server-side,
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
            val fields = PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation") as? JsonObject
                ?: throw kotlinx.serialization.SerializationException("Room settings detail is missing")
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                fields.toString(),
            ).also { result ->
                if (result.id != conversationId || result.members.none { member -> member.id == requesterId } ||
                    ((name != null || photo != null || broadcast != null) && !result.isGroup) ||
                    (name != null && ((fields["name"] as? JsonPrimitive)?.isString != true || result.name.isNullOrBlank())) ||
                    (photo != null && (!fields.containsKey("photo") ||
                        (fields["photo"] != JsonNull && (fields["photo"] as? JsonPrimitive)?.isString != true))) ||
                    (broadcast != null && result.broadcastMode == null) ||
                    (screenPrivacy != null && result.screenPrivacy == null)) {
                    throw kotlinx.serialization.SerializationException("Room settings were not confirmed")
                }
            }
        }

    /** POST /api/conversations/{id}/members { requesterId, userIds[] } - admin-only add. */
    suspend fun addMembers(conversationId: String, requesterId: String, userIds: List<String>): PulseResult<MembersAddedDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            buildJsonObject {
                put("requesterId", requesterId)
                put("userIds", kotlinx.serialization.json.JsonArray(userIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        ) {
            val body = PulseJson.parseToJsonElement(it) as? JsonObject
            val result = PulseJson.decodeFromString(MembersAddedDto.serializer(), it)
            if (body?.get("added") !is JsonArray || result.added.distinct().size != result.added.size ||
                result.added.any { id -> id !in userIds }) {
                throw kotlinx.serialization.SerializationException("Member additions were not confirmed")
            }
            result
        }

    /** PATCH /api/conversations/{id}/members { requesterId, userId, role } - promote/demote (admin-only). */
    suspend fun setMemberRole(conversationId: String, requesterId: String, userId: String, role: String): PulseResult<Unit> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            jsonOf("requesterId" to requesterId, "userId" to userId, "role" to role),
        )

    /**
     * PATCH /api/conversations/{id}/members/{userId} { requesterId, action } -
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
            ).also { result ->
                if (result.id != conversationId) throw kotlinx.serialization.SerializationException("Role response belongs to another room")
            }
        }

    /** DELETE /api/conversations/{id}/members/{userId} { requesterId } - kick a NON-admin member. */
    suspend fun kickMember(conversationId: String, requesterId: String, userId: String): PulseResult<GroupMutationAckDto> =
        deleteWithJson(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members/" +
                java.net.URLEncoder.encode(userId, "UTF-8"),
            jsonOf("requesterId" to requesterId),
        ) {
            PulseJson.decodeFromString(GroupMutationAckDto.serializer(), it).also { result ->
                if (!result.ok) throw kotlinx.serialization.SerializationException("Membership change was not confirmed")
            }
        }

    /**
     * DELETE /api/conversations/{id}/members { requesterId } - LEAVE group.
     * Self-removal path with last-admin succession; kicking yourself on the
     * member-scoped route is a 400 with a "Leave group" pointer (web parity).
     */
    suspend fun leaveGroup(conversationId: String, requesterId: String): PulseResult<GroupMutationAckDto> =
        deleteWithJson(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/members",
            jsonOf("requesterId" to requesterId),
        ) {
            PulseJson.decodeFromString(GroupMutationAckDto.serializer(), it).also { result ->
                if (!result.ok) throw kotlinx.serialization.SerializationException("Membership change was not confirmed")
            }
        }

    /** POST /api/conversations/{id}/invite { requesterId, regenerate? } - admin-only lazy create/regenerate. */
    suspend fun createInvite(conversationId: String, requesterId: String, regenerate: Boolean): PulseResult<InviteCodeDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/invite",
            jsonOf("requesterId" to requesterId, "regenerate" to regenerate),
        ) {
            val result = PulseJson.decodeFromString(InviteCodeDto.serializer(), it)
            val code = app.pulse.core.link.PulseDeepLink.inviteCode(result.inviteCode)
                ?.takeIf { normalized -> result.inviteCode.trim().equals(normalized, ignoreCase = true) }
                ?: throw kotlinx.serialization.SerializationException("Invite link was not confirmed")
            result.copy(inviteCode = code)
        }

    /** PATCH /api/conversations/{id}/disappearing { userId, ttlSeconds } - any participant; presets 0/1d/7d/30d. */
    suspend fun setDisappearingTtl(conversationId: String, userId: String, ttlSeconds: Int): PulseResult<ConversationSummaryDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/disappearing",
            jsonOf("userId" to userId, "ttlSeconds" to ttlSeconds),
        ) {
            PulseJson.decodeFromString(
                ConversationSummaryDto.serializer(),
                PulseJson.parseToJsonElement(it).unwrapOrRoot("conversation").toString(),
            ).also { result ->
                val applied = result.ttlSeconds
                if (result.id != conversationId || result.members.none { member -> member.id == userId } || applied == null || applied !in listOf(0, 86_400, 604_800, 2_592_000)) {
                    throw kotlinx.serialization.SerializationException("Disappearing-message setting was not confirmed")
                }
            }
        }

    /** PATCH /api/conversations/{id}/slow-mode { userId, seconds } - admin-only; presets 0/5/10/30/60/300. */
    suspend fun setSlowMode(conversationId: String, userId: String, seconds: Int): PulseResult<SlowModeAckDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/slow-mode",
            jsonOf("userId" to userId, "seconds" to seconds),
        ) {
            val body = PulseJson.parseToJsonElement(it) as? JsonObject
            val result = PulseJson.decodeFromString(SlowModeAckDto.serializer(), it)
            if (!result.ok || body?.get("slowModeSeconds") != JsonPrimitive(seconds)) {
                throw kotlinx.serialization.SerializationException("Slow-mode setting was not confirmed")
            }
            result
        }

    // REM-A scheduled sends (F-MS-18)

    private fun scheduledScope(text: String, owner: String, room: String? = null): JsonObject {
        val root = PulseJson.parseToJsonElement(text) as? JsonObject ?: error("Scheduled response is missing.")
        val user = root["userId"] as? JsonPrimitive
        val conversation = root["conversationId"] as? JsonPrimitive
        require(root["scheduleVersion"]?.toString() == "1" && owner.isNotBlank() && user?.isString == true && user.content == owner &&
            conversation?.isString == true && conversation.content.isNotBlank() && (room == null || conversation.content == room)) {
            "Scheduled-message ownership was not confirmed."
        }
        return root
    }
    private fun validateScheduled(row: ScheduledItemDto, room: String) {
        require(row.id.isNotBlank() && row.conversationId == room && row.content.isNotBlank() && row.sentAt == null &&
            runCatching { java.time.Instant.parse(row.scheduledAt) }.isSuccess &&
            (row.cancelledAt == null || runCatching { java.time.Instant.parse(row.cancelledAt) }.isSuccess)) { "Invalid scheduled message." }
    }
    /** Complete-or-error scan; never publish the old endpoint's silently truncated first 50 rows. */
    suspend fun scheduledMessages(conversationId: String, userId: String): PulseResult<ScheduledPageDto> {
        val rows = mutableListOf<ScheduledItemDto>()
        val ids = mutableSetOf<String>(); val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var characters = 0
        repeat(100) {
            val path = "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
                "/scheduled?userId=" + java.net.URLEncoder.encode(userId, "UTF-8") +
                (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            when (val result = get(path) { text ->
                characters += text.length
                require(characters <= 4 * 1024 * 1024) { "Scheduled list is too large to confirm safely." }
                val root = scheduledScope(text, userId, conversationId)
                require(root["items"] is JsonArray && root["hasMore"]?.toString() in setOf("true", "false")) { "Scheduled list is incomplete." }
                val page = PulseJson.decodeFromString(ScheduledPageDto.serializer(), text)
                require(page.items.size <= 50) { "Scheduled page exceeded its limit." }
                page.items.forEach { validateScheduled(it, conversationId) }
                val more = root["hasMore"]?.toString() == "true"
                val next = root["nextCursor"] as? JsonPrimitive
                require(if (more) next?.isString == true && next.content.isNotBlank() && page.items.isNotEmpty() else root["nextCursor"] == kotlinx.serialization.json.JsonNull) {
                    "Scheduled list cursor was not confirmed."
                }
                page.items to if (more) next?.content else null
            }) {
                is PulseResult.Failure -> return result
                is PulseResult.Success -> {
                    val (page, next) = result.value
                    if (page.any { !ids.add(it.id) } || (next != null && !cursors.add(next))) {
                        return PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "Scheduled list changed while loading. Retry.")
                    }
                    rows.addAll(page)
                    if (next == null) return PulseResult.Success(ScheduledPageDto(items = rows))
                    cursor = next
                }
            }
        }
        return PulseResult.Failure(PulseResult.Failure.Kind.SERVER, "Scheduled list exceeded the safe page limit. No partial list was applied.")
    }

    suspend fun scheduleMessage(conversationId: String, senderId: String, content: String, scheduledAtIso: String, clientId: String): PulseResult<ScheduledItemDto> =
        post("/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/scheduled/create-v2",
            jsonOf("senderId" to senderId, "content" to content, "scheduledAt" to scheduledAtIso, "clientId" to clientId)) {
            val root = scheduledScope(it, senderId, conversationId)
            val echoed = root["clientId"] as? JsonPrimitive
            require(echoed?.isString == true && echoed.content == clientId && root["replayed"]?.toString() in setOf("true", "false")) {
                "Scheduled request identity was not confirmed. Retry the same details."
            }
            val item = root["item"] as? JsonObject ?: error("Scheduled message was not confirmed.")
            PulseJson.decodeFromString(ScheduledItemDto.serializer(), item.toString()).also { row ->
                validateScheduled(row, conversationId)
                require(row.content == content.trim() && row.cancelledAt == null && row.cancelledReason == null &&
                    java.time.Instant.parse(row.scheduledAt).toEpochMilli() == java.time.Instant.parse(scheduledAtIso).toEpochMilli()) {
                    "The requested scheduled message was not confirmed."
                }
            }
        }

    suspend fun cancelScheduled(scheduledId: String, requesterId: String): PulseResult<OkDto> =
        deleteWithJson("/api/scheduled/" + java.net.URLEncoder.encode(scheduledId, "UTF-8"), jsonOf("requesterId" to requesterId)) {
            val root = scheduledScope(it, requesterId)
            require(root["ok"]?.toString() == "true" && root["cancelled"]?.toString() == "true" &&
                (root["scheduledId"] as? JsonPrimitive)?.content == scheduledId) { "Scheduled cancellation was not confirmed." }
            OkDto(ok = true)
        }

    // R2-A round-2 parity (automations · webhooks · AI recap · per-viewer privacy)

    /** GET /api/conversations/{id}/automations?userId= - participant-only rows. */
    suspend fun automations(conversationId: String, userId: String): PulseResult<AutomationsPageDto> =
        get(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
                "/automations?userId=" + java.net.URLEncoder.encode(userId, "UTF-8"),
        ) {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject ?: error("Automation list is missing.")
            require(root["automations"] is JsonArray) { "Automation list was not confirmed." }
            (root["automations"] as JsonArray).forEach { row ->
                require((row as? JsonObject)?.get("enabled")?.toString() in setOf("true", "false")) { "Automation state was not confirmed." }
            }
            PulseJson.decodeFromString(AutomationsPageDto.serializer(), it).also { page ->
                require(page.automations.map { it.id }.distinct().size == page.automations.size)
                page.automations.forEach { row -> requireAutomation(row); require(row.conversationId == conversationId) }
            }
        }

    /** POST /api/conversations/{id}/automations { userId, trigger, reply } - admin-only → 201 { automation }. */
    suspend fun createAutomation(conversationId: String, userId: String, trigger: String, reply: String): PulseResult<AutomationEnvelopeDto> =
        post(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/automations",
            jsonOf("userId" to userId, "trigger" to trigger, "reply" to reply),
        ) {
            confirmedAutomation(it).also { receipt ->
                val row = receipt.automation
                require(row.conversationId == conversationId && row.trigger == trigger.trim() && row.reply == reply.trim() && row.enabled) { "Automation creation was not confirmed." }
            }
        }

    /** PATCH /api/automations/{id} { userId, enabled?/trigger? } - admin-only → { automation }. */
    suspend fun patchAutomation(automationId: String, userId: String, enabled: Boolean?, trigger: String?): PulseResult<AutomationEnvelopeDto> =
        patch(
            "/api/automations/" + java.net.URLEncoder.encode(automationId, "UTF-8"),
            buildJsonObject {
                put("userId", userId)
                if (enabled != null) put("enabled", enabled)
                if (trigger != null) put("trigger", trigger)
            },
        ) {
            confirmedAutomation(it).also { receipt ->
                val row = receipt.automation
                require(row.id == automationId && (enabled == null || row.enabled == enabled) && (trigger == null || row.trigger == trigger.trim())) { "Automation changes were not confirmed." }
            }
        }

    /** DELETE /api/automations/{id} { userId } - admin-only → { ok: true }. */
    suspend fun deleteAutomation(automationId: String, userId: String): PulseResult<OkDto> =
        deleteWithJson(
            "/api/automations/" + java.net.URLEncoder.encode(automationId, "UTF-8"),
            jsonOf("userId" to userId),
        ) { confirmedControlDeletion(it, userId, "automationId", automationId) }

    /** GET /api/webhooks?conversationId=&requesterId= → { webhooks[] } - admins only; contains posting credentials. */
    suspend fun webhooks(conversationId: String, requesterId: String): PulseResult<WebhooksPageDto> =
        get(
            "/api/webhooks?conversationId=" + java.net.URLEncoder.encode(conversationId, "UTF-8") +
                "&requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8"),
        ) {
            val root = PulseJson.parseToJsonElement(it) as? JsonObject
            require(root?.get("webhooks") is JsonArray) { "Webhook list was not confirmed." }
            PulseJson.decodeFromString(WebhooksPageDto.serializer(), it).also { page ->
                page.webhooks.forEach { row -> requireWebhook(row) }
                require(page.webhooks.map { it.id }.distinct().size == page.webhooks.size)
            }
        }

    /** POST /api/webhooks { conversationId, name, requesterId } - admin-only → 201 WebhookDTO. */
    suspend fun createWebhook(conversationId: String, name: String, requesterId: String): PulseResult<WebhookDto> =
        post(
            "/api/webhooks",
            jsonOf("conversationId" to conversationId, "name" to name, "requesterId" to requesterId),
        ) {
            PulseJson.decodeFromString(WebhookDto.serializer(), it).also { row ->
                requireWebhook(row)
                require(row.name == name.trim() && row.createdBy == requesterId) { "Webhook creation was not confirmed." }
            }
        }

    /** DELETE /api/webhooks/{token}?requesterId= - admin-only → { ok: true }. */
    suspend fun deleteWebhook(token: String, requesterId: String): PulseResult<OkDto> =
        deleteAt(
            "/api/webhooks/" + java.net.URLEncoder.encode(token, "UTF-8") +
                "?requesterId=" + java.net.URLEncoder.encode(requesterId, "UTF-8"),
        ) { confirmedControlDeletion(it, requesterId, "token", token) }

    private fun requireAutomation(row: app.pulse.protocol.AutomationDto) {
        require(row.id.isNotBlank() && row.conversationId.isNotBlank() && row.trigger.length in 2..40 && row.reply.length in 1..500 && row.hits >= 0) {
            "Invalid automation response."
        }
    }
    private fun confirmedAutomation(text: String): AutomationEnvelopeDto {
        val row = (PulseJson.parseToJsonElement(text) as? JsonObject)?.get("automation") as? JsonObject
        require(row != null && row["enabled"]?.toString() in setOf("true", "false")) { "Automation state was not confirmed." }
        return PulseJson.decodeFromString(AutomationEnvelopeDto.serializer(), text).also { requireAutomation(it.automation) }
    }
    private fun requireWebhook(row: WebhookDto) {
        require(row.id.isNotBlank() && row.name.isNotBlank() && row.token.isNotBlank() && row.createdBy.isNotBlank() && row.url == "/api/webhooks/${row.token}") {
            "Invalid webhook response."
        }
    }
    private fun confirmedControlDeletion(text: String, owner: String, field: String, target: String): OkDto {
        val root = PulseJson.parseToJsonElement(text) as? JsonObject ?: error("Deletion receipt is missing.")
        require(root["ok"]?.toString() == "true" && root["deleted"]?.toString() == "true" &&
            root["userId"] == JsonPrimitive(owner) && root[field] == JsonPrimitive(target)) { "Deletion was not confirmed." }
        return OkDto(ok = true)
    }

    /** POST /api/ai/recap { userId, conversationId } - LLM summary of the last ~30 messages. */
    suspend fun aiRecap(userId: String, conversationId: String): PulseResult<AiRecapDto> =
        post(
            "/api/ai/recap",
            jsonOf("userId" to userId, "conversationId" to conversationId),
            timeoutMillis = RECAP_TIMEOUT_MS,
        ) { PulseJson.decodeFromString(AiRecapDto.serializer(), it) }

    /**
     * PATCH /api/conversations/{id}/screen-privacy { userId, on } - R42 the
     * per-VIEWER veil flag → { ok, screenPrivacy }.
     */
    suspend fun setMyScreenPrivacy(conversationId: String, userId: String, on: Boolean): PulseResult<ScreenPrivacyAckDto> =
        patch(
            "/api/conversations/" + java.net.URLEncoder.encode(conversationId, "UTF-8") + "/screen-privacy",
            jsonOf("userId" to userId, "on" to on),
        ) {
            val body = PulseJson.parseToJsonElement(it) as? JsonObject
            val result = PulseJson.decodeFromString(ScreenPrivacyAckDto.serializer(), it)
            if (!result.ok || body?.get("screenPrivacy") != JsonPrimitive(on)) {
                throw kotlinx.serialization.SerializationException("Personal screen privacy was not confirmed")
            }
            result
        }

    companion object {
        /** Per-request cap for the slow voice-caption ASR round-trip. */
        private const val TRANSCRIBE_TIMEOUT_MS = 60_000L

        /** R1-W2F - LLM translate round-trip cap (iOS parity, timeoutCap: 30). */
        private const val TRANSLATE_TIMEOUT_MS = 30_000L

        /** R2-A - the recap LLM round-trip can crawl; same cap class as translate. */
        private const val RECAP_TIMEOUT_MS = 30_000L

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

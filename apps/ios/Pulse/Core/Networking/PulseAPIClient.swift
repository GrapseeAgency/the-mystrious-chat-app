import Foundation

/// REAL URLSession REST client — the exact routes the web app calls,
/// identified by `userId` (web parity), failures mapped to the same kinds
/// as Android's PulseResult.
/// N3-b: additive identity / DM-create / reaction / wallet endpoints, and
/// response shapes verified against the live routes (send + react return
/// `{ message }`, conversation create returns `{ conversation }`).
public struct PulseAPIClient: Sendable {
    public struct Failure: Error, Equatable {
        public enum Kind: Equatable { case network, auth, forbidden, rateLimited, notFound, validation, server, unknown }
        public let kind: Kind
        public let message: String?
        /// N3-b — machine code from the body (e.g. "username_taken").
        public var code: String?
        /// N3-b — server-suggested alternative (username_taken flow).
        public var suggestion: String?
        /// Onboarding — raw HTTP status (409 name-clash vs username-taken branches).
        public var status: Int?
        /// REM-B F-MS-20 — slow-mode 429s: seconds the server asked us to
        /// wait (body { error, retryAfter } or the Retry-After header).
        public var retryAfter: Int?

        public init(kind: Kind, message: String?, code: String? = nil, suggestion: String? = nil, status: Int? = nil, retryAfter: Int? = nil) {
            self.kind = kind
            self.message = message
            self.code = code
            self.suggestion = suggestion
            self.status = status
            self.retryAfter = retryAfter
        }
    }

    public let baseURL: URL
    public let userId: String
    /// Wave 8 — the raw session token from the Keychain. Every outbound
    /// request carries `Authorization: Bearer <token>` when present; the
    /// server's optional-verify proxy accepts header-less requests (web
    /// migration parity) and 401s present-but-invalid ones (Failure.kind
    /// == .auth → the session layer clears the token + surfaces re-login).
    public let authToken: String?
    private let session: URLSession
    private let decoder = JSONDecoder()

    public init(baseURL: URL, userId: String, authToken: String? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.userId = userId
        self.authToken = authToken
        self.session = session
    }

    /// Identity-independent client (onboarding: no viewer yet — users routes
    /// never need one).
    public init(baseURL: URL, session: URLSession = .shared) {
        self.init(baseURL: baseURL, userId: "", authToken: nil, session: session)
    }

    // ── reads ────────────────────────────────────────────────
    public func conversations() async throws -> [WireConversationSummary] {
        let page: WireConversationsPage = try await get("/api/conversations?userId=\(userId)")
        return page.conversations
    }

    /// Timeline pages: newest-first page (limit=200 default), older pages via
    /// the `before` cursor (ISO of the oldest loaded), in-conversation search
    /// via `q` (server matches content + fileName) and topic-filtered views
    /// via `topicId` (W2-DATA-B spec §0 — General is the unfiltered room).
    /// Pure query building lives in messagesPath so unit tests can pin the
    /// wire shape without network.
    public func messages(
        conversationId: String,
        limit: Int = 200,
        before: String? = nil,
        query: String? = nil,
        topicId: String? = nil
    ) async throws -> WireMessagesPage {
        try await get(Self.messagesPath(
            conversationId: conversationId,
            limit: limit,
            before: before,
            query: query,
            topicId: topicId
        ))
    }

    /// GET /api/conversations/{id}/messages?limit=&before=&q=&topicId= — the
    /// exact pagination/search/topic contract from spec §0/§1. Blank search
    /// strings AND blank topic ids are omitted (the server would match
    /// nothing useful).
    static func messagesPath(
        conversationId: String,
        limit: Int,
        before: String?,
        query: String?,
        topicId: String? = nil
    ) -> String {
        var path = "/api/conversations/\(conversationId)/messages?limit=\(limit)"
        if let before { path += "&before=\(before)" }
        if let query {
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { path += "&q=\(queryEncoded(trimmed))" }
        }
        if let topicId {
            let trimmed = topicId.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { path += "&topicId=\(queryEncoded(trimmed))" }
        }
        return path
    }

    /// Percent-encoding for query VALUES — plain `.urlQueryAllowed` keeps the
    /// reserved `&=?#+` literal, so a search string containing "&" would split
    /// into bogus params. Subtracting them forces percent-encoding.
    private static func queryEncoded(_ value: String) -> String {
        let allowed = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=?#+"))
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    /// N3-b — every identity on this Pulse (contacts + onboarding picker).
    public func users() async throws -> [WireUser] {
        let page: WireUsersPage = try await get("/api/users")
        return page.users
    }

    /// Onboarding — live @handle availability (web check-username).
    public func checkUsername(_ username: String) async throws -> WireUsernameCheck {
        let query = username.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? username
        return try await get("/api/users/check-username?username=\(query)")
    }

    /// Offline-first @handle verdict: live server first; when it can't answer
    /// (unreachable / route-less static CDN / 5xx) fall back to the repo's
    /// registry JSON, then a local verdict. Definitive 400/409 stay errors.
    public func checkUsernameWithFallback(_ username: String) async throws -> WireUsernameCheck {
        do {
            return try await checkUsername(username)
        } catch let failure as Failure where failure.status == 400 || failure.status == 409 {
            throw failure
        } catch {
            return await registryVerdict(username)
        }
    }

    /// Static CDN registry (registry/handles.json) → local verdict last resort.
    func registryVerdict(_ username: String) async -> WireUsernameCheck {
        do {
            let request = URLRequest(url: url("/registry/handles.json"))
            let data = try await send(request)
            let registry = try decoder.decode(WireHandleRegistry.self, from: data)
            let claimed = ((registry.taken ?? []) + (registry.reserved ?? [])).map { $0.lowercased() }
            if claimed.contains(username.lowercased()) {
                return WireUsernameCheck(available: false, suggestion: username + "_")
            }
            return WireUsernameCheck(available: true, suggestion: nil)
        } catch {
            if Self.reservedHandles.contains(username.lowercased()) {
                return WireUsernameCheck(available: false, suggestion: username + "_")
            }
            return WireUsernameCheck(available: true, suggestion: nil)
        }
    }

    /// Handles nobody may claim — mirrors registry/handles.json (offline net).
    static let reservedHandles: Set<String> = [
        "admin", "administrator", "root", "system", "support", "help", "team",
        "official", "moderator", "mod", "pulse", "staff", "security", "noreply",
        "notifications", "bot", "api", "gs",
    ]

    /// Onboarding — case-insensitive name lookup ("that's me — log in").
    /// 404 means the name is free — surfaced as nil, not an error.
    public func lookupUserByName(_ name: String) async throws -> WireUser? {
        let query = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
        do {
            let envelope: WireUserEnvelope = try await get("/api/users?name=\(query)")
            return envelope.user
        } catch let failure as Failure where failure.kind == .notFound {
            return nil
        }
    }

    /// N3-b — Hub wallet (real coins / gems / streak numbers).
    public func wallet() async throws -> WireWallet {
        let page: WireWalletPage = try await get("/api/hub/wallet?userId=\(userId)")
        return page.wallet ?? WireWallet(userId: nil, coins: nil, gems: nil, streak: nil, lastCheckIn: nil, checkedInToday: nil)
    }

    // ── writes ───────────────────────────────────────────────
    /// Send a message — TEXT is the default (outbox parity); thread replies
    /// ride `parentId` (the thread-ROOT id — NEVER the inline-quote
    /// replyToId, spec §1.1), media sends carry their upload paths + sizes.
    /// Only non-nil fields enter the body; `kind` defaults to text (the
    /// server whitelist is text|image|audio|sticker|location|file).
    public func sendMessage(
        conversationId: String,
        content: String,
        replyToId: String? = nil,
        parentId: String? = nil,
        imagePath: String? = nil,
        audioPath: String? = nil,
        durationMs: Double? = nil,
        filePath: String? = nil,
        fileName: String? = nil,
        fileSize: Int? = nil,
        kind: String? = nil,
        viewOnce: Bool? = nil,
        topicId: String? = nil,
        payload: [String: Any]? = nil,
        anon: Bool? = nil
    ) async throws -> WireChatMessage {
        try await sendMessageWithStreak(
            conversationId: conversationId,
            content: content,
            replyToId: replyToId,
            parentId: parentId,
            imagePath: imagePath,
            audioPath: audioPath,
            durationMs: durationMs,
            filePath: filePath,
            fileName: fileName,
            fileSize: fileSize,
            kind: kind,
            viewOnce: viewOnce,
            topicId: topicId,
            payload: payload,
            anon: anon,
        ).message
    }

    /// R5-A Item 5 — the same POST /api/conversations/{id}/messages wire call,
    /// returning the FULL send response: { message, streak?, xpAwarded }. The
    /// streak sibling rides ONLY when this send changed the streak (route.ts
    /// :678-706) — the room's text-send path toasts from it (web chat-room
    /// sendMessage.onSuccess parity); every other send path keeps the plain
    /// sendMessage wrapper above.
    public func sendMessageWithStreak(
        conversationId: String,
        content: String,
        replyToId: String? = nil,
        parentId: String? = nil,
        imagePath: String? = nil,
        audioPath: String? = nil,
        durationMs: Double? = nil,
        filePath: String? = nil,
        fileName: String? = nil,
        fileSize: Int? = nil,
        kind: String? = nil,
        viewOnce: Bool? = nil,
        topicId: String? = nil,
        payload: [String: Any]? = nil,
        anon: Bool? = nil
    ) async throws -> PulseSendResult {
        var body: [String: Any] = ["senderId": userId, "content": content, "kind": kind ?? "text"]
        if let replyToId { body["replyToId"] = replyToId }
        if let parentId { body["parentId"] = parentId }
        if let imagePath { body["imagePath"] = imagePath }
        if let audioPath { body["audioPath"] = audioPath }
        if let durationMs { body["durationMs"] = durationMs }
        if let filePath { body["filePath"] = filePath }
        if let fileName { body["fileName"] = fileName }
        if let fileSize { body["fileSize"] = fileSize }
        // W2-UI-B — view-once enters the body ONLY when true (server contract
        // spec §0: viewOnce===true REQUIRES imagePath); topicId files the row
        // under a topic (spec §1 rows 9/10 — thread replies never pass it).
        if viewOnce == true { body["viewOnce"] = true }
        if let topicId { body["topicId"] = topicId }
        // REM-B — rich-object payload (sticker {emoji,pack} / effect
        // {effect:…}) rides the JSON object (server serializes to the row);
        // anon is the F-MS-17 incognito flag (groups only, server clamps).
        if let payload { body["payload"] = payload }
        if anon == true { body["anon"] = true }
        let data = try await postRaw("/api/conversations/\(conversationId)/messages", body: body)
        return try WireMessageEnvelope.extractSendResult(from: data)
    }

    public func markRead(conversationId: String) async throws {
        // N10-b transport fix — POST /read requires { userId } (400 otherwise).
        try await postEmpty("/api/conversations/\(conversationId)/read", body: ["userId": userId])
    }

    /// N10-b transport fix — PATCH /pin { userId }; the server TOGGLES the pin
    /// (the old POST { pinned } body was never part of the route contract).
    public func togglePin(conversationId: String) async throws {
        try await patchEmpty("/api/conversations/\(conversationId)/pin", body: ["userId": userId])
    }

    /// N10-b — per-viewer notification mute with the wire preset until:
    /// "8h" | "1w" | "always" | null (unmute).
    public func setMuted(conversationId: String, until preset: String?) async throws {
        let body: [String: Any] = ["userId": userId, "until": preset ?? NSNull()]
        try await patchEmpty("/api/conversations/\(conversationId)/mute", body: body)
    }

    /// Boolean overload kept for older callers — true → 8h, false → unmute.
    public func setMuted(conversationId: String, muted: Bool) async throws {
        try await setMuted(conversationId: conversationId, until: muted ? "8h" : nil)
    }

    /// N10-b transport fix — PATCH /archive { userId, archived }.
    public func archive(conversationId: String, archived: Bool) async throws {
        try await patchEmpty("/api/conversations/\(conversationId)/archive", body: ["userId": userId, "archived": archived])
    }

    /// N3-b — per-viewer unread dot (PATCH, body { userId, on }).
    public func markUnread(conversationId: String, on: Bool) async throws {
        try await patchEmpty("/api/conversations/\(conversationId)/mark-unread", body: ["userId": userId, "on": on])
    }

    /// N3-b — toggle an emoji reaction. Server replies with the FRESH message.
    public func react(messageId: String, emoji: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/messages/\(messageId)/react", body: ["userId": userId, "emoji": emoji])
        return try WireMessageEnvelope.extract(from: data)
    }

    // ── W1-DATA-B — Wave 1 message actions (spec §1.1) ─────

    /// PATCH /api/messages/{id} {userId, content} — sender-only edit; the
    /// server stamps editedAt and replies with the fresh row.
    public func editMessage(id: String, userId: String, content: String) async throws -> WireChatMessage {
        let data = try await patchRaw("/api/messages/\(id)", body: ["userId": userId, "content": content])
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/messages/{id}/pin {userId} — toggle; the {message} back
    /// carries pinnedAt/pinnedBy when pinned, nil when unpinned.
    public func toggleMessagePin(id: String, userId: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/messages/\(id)/pin", body: ["userId": userId])
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/messages/{id}/save {userId} — save/star toggle → {saved}.
    public func toggleMessageSave(id: String, userId: String) async throws -> Bool {
        let data = try await postRaw("/api/messages/\(id)/save", body: ["userId": userId])
        return try decoder.decode(WireSavedToggle.self, from: data).saved
    }

    /// GET /api/conversations/{id}/pinned?userId= — pins list, pinnedAt asc.
    public func pinnedMessages(conversationId: String, userId: String) async throws -> [WireChatMessage] {
        let page: WirePinnedPage = try await get("/api/conversations/\(conversationId)/pinned?userId=\(userId)")
        return page.messages
    }

    /// GET /api/messages/{id}/thread?userId= — thread root + replies (asc).
    public func thread(rootId: String, userId: String) async throws -> WireThreadPage {
        try await get("/api/messages/\(rootId)/thread?userId=\(userId)")
    }

    /// POST /api/uploads {dataUrl} — JSON body (NOT multipart); returns the
    /// stored filePath the message body then references. Callers downscale
    /// images (≤1280px JPEG q0.82) and stay under the size ceilings BEFORE
    /// calling — the server rejects oversized payloads.
    public func uploadMedia(dataUrl: String) async throws -> String {
        let data = try await postRaw("/api/uploads", body: ["dataUrl": dataUrl])
        let result = try decoder.decode(WireUploadResult.self, from: data)
        guard let filePath = result.filePath, !filePath.isEmpty else {
            throw Failure(kind: .validation, message: "Upload response missing filePath")
        }
        return filePath
    }

    /// PATCH /api/conversations/{id}/draft {userId, draft} — server-side draft
    /// mirror ("" clears). The local draft stays authoritative; the mirror
    /// only seeds cross-device. Call sites are allowed to silent-fail this.
    public func setDraft(conversationId: String, userId: String, draft: String) async throws {
        try await patchEmpty("/api/conversations/\(conversationId)/draft", body: ["userId": userId, "draft": draft])
    }

    /// GET /api/conversations/{id}?userId= — one conversation. The detail is
    /// a superset of the summary shape; the tolerant envelope handles both
    /// the {conversation: …} wrapper and the bare object.
    public func conversationDetail(id: String, userId: String) async throws -> WireConversationSummary {
        var request = URLRequest(url: url("/api/conversations/\(id)?userId=\(userId)"))
        request.httpMethod = "GET"
        let data = try await send(request)
        return try WireConversationEnvelope.extract(from: data)
    }

    /// N3-b — create (or dedupe into) a DM/group. Tolerant { conversation } envelope.
    public func createConversation(memberIds: [String], isGroup: Bool, name: String? = nil) async throws -> WireConversationSummary {
        var body: [String: Any] = ["creatorId": userId, "memberIds": memberIds, "isGroup": isGroup]
        if let name { body["name"] = name }
        let data = try await postRaw("/api/conversations", body: body)
        return try WireConversationEnvelope.extract(from: data)
    }

    /// N3-b / Wave 8 — create a new identity. 409 username_taken surfaces
    /// code + suggestion. The 201 envelope now ALSO carries the raw session
    /// token (`{ user, token }` — decode tolerantly, the web ignores it).
    public func createAccount(name: String, color: String, username: String? = nil) async throws -> WireAuthEnvelope {
        var body: [String: Any] = ["name": name, "color": color]
        if let username, !username.isEmpty { body["username"] = username }
        let data = try await postRaw("/api/users", body: body)
        return try decoder.decode(WireAuthEnvelope.self, from: data)
    }

    /// Wave 8 — POST /api/users/login { name } — the reclaim confirm ("log
    /// in instead"). 200 { user, token } | 400 "Name is required." |
    /// 404 "No identity with that name on this Pulse." (honest copy lands
    /// verbatim through Failure.message). Login ROTATES the stored hash —
    /// the previous token 401s afterwards (last login wins).
    public func login(name: String) async throws -> WireAuthEnvelope {
        let data = try await postRaw("/api/users/login", body: ["name": name])
        return try decoder.decode(WireAuthEnvelope.self, from: data)
    }

    // ── Wave 8 — settings blob (PulsePrefs server sync) ──────

    /// GET /api/settings?userId= → 200 { preferences } (defaults merged
    /// server-side). 404 = identity unknown (offline-created local ids).
    public func settings() async throws -> WirePulsePrefs {
        let envelope: WirePrefsEnvelope = try await get("/api/settings?userId=\(userId)")
        return envelope.preferences ?? WirePulsePrefs()
    }

    /// PATCH /api/settings { userId, preferences: Partial } → 200
    /// { preferences } (shallow-merged + clamped server-side). Only the
    /// non-nil patch fields ride the body.
    public func updateSettings(patch: WirePulsePrefs) async throws -> WirePulsePrefs {
        let body: [String: Any] = ["userId": userId, "preferences": patch.asPatchBody()]
        let data = try await patchRaw("/api/settings", body: body)
        let envelope = try decoder.decode(WirePrefsEnvelope.self, from: data)
        return envelope.preferences ?? WirePulsePrefs()
    }

    public func block(userId target: String) async throws {
        try await postEmpty("/api/users/\(target)/block", body: ["userId": userId])
    }
    /// WAVE-6 DEFECT FIX — the old body POSTed /api/users/{target}/unblock,
    /// a route that does NOT exist server-side (every unblock failed). The
    /// R47 contract is DELETE /api/users/{id}/block?userId={actor} (query
    /// param, not body — mirrors the safety route's pair convention).
    public func unblock(userId target: String) async throws {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        try await deleteEmpty("/api/users/\(target)/block?userId=\(query)", body: ["userId": userId])
    }
    /// POST /api/users/{id}/report { userId, reason, details? } — repeat with
    /// the same reason refreshes the row → { reported, updated: true }.
    public func report(userId target: String, reason: String, details: String?) async throws -> WireReportVerdict {
        var body: [String: Any] = ["userId": userId, "reason": reason]
        if let details { body["details"] = details }
        let data = try await postRaw("/api/users/\(target)/report", body: body)
        return try decoder.decode(WireReportVerdict.self, from: data)
    }

    // ── Wave 6 — social graph & discovery (F-CP/F-SM/F-FD/F-CH) ──

    /// GET /api/users/{id} → { user } (AppUser mirror; 404 = gone).
    public func user(_ id: String) async throws -> WireUser {
        let envelope: WireUserEnvelope = try await get("/api/users/\(id)")
        return envelope.user
    }

    /// GET /api/users/{id}/stats → { stats } (real activity stamps;
    /// lastSeenAt is scrubbed server-side when the user hides it).
    public func userStats(_ id: String) async throws -> WireUserStats {
        let page: WireUserStatsPage = try await get("/api/users/\(id)/stats")
        return page.stats
    }

    /// GET /api/users/{id}/safety?userId={viewer} → { peerId, safetyNumber,
    /// verified, verifiedAt } — the server-computed 12×5 number + MY state.
    public func safetyState(peerId: String) async throws -> WireSafetyState {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        return try await get("/api/users/\(peerId)/safety?userId=\(query)")
    }

    /// POST /api/users/{id}/safety { userId } — upsert the verification
    /// (re-verifying refreshes verifiedAt). No optimistic lies: callers
    /// refetch the state on settle.
    public func verifySafety(peerId: String) async throws -> WireSafetyVerdict {
        let data = try await postRaw("/api/users/\(peerId)/safety", body: ["userId": userId])
        return try decoder.decode(WireSafetyVerdict.self, from: data)
    }

    /// DELETE /api/users/{id}/safety?userId={viewer} — unverify (deleteMany).
    public func resetSafety(peerId: String) async throws {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        try await deleteEmpty("/api/users/\(peerId)/safety?userId=\(query)", body: ["userId": userId])
    }

    /// GET /api/users/{id}/block?userId={actor} — pair block state.
    public func blockState(target: String) async throws -> Bool {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        let state: WireBlockState = try await get("/api/users/\(target)/block?userId=\(query)")
        return state.blocked
    }

    /// GET /api/users/{id}/blocks?userId={self} — MY block list, newest first
    /// (self-service only; the server 403s any other viewer).
    public func blockedAccounts() async throws -> [WireBlockedAccount] {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        let page: WireBlocksPage = try await get("/api/users/\(userId)/blocks?userId=\(query)")
        return page.blocks
    }

    /// GET /api/users/{id}/report?userId={viewer} — MY prior submissions
    /// about this account (private "already reported" hint).
    public func reportHistory(reportedId: String) async -> [WireReportReasonRow] {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        guard let page: WireReportHistory = try? await get(
            "/api/users/\(reportedId)/report?userId=\(query)",
            as: WireReportHistory.self,
        ) else { return [] }
        return page.reasons
    }

    /// PATCH /api/users/{id} { name?, about?, color?, statusEmoji?,
    /// statusText?, username?, avatar? } → { user }. Explicit keys only —
    /// the server validates limits (name 1-32, about 1-140, statusEmoji ≤8,
    /// statusText ≤48, username 3-20 [a-z0-9_], avatar "/api/uploads/<file>").
    public func updateProfile(userId: String, body: [String: Any]) async throws -> WireUser {
        let data = try await patchRaw("/api/users/\(userId)", body: body)
        return try decoder.decode(WireUserEnvelope.self, from: data).user
    }

    /// GET /api/mentions?userId=&limit= — the FULL 14-day mention feed
    /// (entries, not just the pill count; tolerant → [] when unreachable).
    public func mentions(limit: Int = 50) async -> [WireMentionEntry] {
        guard let page: WireMentionEntriesPage = try? await get(
            "/api/mentions?userId=\(userId)&limit=\(limit)",
            as: WireMentionEntriesPage.self,
        ) else { return [] }
        return page.items ?? []
    }

    /// GET /api/channels?userId=[&mine=1] — the broadcast directory
    /// (memberCount desc, createdAt desc; viewer-aware isSubscribed/unread).
    public func channels(mineOnly: Bool = false) async throws -> [WireChannelSummary] {
        var path = "/api/channels?userId=\(userId)"
        if mineOnly { path += "&mine=1" }
        let page: WireChannelsPage = try await get(path)
        return page.channels
    }

    /// POST /api/channels { userId, name(2-40), description?(≤200), photo? }
    /// → 201 { channel } — creator lands as admin + the first post is real.
    public func createChannel(name: String, description: String, photoPath: String?) async throws -> WireChannelSummary {
        var body: [String: Any] = ["userId": userId, "name": name, "description": description]
        if let photoPath, !photoPath.isEmpty { body["photo"] = photoPath }
        let data = try await postRaw("/api/channels", body: body)
        let created = try decoder.decode(WireChannelCreated.self, from: data)
        guard let channel = created.channel else {
            throw Failure(kind: .validation, message: "Channel response missing the created channel")
        }
        return channel
    }

    /// POST /api/channels/{id}/subscribe { userId } → { already, memberCount }.
    public func subscribeChannel(_ id: String) async throws -> WireSubscribeResult {
        let data = try await postRaw("/api/channels/\(id)/subscribe", body: ["userId": userId])
        return try decoder.decode(WireSubscribeResult.self, from: data)
    }

    /// DELETE /api/channels/{id}/subscribe { userId } → { ok, memberCount }.
    /// Last-admin leave is blocked server-side with the honest 403 copy.
    public func unsubscribeChannel(_ id: String) async throws -> WireUnsubscribeResult {
        let data = try await deleteRaw("/api/channels/\(id)/subscribe", body: ["userId": userId])
        return try decoder.decode(WireUnsubscribeResult.self, from: data)
    }

    /// GET /api/invite/{code}?userId= → { invite } (404 = no longer valid).
    public func invitePreview(code: String) async throws -> WireInvitePreview {
        let encoded = code.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? code
        let page: WireInvitePage = try await get("/api/invite/\(encoded)?userId=\(userId)")
        return page.invite
    }

    /// POST /api/invite/{code}/join { userId } → { conversationId,
    /// alreadyMember } (idempotent — already a member joins nothing).
    public func joinInvite(code: String) async throws -> WireInviteJoinResult {
        let encoded = code.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? code
        let data = try await postRaw("/api/invite/\(encoded)/join", body: ["userId": userId])
        return try decoder.decode(WireInviteJoinResult.self, from: data)
    }

    /// POST /api/folders { userId, name(1-24), emoji?(≤16, default 📂) }
    /// → 201 { folder } (position = max+1).
    public func createFolder(name: String, emoji: String?) async throws -> WireFolder {
        var body: [String: Any] = ["userId": userId, "name": name]
        if let emoji, !emoji.isEmpty { body["emoji"] = emoji }
        let data = try await postRaw("/api/folders", body: body)
        return try decoder.decode(WireFolderEnvelope.self, from: data).folder
    }

    /// PATCH /api/folders/{id} { name?, emoji?, position? } → { folder }.
    public func updateFolder(id: String, name: String?, emoji: String?, position: Int?) async throws -> WireFolder {
        var body: [String: Any] = [:]
        if let name { body["name"] = name }
        if let emoji { body["emoji"] = emoji }
        if let position { body["position"] = position }
        let data = try await patchRaw("/api/folders/\(id)", body: body)
        return try decoder.decode(WireFolderEnvelope.self, from: data).folder
    }

    /// DELETE /api/folders/{id} — cascades membership rows only; the chats
    /// stay in the list. → { ok: true } (status is the contract).
    public func deleteFolder(id: String) async throws {
        try await deleteEmpty("/api/folders/\(id)", body: ["userId": userId])
    }

    /// PUT /api/folders/{id}/conversations { conversationIds[] } — FULL
    /// ordered replace in one transaction (dups collapsed server-side,
    /// empty clears). → { folder } with the fresh membership.
    public func saveFolderMembership(folderId: String, conversationIds: [String]) async throws -> WireFolder {
        let data = try await putRaw("/api/folders/\(folderId)/conversations", body: ["conversationIds": conversationIds])
        return try decoder.decode(WireFolderEnvelope.self, from: data).folder
    }

    // ── N10-b home-page endpoints (all degrade to nil on failure) ──

    /// POST /api/conversations/self { userId } — Note to Self create/dedupe.
    public func createSelfChat() async throws -> WireConversationSummary {
        let data = try await postRaw("/api/conversations/self", body: ["userId": userId])
        return try WireConversationEnvelope.extract(from: data)
    }

    /// GET /api/stories?requesterId= — 24h status groups (nil = unreachable,
    /// the UI renders only the My-status cell — honest empty, no fakes).
    public func stories() async -> WireStoriesPage? {
        try? await get("/api/stories?requesterId=\(userId)", as: WireStoriesPage.self)
    }

    // ── Wave 4 — stories write/owner paths (REST only; zero socket) ──

    /// POST /api/stories { requesterId, caption?, background?, imagePath? }
    /// → 201 { story }. Text stories carry `background` (one of the 8 palette
    /// keys); photo stories carry `imagePath` (from /api/uploads) — the server
    /// forces "emerald" for image stories, so it is never sent.
    public func postStory(caption: String, background: String?, imagePath: String?) async throws -> WireStoryItem {
        var body: [String: Any] = ["requesterId": userId]
        let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { body["caption"] = trimmed }
        if let background { body["background"] = background }
        if let imagePath { body["imagePath"] = imagePath }
        let data = try await postRaw("/api/stories", body: body)
        let created = try decoder.decode(WireStoryCreated.self, from: data)
        guard let story = created.story, let id = story.id, !id.isEmpty else {
            throw Failure(kind: .validation, message: "Story response missing the created story")
        }
        return story
    }

    /// POST /api/stories/{id}/view { requesterId } — idempotent view mark
    /// (owner short-circuits server-side without recording a self-view).
    public func markStoryViewed(id: String) async throws -> Int {
        let data = try await postRaw("/api/stories/\(id)/view", body: ["requesterId": userId])
        let result = try decoder.decode(WireStoryViewCount.self, from: data)
        return result.viewCount ?? 0
    }

    /// GET /api/stories/{id}/view?requesterId= — owner-only viewers list
    /// (403 otherwise; oldest viewer first). Empty list on a missing key.
    public func storyViewers(id: String) async throws -> [WireStoryViewer] {
        let page: WireStoryViewersPage = try await get(
            "/api/stories/\(id)/view?requesterId=\(userId)",
            as: WireStoryViewersPage.self,
        )
        return page.viewers ?? []
    }

    /// DELETE /api/stories/{id} { requesterId } — owner-only (403 / 404 wire).
    public func deleteStory(id: String) async throws {
        try await deleteEmpty("/api/stories/\(id)", body: ["requesterId": userId])
    }

    /// GET /api/folders?userId= — chat folders (nil = unreachable → All only).
    public func folders() async -> [WireFolder]? {
        guard let page: WireFoldersPage = try? await get("/api/folders?userId=\(userId)", as: WireFoldersPage.self) else { return nil }
        return page.folders
    }

    /// GET /api/mentions?userId= — mention count for the entry pill (nil → 0).
    public func mentionsCount() async -> Int? {
        guard let page: WireMentionsPage = try? await get("/api/mentions?userId=\(userId)&limit=50", as: WireMentionsPage.self) else { return nil }
        return page.items?.count
    }

    /// GET /api/search?userId=&q= — server message search (nil = unreachable;
    /// the Messages section is omitted silently — local results still work).
    public func searchMessages(_ query: String) async -> WireSearchPage? {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return try? await get("/api/search?userId=\(userId)&q=\(encoded)", as: WireSearchPage.self)
    }

    /// DELETE /api/messages/{id} { requesterId } — soft-delete MY OWN message
    /// (clear-chat; the route is sender-gated server-side).
    public func deleteOwnMessage(id: String) async throws {
        try await deleteEmpty("/api/messages/\(id)", body: ["requesterId": userId])
    }

    /// Every message in a room, oldest → newest (paginated via the `before`
    /// cursor) — feeds the .txt export and the clear-chat sweep.
    public func fullHistory(conversationId: String) async -> [WireChatMessage]? {
        var pages: [[WireChatMessage]] = []
        var before: String?
        for _ in 0..<24 {
            var path = "/api/conversations/\(conversationId)/messages?limit=500"
            if let before { path += "&before=\(before)" }
            guard let page: WireMessagesPage = try? await get(path, as: WireMessagesPage.self),
                  !page.messages.isEmpty else { break }
            pages.append(page.messages)
            if !page.hasMore { break }
            guard let cursor = page.messages.first?.createdAt else { break }
            before = cursor.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)
        }
        guard !pages.isEmpty else { return nil }
        return pages.reversed().flatMap { $0 }
    }

    // ── W2-DATA-B — Wave 2 message depth (spec §0 contract) ─────

    /// POST /api/messages/{id}/transcribe {requesterId} — voice notes only
    /// (kind "audio"). → { transcript, transcribedAt, cached } (cached:true
    /// on the second call; 422 empty ASR / 502 service down surface as
    /// Failure.validation / Failure.server).
    public func transcribe(messageId: String, requesterId: String) async throws -> WireTranscribeResult {
        let data = try await postRaw("/api/messages/\(messageId)/transcribe", body: ["requesterId": requesterId])
        return try decoder.decode(WireTranscribeResult.self, from: data)
    }

    /// W5-f — POST /api/voice/transcribe { conversationId, requesterId,
    /// audioBase64 } → { transcript } (≤280 chars). Live-caption ASR for
    /// PTT voice rooms (web parity, voice-room-sheet.tsx). The request
    /// carries up to a 4 s WAV window (~512 KB base64), so THIS call alone
    /// gets a 60 s timeout — every other route keeps the ≤6 s house cap
    /// (additive `timeoutCap` on the private transport, default unchanged).
    /// Error mapping rides the shared send() kinds: 403 → .forbidden,
    /// 422 → .validation, 502 → .server, 413 (window too large) → status
    /// preserved on Failure for honest copy.
    public func transcribeVoice(conversationId: String, requesterId: String, audioBase64: String) async throws -> WireVoiceTranscriptResult {
        let data = try await postRaw(
            "/api/voice/transcribe",
            body: ["conversationId": conversationId, "requesterId": requesterId, "audioBase64": audioBase64],
            timeoutCap: 60,
        )
        return try decoder.decode(WireVoiceTranscriptResult.self, from: data)
    }

    /// POST /api/messages/{id}/viewed {userId} — consume a view-once
    /// attachment. Idempotent: the FIRST non-sender open stamps
    /// viewedAt/viewedBy forever; the {message} back is authoritative.
    public func markViewed(messageId: String, userId: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/messages/\(messageId)/viewed", body: ["userId": userId])
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/conversations/{id}/poll {senderId, question, options} —
    /// single-choice poll message (2–6 non-blank options server-gated).
    /// → 201 { message } with message.poll populated.
    public func createPoll(
        conversationId: String,
        senderId: String,
        question: String,
        options: [String]
    ) async throws -> WireChatMessage {
        let data = try await postRaw(
            "/api/conversations/\(conversationId)/poll",
            body: ["senderId": senderId, "question": question, "options": options]
        )
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/polls/{id}/vote {userId, optionId} — single-choice vote
    /// (server moves the vote on revote; closed polls answer 400).
    /// → { message } with the fresh tally.
    public func votePoll(pollId: String, userId: String, optionId: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/polls/\(pollId)/vote", body: ["userId": userId, "optionId": optionId])
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/polls/{id}/close {userId} — end voting (creator only).
    /// → { message } with the frozen tally.
    public func closePoll(pollId: String, userId: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/polls/\(pollId)/close", body: ["userId": userId])
        return try WireMessageEnvelope.extract(from: data)
    }

    /// POST /api/messages/{id}/unfurl {userId} — attach an Open-Graph
    /// preview. → { message: ChatMessage | null } — null is VALID (nothing
    /// link-ish / host unreachable), hence the lenient extract. The link
    /// arrives for everyone else via the link:preview relay envelope.
    public func unfurl(messageId: String, userId: String) async throws -> WireChatMessage? {
        let data = try await postRaw("/api/messages/\(messageId)/unfurl", body: ["userId": userId])
        return WireMessageEnvelope.extractOptional(from: data)
    }

    /// GET /api/users/{id}/saved — saved/starred library, newest-first,
    /// cap 100, NO server pagination/search (local filter is the native
    /// capability, spec §1 row 14).
    public func savedLibrary(userId: String) async throws -> [WireSavedItem] {
        let page: WireSavedPage = try await get("/api/users/\(userId)/saved")
        return page.items ?? []
    }

    /// GET /api/conversations/{id}/topics?userId= — the topic rail
    /// (participant-guarded; General is NOT a row).
    public func topics(conversationId: String, userId: String) async throws -> [WireTopic] {
        let page: WireTopicsPage = try await get("/api/conversations/\(conversationId)/topics?userId=\(userId)")
        return page.topics ?? []
    }

    /// POST /api/conversations/{id}/topics {userId, name, emoji?} — create
    /// (1..32 chars) or case-insensitive dedupe into the existing row
    /// (200 dedupe / 201 create — both answer { topic }).
    public func createTopic(
        conversationId: String,
        userId: String,
        name: String,
        emoji: String?
    ) async throws -> WireTopic {
        var body: [String: Any] = ["userId": userId, "name": name]
        if let emoji { body["emoji"] = emoji }
        let data = try await postRaw("/api/conversations/\(conversationId)/topics", body: body)
        return try WireTopicEnvelope.extract(from: data)
    }

    /// DELETE /api/topics/{id}?userId= — hard-delete a topic (creator/admin
    /// only). Filed messages drop back to General server-side (SetNull);
    /// → { ok: true } — the status code is the contract, the body the verdict.
    public func deleteTopic(topicId: String, userId: String) async throws {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        var request = URLRequest(url: url("/api/topics/\(topicId)?userId=\(query)"))
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    // ── calls (W3-b — Wave 3 native calls) ───────────────────

    /// GET /api/calls?userId=X — the viewer's call log, newest first, server
    /// cap 50. Rows where the viewer was caller OR callee are merged
    /// server-side; each resolves the PEER + an `outgoing` flag.
    public func callHistory(userId: String) async throws -> [WireCallLogItem] {
        let query = userId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? userId
        let page: WireCallLogPage = try await get("/api/calls?userId=\(query)")
        return page.items ?? []
    }

    /// POST /api/calls { userId, conversationId, peerId, kind, status,
    /// durationSec? } → 201 { item }. Single-writer rule: the CALLER's client
    /// writes every terminal row (completed | missed | declined) exactly once;
    /// the callee never writes. Offline failures queue in PulseStore
    /// (callLogQueue) and flush on socket reconnect / app start.
    public func createCallLogRow(_ body: [String: Any]) async throws -> WireCallLogItem {
        let data = try await postRaw("/api/calls", body: body)
        return try WireCallLogEnvelope.extract(from: data)
    }

    // ── Wave 7 — collaboration & hub (F-RO / F-HB) ───────────

    private func q(_ id: String) -> String {
        id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
    }

    private func envelope<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try decoder.decode(T.self, from: data)
    }

    // MARK: red packets

    /// POST /api/redpackets { userId, conversationId, total, count, note? } → { message, packet }.
    public func createRedPacket(conversationId: String, total: Int, count: Int, note: String?) async throws -> WireRedPacketCreateResult {
        var body: [String: Any] = ["userId": userId, "conversationId": conversationId, "total": total, "count": count]
        if let note, !note.isEmpty { body["note"] = note }
        let data = try await postRaw("/api/redpackets", body: body)
        return try envelope(WireRedPacketCreateResult.self, from: data)
    }

    /// GET /api/redpackets/{id}?userId= — lazy refund settles on first read after expiry.
    public func redPacketDetail(_ packetId: String) async throws -> WireRedPacketDetail {
        try await get("/api/redpackets/\(q(packetId))?userId=\(q(userId))")
    }

    /// POST /api/redpackets/{id}/grab { userId } — atomic (409s surface verbatim).
    public func grabRedPacket(_ packetId: String) async throws -> WireRedPacketGrabResult {
        let data = try await postRaw("/api/redpackets/\(q(packetId))/grab", body: ["userId": userId])
        return try envelope(WireRedPacketGrabResult.self, from: data)
    }

    // MARK: whiteboard

    /// GET /api/conversations/{id}/whiteboard?requesterId=&since= (since = epoch ms; nil = full).
    public func whiteboard(conversationId: String, since: Int64?) async throws -> WireWhiteboardPage {
        var path = "/api/conversations/\(q(conversationId))/whiteboard?requesterId=\(q(userId))"
        if let since { path += "&since=\(since)" }
        return try await get(path)
    }

    /// POST /api/conversations/{id}/whiteboard { requesterId, strokes[] } → { ids, created, serverTime }.
    public func postWhiteboardStrokes(conversationId: String, strokes: [WireWhiteboardStrokePost]) async throws -> WireWhiteboardPostResult {
        let strokeArrays: [[String: Any]] = strokes.map { s in
            ["color": s.color, "width": s.width, "points": s.points]
        }
        let data = try await postRaw("/api/conversations/\(q(conversationId))/whiteboard", body: ["requesterId": userId, "strokes": strokeArrays])
        return try envelope(WireWhiteboardPostResult.self, from: data)
    }

    /// POST { action: 'undo' } — deletes only the caller's latest stroke.
    public func undoWhiteboardStroke(conversationId: String) async throws -> WireWhiteboardUndoResult {
        let data = try await postRaw("/api/conversations/\(q(conversationId))/whiteboard", body: ["action": "undo", "requesterId": userId])
        return try envelope(WireWhiteboardUndoResult.self, from: data)
    }

    /// DELETE ?requesterId= — clear all + resetAt watermark.
    public func clearWhiteboard(conversationId: String) async throws -> WireWhiteboardClearResult {
        var request = URLRequest(url: url("/api/conversations/\(q(conversationId))/whiteboard?requesterId=\(q(userId))"))
        request.httpMethod = "DELETE"
        let data = try await send(request)
        return try envelope(WireWhiteboardClearResult.self, from: data)
    }

    // MARK: kanban

    /// GET /api/conversations/{id}/kanban?userId= → { cards }.
    public func kanbanBoard(conversationId: String) async throws -> WireKanbanPage {
        try await get("/api/conversations/\(q(conversationId))/kanban?userId=\(q(userId))")
    }

    /// POST /api/conversations/{id}/kanban { userId, title?, column?, assigneeId?, messageId? } → { card }.
    public func createKanbanCard(conversationId: String, title: String?, column: String?, assigneeId: String?, messageId: String?) async throws -> WireKanbanCard {
        var body: [String: Any] = ["userId": userId]
        if let title { body["title"] = title }
        if let column { body["column"] = column }
        if let assigneeId { body["assigneeId"] = assigneeId }
        if let messageId { body["messageId"] = messageId }
        let data = try await postRaw("/api/conversations/\(q(conversationId))/kanban", body: body)
        return try envelope(WireKanbanCardEnvelope.self, from: data).card ?? WireKanbanCard(id: "", conversationId: conversationId, title: title, column: column, position: 0, assigneeId: assigneeId, assigneeName: nil, createdById: nil, createdByName: nil, createdAt: nil, updatedAt: nil, sourceMessageId: messageId)
    }

    /// PATCH /api/kanban/{cardId} { userId, title?, column?, assigneeId?, position? } → { card }.
    public func updateKanbanCard(_ cardId: String, title: String?, column: String?, assigneeId: String?, clearAssignee: Bool, position: Int?) async throws -> WireKanbanCard {
        var body: [String: Any] = ["userId": userId]
        if let title { body["title"] = title }
        if let column { body["column"] = column }
        if clearAssignee { body["assigneeId"] = NSNull() }
        else if let assigneeId { body["assigneeId"] = assigneeId }
        if let position { body["position"] = position }
        let data = try await patchRaw("/api/kanban/\(q(cardId))", body: body)
        return try envelope(WireKanbanCardEnvelope.self, from: data).card ?? WireKanbanCard(id: cardId, conversationId: nil, title: title, column: column, position: position, assigneeId: assigneeId, assigneeName: nil, createdById: nil, createdByName: nil, createdAt: nil, updatedAt: nil, sourceMessageId: nil)
    }

    /// DELETE /api/kanban/{cardId}?userId= — creator OR group admin.
    public func deleteKanbanCard(_ cardId: String) async throws {
        var request = URLRequest(url: url("/api/kanban/\(q(cardId))?userId=\(q(userId))"))
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    // MARK: events

    /// GET /api/conversations/{id}/events?userId= — upcoming asc then past desc, ≤50.
    public func events(conversationId: String) async throws -> WireEventsPage {
        try await get("/api/conversations/\(q(conversationId))/events?userId=\(q(userId))")
    }

    /// POST /api/conversations/{id}/events { userId, title, startsAt, description?, location? } → { event }.
    public func createEvent(conversationId: String, title: String, startsAtIso: String, description: String?, location: String?) async throws -> WireGroupEvent {
        var body: [String: Any] = ["userId": userId, "title": title, "startsAt": startsAtIso]
        if let description { body["description"] = description }
        if let location { body["location"] = location }
        let data = try await postRaw("/api/conversations/\(q(conversationId))/events", body: body)
        return try envelope(WireEventEnvelope.self, from: data).event ?? WireGroupEvent(id: "", title: title, description: description, location: location, startsAt: startsAtIso, createdById: userId, createdByName: nil, rsvps: nil, counts: nil, myStatus: nil)
    }

    /// DELETE /api/events/{id}?userId= — creator OR group admin.
    public func deleteEvent(_ eventId: String) async throws {
        var request = URLRequest(url: url("/api/events/\(q(eventId))?userId=\(q(userId))"))
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    /// POST /api/events/{id}/rsvp { userId, status: going|maybe|no } → { rsvp, counts }.
    public func rsvpEvent(_ eventId: String, status: String) async throws -> WireRsvpResult {
        let data = try await postRaw("/api/events/\(q(eventId))/rsvp", body: ["userId": userId, "status": status])
        return try envelope(WireRsvpResult.self, from: data)
    }

    /// POST /api/events/{id}/checkin { userId } — window +15 XP; 409 outside window is a real failure.
    public func checkinEvent(_ eventId: String) async throws -> WireCheckinResult {
        let data = try await postRaw("/api/events/\(q(eventId))/checkin", body: ["userId": userId])
        return try envelope(WireCheckinResult.self, from: data)
    }

    // MARK: reminders

    /// GET /api/reminders?userId=[&due=1] — due = remindAt ≤ now && firedAt null.
    public func reminders(dueOnly: Bool) async throws -> WireRemindersPage {
        try await get("/api/reminders?userId=\(q(userId))" + (dueOnly ? "&due=1" : ""))
    }

    /// POST /api/reminders { userId, conversationId, messageId?, note?, remindAt } → { item }.
    public func createReminder(conversationId: String, messageId: String?, note: String?, remindAtIso: String) async throws -> WireReminderItem {
        var body: [String: Any] = ["userId": userId, "conversationId": conversationId, "remindAt": remindAtIso]
        if let messageId { body["messageId"] = messageId }
        if let note { body["note"] = note }
        let data = try await postRaw("/api/reminders", body: body)
        if let page = try? envelope(WireReminderEnvelope.self, from: data), let item = page.item { return item }
        return try envelope(WireReminderItem.self, from: data)
    }

    /// PATCH /api/reminders/{id} { userId } — owner-only resolve.
    public func resolveReminder(_ reminderId: String) async throws -> WireReminderResolve {
        let data = try await patchRaw("/api/reminders/\(q(reminderId))", body: ["userId": userId])
        return try envelope(WireReminderResolve.self, from: data)
    }

    /// DELETE /api/reminders/{id} { userId } — owner-only cancel.
    public func deleteReminder(_ reminderId: String) async throws {
        try await deleteRaw("/api/reminders/\(q(reminderId))", body: ["userId": userId])
    }

    // MARK: games

    /// POST /api/games { userId, conversationId, game, opponentId? } → { match, message }.
    public func createGame(conversationId: String, opponentId: String?) async throws -> WireGameMatchCreateResult {
        var body: [String: Any] = ["userId": userId, "conversationId": conversationId, "game": "tictactoe"]
        if let opponentId { body["opponentId"] = opponentId }
        let data = try await postRaw("/api/games", body: body)
        return try envelope(WireGameMatchCreateResult.self, from: data)
    }

    /// GET /api/games/{id} → { match, playerX, playerO }.
    public func gameDetail(_ matchId: String) async throws -> WireGameDetail {
        try await get("/api/games/\(q(matchId))")
    }

    /// POST /api/games/{id}/move { userId, cell 0..8 } → { match, playerX, playerO }.
    public func gameMove(_ matchId: String, cell: Int) async throws -> WireGameDetail {
        let data = try await postRaw("/api/games/\(q(matchId))/move", body: ["userId": userId, "cell": cell])
        return try envelope(WireGameDetail.self, from: data)
    }

    /// POST /api/games/{id}/join { userId } — first-come O seat.
    public func joinGame(_ matchId: String) async throws -> WireGameDetail {
        let data = try await postRaw("/api/games/\(q(matchId))/join", body: ["userId": userId])
        return try envelope(WireGameDetail.self, from: data)
    }

    // MARK: tournaments

    /// POST /api/tournaments { userId, conversationId, name } → { tournament, message }.
    public func createTournament(conversationId: String, name: String) async throws -> WireTournamentCreateResult {
        let data = try await postRaw("/api/tournaments", body: ["userId": userId, "conversationId": conversationId, "name": name, "game": "tictactoe"])
        return try envelope(WireTournamentCreateResult.self, from: data)
    }

    /// GET /api/tournaments/{id} → { tournament } standings (tolerant envelope OR bare).
    public func tournamentDetail(_ tournamentId: String) async throws -> WireTournamentSummary {
        var request = URLRequest(url: url("/api/tournaments/\(q(tournamentId))"))
        request.httpMethod = "GET"
        let data = try await send(request)
        if let page = try? envelope(WireTournamentEnvelope.self, from: data), let t = page.tournament { return t }
        return try envelope(WireTournamentSummary.self, from: data)
    }

    /// PATCH /api/tournaments/{id} { userId, status: 'finished' } — creator/admin, idempotent.
    public func finishTournament(_ tournamentId: String) async throws -> WireTournamentSummary {
        let data = try await patchRaw("/api/tournaments/\(q(tournamentId))", body: ["userId": userId, "status": "finished"])
        if let page = try? envelope(WireTournamentEnvelope.self, from: data), let t = page.tournament { return t }
        return try envelope(WireTournamentSummary.self, from: data)
    }

    /// POST /api/tournaments/{id}/join { userId } — idempotent upsert.
    public func joinTournament(_ tournamentId: String) async throws -> WireTournamentJoinResult {
        let data = try await postRaw("/api/tournaments/\(q(tournamentId))/join", body: ["userId": userId])
        return try envelope(WireTournamentJoinResult.self, from: data)
    }

    // MARK: leaderboard

    /// GET /api/leaderboard?conversationId=&userId= (room) or bare (global top 50).
    public func leaderboard(conversationId: String?) async throws -> WireLeaderboardPage {
        if let conversationId {
            return try await get("/api/leaderboard?conversationId=\(q(conversationId))&userId=\(q(userId))")
        }
        return try await get("/api/leaderboard")
    }

    // MARK: hub economy (F-HB)

    /// GET /api/hub/wallet?userId=[&ledger=30] — upserts a zero wallet; ledger desc.
    public func walletPage(ledger: Int = 30) async throws -> WireWalletPage {
        try await get("/api/hub/wallet?userId=\(q(userId))&ledger=\(ledger)")
    }

    /// POST /api/hub/wallet/checkin { userId } — 409 body carries { error, wallet }.
    public func checkinWallet() async throws -> WireCheckinWalletResult {
        let data = try await postRaw("/api/hub/wallet/checkin", body: ["userId": userId])
        return try envelope(WireCheckinWalletResult.self, from: data)
    }

    /// POST /api/hub/wallet/transfer { userId, toUsername, amount, note? } → { wallet, to }.
    public func transferCoins(toUsername: String, amount: Int, note: String?) async throws -> WireTransferResult {
        var body: [String: Any] = ["userId": userId, "toUsername": toUsername.trimmingCharacters(in: .whitespaces).hasPrefix("@") ? String(toUsername.dropFirst()).lowercased() : toUsername.lowercased(), "amount": amount]
        if let note, !note.isEmpty { body["note"] = note }
        let data = try await postRaw("/api/hub/wallet/transfer", body: body)
        return try envelope(WireTransferResult.self, from: data)
    }

    /// GET /api/hub/swap → { rates, stats }.
    public func swapRates() async throws -> WireSwapPage {
        try await get("/api/hub/swap")
    }

    /// POST /api/hub/swap { userId, direction, amount } → { wallet, note }.
    public func swap(direction: String, amount: Int) async throws -> WireSwapResult {
        let data = try await postRaw("/api/hub/swap", body: ["userId": userId, "direction": direction, "amount": amount])
        return try envelope(WireSwapResult.self, from: data)
    }

    /// GET /api/hub/tasks?userId= → { tasks } ordered doing → todo → done.
    public func hubTasks() async throws -> WireHubTasksPage {
        try await get("/api/hub/tasks?userId=\(q(userId))")
    }

    /// POST /api/hub/tasks { userId, title, status? } → { task }.
    public func createHubTask(title: String, status: String?) async throws -> WireHubTask {
        let data = try await postRaw("/api/hub/tasks", body: ["userId": userId, "title": title, "status": status ?? "todo"])
        if let page = try? envelope(WireHubTaskEnvelope.self, from: data), let t = page.task { return t }
        return try envelope(WireHubTask.self, from: data)
    }

    /// PATCH /api/hub/tasks/{id} { userId, title?, status? } — owner-only.
    public func updateHubTask(_ taskId: String, title: String?, status: String?) async throws -> WireHubTask {
        var body: [String: Any] = ["userId": userId]
        if let title { body["title"] = title }
        if let status { body["status"] = status }
        let data = try await patchRaw("/api/hub/tasks/\(q(taskId))", body: body)
        if let page = try? envelope(WireHubTaskEnvelope.self, from: data), let t = page.task { return t }
        return try envelope(WireHubTask.self, from: data)
    }

    /// DELETE /api/hub/tasks/{id}?userId= — owner-only.
    public func deleteHubTask(_ taskId: String) async throws {
        var request = URLRequest(url: url("/api/hub/tasks/\(q(taskId))?userId=\(q(userId))"))
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    /// GET /api/hub/market?userId= → { listings }.
    public func market() async throws -> WireMarketPage {
        try await get("/api/hub/market?userId=\(q(userId))")
    }

    /// POST /api/hub/market { userId, title, description?, price } → { listing }.
    public func createListing(title: String, description: String?, price: Int) async throws -> WireMarketListing {
        var body: [String: Any] = ["userId": userId, "title": title, "price": price]
        if let description { body["description"] = description }
        let data = try await postRaw("/api/hub/market", body: body)
        if let page = try? envelope(WireMarketListingEnvelope.self, from: data), let l = page.listing { return l }
        return try envelope(WireMarketListing.self, from: data)
    }

    /// POST /api/hub/market/{id}/buy { userId } → { ok, wallet }.
    public func buyListing(_ listingId: String) async throws -> WireMarketBuyResult {
        let data = try await postRaw("/api/hub/market/\(q(listingId))/buy", body: ["userId": userId])
        return try envelope(WireMarketBuyResult.self, from: data)
    }

    /// GET /api/hub/logs?limit=&kind= → { logs } desc.
    public func hubLogs(limit: Int, kind: String?) async throws -> WireHubLogsPage {
        var path = "/api/hub/logs?limit=\(limit)"
        if let kind, !kind.isEmpty { path += "&kind=\(q(kind))" }
        return try await get(path)
    }

    /// GET /api/hub/apps/{appId}/install?userId= — appId is the numeric matrix id as a string.
    public func appInstallState(appId: String) async throws -> WireAppInstallState {
        try await get("/api/hub/apps/\(q(appId))/install?userId=\(q(userId))")
    }

    /// POST /api/hub/apps/{appId}/install { userId } — idempotent connect.
    public func installApp(appId: String) async throws -> WireAppInstallResult {
        let data = try await postRaw("/api/hub/apps/\(q(appId))/install", body: ["userId": userId])
        return try envelope(WireAppInstallResult.self, from: data)
    }

    /// DELETE /api/hub/apps/{appId}/install { userId } — hard remove.
    public func uninstallApp(appId: String) async throws -> WireAppInstallResult {
        let data = try await deleteRaw("/api/hub/apps/\(q(appId))/install", body: ["userId": userId])
        return try envelope(WireAppInstallResult.self, from: data)
    }

    /// GET /api/hub/apps/{appId}/community?userId= → { conversation|null, memberCount, joined }.
    public func appCommunity(appId: String) async throws -> WireAppCommunity {
        try await get("/api/hub/apps/\(q(appId))/community?userId=\(q(userId))")
    }

    /// POST /api/hub/apps/{appId}/community { userId } — auto-provisions the group; founder = admin.
    public func joinAppCommunity(appId: String) async throws -> WireAppCommunity {
        let data = try await postRaw("/api/hub/apps/\(q(appId))/community", body: ["userId": userId])
        return try envelope(WireAppCommunity.self, from: data)
    }

    // ── REM-B — group admin (web group-info-sheet.tsx parity) ──

    /// PATCH /api/conversations/{id} { requesterId, name?/photo?/broadcast? }
    /// — admin-only group meta. `photo` is an "/api/uploads/<file>" path
    /// ('' clears); `broadcast` toggles announcement mode. Verify against the
    /// live route: name 1-GROUP_NAME_MAX, photo must match the stored-path
    /// regex, 403s surface verbatim.
    public func patchConversation(
        _ conversationId: String,
        requesterId: String,
        name: String? = nil,
        photo: String?? = nil,
        broadcast: Bool? = nil,
        screenPrivacy: Bool? = nil,
    ) async throws -> WireConversationSummary {
        var body: [String: Any] = ["requesterId": requesterId]
        if let name { body["name"] = name }
        if let photo { body["photo"] = photo ?? "" } // nil-in-optional = clear ('')
        if let broadcast { body["broadcast"] = broadcast }
        if let screenPrivacy { body["screenPrivacy"] = screenPrivacy }
        let data = try await patchRaw("/api/conversations/\(q(conversationId))", body: body)
        return try WireConversationEnvelope.extract(from: data)
    }

    /// POST /api/conversations/{id}/members { requesterId, userIds } —
    /// admin-only add (dups deduped server-side) → { conversation, added }.
    public func addMembers(_ conversationId: String, requesterId: String, userIds: [String]) async throws -> [String] {
        let data = try await postRaw(
            "/api/conversations/\(q(conversationId))/members",
            body: ["requesterId": requesterId, "userIds": userIds],
        )
        return try envelope(WireMembersAdded.self, from: data).added ?? []
    }

    /// PATCH /api/conversations/{id}/members { requesterId, userId, role } —
    /// admin-only role set ("admin" | "member"); last-admin demote 400s.
    /// → { conversation } refreshed detail.
    public func setMemberRole(_ conversationId: String, requesterId: String, userId: String, role: String) async throws -> WireConversationSummary {
        let data = try await patchRaw(
            "/api/conversations/\(q(conversationId))/members",
            body: ["requesterId": requesterId, "userId": userId, "role": role],
        )
        return try WireConversationEnvelope.extract(from: data)
    }

    /// DELETE /api/conversations/{id}/members/{userId} { requesterId } —
    /// kick a NON-admin member (self-kick 400, admin target 403).
    public func kickMember(_ conversationId: String, requesterId: String, userId: String) async throws {
        try await deleteEmpty(
            "/api/conversations/\(q(conversationId))/members/\(q(userId))",
            body: ["requesterId": requesterId],
        )
    }

    /// DELETE /api/conversations/{id}/members { requesterId } — leave the
    /// group (last-admin succession server-side) → { ok, remainingMembers,
    /// promotedUserId? }.
    public func leaveGroup(_ conversationId: String, requesterId: String) async throws -> WireLeaveResult {
        let data = try await deleteRaw(
            "/api/conversations/\(q(conversationId))/members",
            body: ["requesterId": requesterId],
        )
        return try envelope(WireLeaveResult.self, from: data)
    }

    /// POST /api/conversations/{id}/invite { requesterId, regenerate? } —
    /// admin-only lazy-create/rotate → { inviteCode }. The shareable link is
    /// "pulse://invite/<code>" (deep-link F-DL) with the web's /join/<code>
    /// shape mirrored by JoinInviteSheet.
    public func inviteCreate(_ conversationId: String, requesterId: String, regenerate: Bool) async throws -> String {
        let data = try await postRaw(
            "/api/conversations/\(q(conversationId))/invite",
            body: ["requesterId": requesterId, "regenerate": regenerate],
        )
        let decoded = try envelope(WireInviteCode.self, from: data)
        guard let code = decoded.inviteCode, !code.isEmpty else {
            throw Failure(kind: .validation, message: "Invite response missing the code")
        }
        return code
    }

    /// PATCH /api/conversations/{id}/disappearing { userId, ttlSeconds } —
    /// participant-level TTL (presets 0 · 1d · 1w · 30d server-gated).
    public func setDisappearingTtl(_ conversationId: String, userId: String, ttlSeconds: Int) async throws -> WireConversationSummary {
        let data = try await patchRaw(
            "/api/conversations/\(q(conversationId))/disappearing",
            body: ["userId": userId, "ttlSeconds": ttlSeconds],
        )
        return try WireConversationEnvelope.extract(from: data)
    }

    /// PATCH /api/conversations/{id}/slow-mode { userId, seconds } —
    /// admin-only member throttle (presets 0/5/10/30/60/300).
    public func setSlowMode(_ conversationId: String, userId: String, seconds: Int) async throws -> Int {
        let data = try await patchRaw(
            "/api/conversations/\(q(conversationId))/slow-mode",
            body: ["userId": userId, "seconds": seconds],
        )
        return try envelope(WireSlowModeResult.self, from: data).slowModeSeconds ?? seconds
    }

    // ── REM-B F-MS-18 — scheduled sends ──────────────────────

    /// GET /api/conversations/{id}/scheduled?userId= — the caller's OWN
    /// pending rows, soonest first (plus dispatch-refused ones).
    public func scheduledMessages(conversationId: String) async throws -> [WireScheduledItem] {
        let page: WireScheduledPage = try await get(
            "/api/conversations/\(q(conversationId))/scheduled?userId=\(q(userId))",
        )
        return page.items ?? []
    }

    /// POST /api/conversations/{id}/scheduled { senderId, content,
    /// scheduledAt } → 201 { item }. Window guard server-side: 30 s – 30 d.
    public func scheduleMessage(conversationId: String, content: String, scheduledAtIso: String) async throws -> WireScheduledItem {
        let data = try await postRaw(
            "/api/conversations/\(q(conversationId))/scheduled",
            body: ["senderId": userId, "content": content, "scheduledAt": scheduledAtIso],
        )
        return try envelope(WireScheduledItem.self, from: data)
    }

    /// DELETE /api/scheduled/{id} { requesterId } — sender-only cancel.
    public func cancelScheduled(_ id: String) async throws {
        try await deleteEmpty("/api/scheduled/\(q(id))", body: ["requesterId": userId])
    }

    // ── R1-W2B — translation + quick phrases (F-MD-06 / F-MS-29) ──

    /// POST /api/messages/{id}/translate { userId, lang? } → { message }.
    /// LLM translation persisted per language (translate/route.ts:36-120);
    /// a cached lang returns instantly, 502 = service down. The fresh row
    /// carries message.translations — relays as translation:added to peers.
    public func translateMessage(id: String, lang: String = "en") async throws -> WireChatMessage {
        let data = try await postRaw(
            "/api/messages/\(q(id))/translate",
            body: ["userId": userId, "lang": lang],
            timeoutCap: 30,
        )
        return try WireMessageEnvelope.extract(from: data)
    }

    /// GET /api/users/{id}/phrases → { phrases: [{id,text,position}] }
    /// (phrases/route.ts:19-33, position asc, ≤12 rows).
    public func quickPhrases() async throws -> [WireQuickPhrase] {
        let page: WirePhrasesPage = try await get("/api/users/\(q(userId))/phrases")
        return page.phrases ?? []
    }

    /// POST /api/users/{id}/phrases { text } → 201 { phrase } (append at
    /// end; 400 on >120 chars or >12 rows — server copy surfaces verbatim).
    public func createQuickPhrase(text: String) async throws -> WireQuickPhrase {
        let data = try await postRaw("/api/users/\(q(userId))/phrases", body: ["text": text])
        return try envelope(WirePhraseEnvelope.self, from: data).phrase
            ?? envelope(WireQuickPhrase.self, from: data)
    }

    /// DELETE /api/users/{id}/phrases?phraseId= → { ok } (owner-guarded).
    public func deleteQuickPhrase(_ phraseId: String) async throws {
        try await deleteEmpty("/api/users/\(q(userId))/phrases?phraseId=\(q(phraseId))", body: ["userId": userId])
    }

    // ── plumbing ─────────────────────────────────────────────
    /// URL builder that keeps query strings intact (appendingPathComponent
    /// would percent-encode "?", breaking every ?userId= route).
    private func url(_ path: String) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
            ?? URLComponents(string: PulseEndpoints.gatewayURL.absoluteString)!
        if let queryStart = path.firstIndex(of: "?") {
            components.path += String(path[..<queryStart])
            components.query = String(path[path.index(after: queryStart)...])
        } else {
            components.path += path
        }
        return components.url ?? baseURL
    }

    private func get<T: Decodable>(_ path: String, as type: T.Type = T.self) async throws -> T {
        var request = URLRequest(url: url(path))
        request.httpMethod = "GET"
        return try await run(request)
    }

    private func postRaw(_ path: String, body: [String: Any], timeoutCap: TimeInterval = 6) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request, timeoutCap: timeoutCap)
    }

    private func patchRaw(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    /// Wave 6 — PUT with a JSON body (folder membership full-replace).
    private func putRaw(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    /// Wave 6 — DELETE with a JSON body + decoded verdict (channel leave).
    private func deleteRaw(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func patchEmpty(_ path: String, body: [String: Any]) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        _ = try await send(request)
    }

    private func deleteEmpty(_ path: String, body: [String: Any]) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        _ = try await send(request)
    }

    private func postEmpty(_ path: String, body: [String: Any]? = nil) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        _ = try await send(request)
    }

    private func run<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await send(request)
        return try decoder.decode(T.self, from: data)
    }

    /// Shared transport: status check + tolerant error-body enrichment.
    /// `timeoutCap` caps the request timeout (default 6 s — the house
    /// fail-fast rule); ONLY the voice-transcribe call raises it to 60 s.
    private func send(_ request: URLRequest, timeoutCap: TimeInterval = 6) async throws -> Data {
        // Wave 8 — Bearer attach lives on the request-building seam
        // (unit-tested): every outbound call, no exceptions.
        var request = Self.authorized(request, token: authToken)
        // Fail fast — an unreachable gateway must never spin for a minute.
        request.timeoutInterval = min(request.timeoutInterval, timeoutCap)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw Failure(kind: .network, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            var kind = Self.kind(for: http.statusCode)
            var message = String(data: data, encoding: .utf8)
            var code: String?
            var suggestion: String?
            var retryAfter: Int?
            if let body = try? decoder.decode(WireErrorBody.self, from: data) {
                if let error = body.error, !error.isEmpty { message = error }
                code = body.code
                suggestion = body.suggestion
                retryAfter = body.retryAfter
                if body.code == "username_taken" { kind = .validation }
            }
            // F-MS-20 — the Retry-After header backs the body field (web
            // apiJson parity: body number wins, else parse the header).
            if retryAfter == nil, let raw = http.value(forHTTPHeaderField: "Retry-After"),
               let seconds = Int(raw), seconds > 0 {
                retryAfter = seconds
            }
            throw Failure(kind: kind, message: message, code: code, suggestion: suggestion, status: http.statusCode, retryAfter: retryAfter)
        }
        return data
    }

    /// Wave 8 — the request-building auth seam (unit-tested in
    /// Wave8WireTests): attaches `Authorization: Bearer <token>` when a
    /// session token exists, returns the request untouched when it does
    /// not (the server's optional-verify proxy accepts header-less calls).
    static func authorized(_ request: URLRequest, token: String?) -> URLRequest {
        var request = request
        guard let token, !token.isEmpty else { return request }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    static func kind(for status: Int) -> Failure.Kind {
        switch status {
        case 401: return .auth
        case 403: return .forbidden
        case 404: return .notFound
        case 422: return .validation
        case 429: return .rateLimited
        case 500...599: return .server
        default: return .unknown
        }
    }
}

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

        public init(kind: Kind, message: String?, code: String? = nil, suggestion: String? = nil, status: Int? = nil) {
            self.kind = kind
            self.message = message
            self.code = code
            self.suggestion = suggestion
            self.status = status
        }
    }

    public let baseURL: URL
    public let userId: String
    private let session: URLSession
    private let decoder = JSONDecoder()

    public init(baseURL: URL, userId: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.userId = userId
        self.session = session
    }

    /// Identity-independent client (onboarding: no viewer yet — users routes
    /// never need one).
    public init(baseURL: URL, session: URLSession = .shared) {
        self.init(baseURL: baseURL, userId: "", session: session)
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
        return page.wallet
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
        kind: String? = nil
    ) async throws -> WireChatMessage {
        var body: [String: Any] = ["senderId": userId, "content": content, "kind": kind ?? "text"]
        if let replyToId { body["replyToId"] = replyToId }
        if let parentId { body["parentId"] = parentId }
        if let imagePath { body["imagePath"] = imagePath }
        if let audioPath { body["audioPath"] = audioPath }
        if let durationMs { body["durationMs"] = durationMs }
        if let filePath { body["filePath"] = filePath }
        if let fileName { body["fileName"] = fileName }
        if let fileSize { body["fileSize"] = fileSize }
        let data = try await postRaw("/api/conversations/\(conversationId)/messages", body: body)
        return try WireMessageEnvelope.extract(from: data)
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

    /// N3-b — create a new identity. 409 username_taken surfaces code + suggestion.
    public func createUser(name: String, color: String, username: String? = nil) async throws -> WireUser {
        var body: [String: Any] = ["name": name, "color": color]
        if let username, !username.isEmpty { body["username"] = username }
        let data = try await postRaw("/api/users", body: body)
        return try decoder.decode(WireUserEnvelope.self, from: data).user
    }

    public func block(userId target: String) async throws {
        try await postEmpty("/api/users/\(target)/block", body: ["userId": userId])
    }
    public func unblock(userId target: String) async throws {
        try await postEmpty("/api/users/\(target)/unblock", body: ["userId": userId])
    }
    public func report(userId target: String, reason: String, details: String?) async throws {
        var body: [String: Any] = ["userId": userId, "reason": reason]
        if let details { body["details"] = details }
        try await postEmpty("/api/users/\(target)/report", body: body)
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

    private func postRaw(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func patchRaw(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PATCH"
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
    private func send(_ request: URLRequest) async throws -> Data {
        var request = request
        // Fail fast — an unreachable gateway must never spin for a minute.
        request.timeoutInterval = min(request.timeoutInterval, 6)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw Failure(kind: .network, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            var kind = Self.kind(for: http.statusCode)
            var message = String(data: data, encoding: .utf8)
            var code: String?
            var suggestion: String?
            if let body = try? decoder.decode(WireErrorBody.self, from: data) {
                if let error = body.error, !error.isEmpty { message = error }
                code = body.code
                suggestion = body.suggestion
                if body.code == "username_taken" { kind = .validation }
            }
            throw Failure(kind: kind, message: message, code: code, suggestion: suggestion, status: http.statusCode)
        }
        return data
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

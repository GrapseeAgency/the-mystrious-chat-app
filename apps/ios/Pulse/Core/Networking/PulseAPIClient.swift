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

    public func messages(conversationId: String, limit: Int = 200, before: String? = nil) async throws -> WireMessagesPage {
        var path = "/api/conversations/\(conversationId)/messages?limit=\(limit)"
        if let before { path += "&before=\(before)" }
        return try await get(path)
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
    public func sendMessage(conversationId: String, content: String, replyToId: String? = nil) async throws -> WireChatMessage {
        var body: [String: Any] = ["senderId": userId, "content": content, "kind": "text"]
        if let replyToId { body["replyToId"] = replyToId }
        let data = try await postRaw("/api/conversations/\(conversationId)/messages", body: body)
        return try WireMessageEnvelope.extract(from: data)
    }

    public func markRead(conversationId: String) async throws { try await postEmpty("/api/conversations/\(conversationId)/read") }
    public func togglePin(conversationId: String, pinned: Bool) async throws { try await postEmpty("/api/conversations/\(conversationId)/pin", body: ["pinned": pinned]) }
    public func setMuted(conversationId: String, muted: Bool) async throws { try await postEmpty("/api/conversations/\(conversationId)/mute", body: ["muted": muted]) }
    public func archive(conversationId: String, archived: Bool) async throws { try await postEmpty("/api/conversations/\(conversationId)/archive", body: ["archived": archived]) }

    /// N3-b — per-viewer unread dot (PATCH, body { userId, on }).
    public func markUnread(conversationId: String, on: Bool) async throws {
        try await patchEmpty("/api/conversations/\(conversationId)/mark-unread", body: ["userId": userId, "on": on])
    }

    /// N3-b — toggle an emoji reaction. Server replies with the FRESH message.
    public func react(messageId: String, emoji: String) async throws -> WireChatMessage {
        let data = try await postRaw("/api/messages/\(messageId)/react", body: ["userId": userId, "emoji": emoji])
        return try WireMessageEnvelope.extract(from: data)
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

    private func patchEmpty(_ path: String, body: [String: Any]) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PATCH"
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

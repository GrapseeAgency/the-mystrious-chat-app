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

        public init(kind: Kind, message: String?, code: String? = nil, suggestion: String? = nil) {
            self.kind = kind
            self.message = message
            self.code = code
            self.suggestion = suggestion
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
        let page: WireConversationsPage = try get("/api/conversations?userId=\(userId)")
        return page.conversations
    }

    public func messages(conversationId: String, limit: Int = 200, before: String? = nil) async throws -> WireMessagesPage {
        var path = "/api/conversations/\(conversationId)/messages?limit=\(limit)"
        if let before { path += "&before=\(before)" }
        return try get(path)
    }

    /// N3-b — every identity on this Pulse (contacts + onboarding picker).
    public func users() async throws -> [WireUser] {
        let page: WireUsersPage = try get("/api/users")
        return page.users
    }

    /// N3-b — Hub wallet (real coins / gems / streak numbers).
    public func wallet() async throws -> WireWallet {
        let page: WireWalletPage = try get("/api/hub/wallet?userId=\(userId)")
        return page.wallet
    }

    // ── writes ───────────────────────────────────────────────
    public func sendMessage(conversationId: String, content: String, replyToId: String? = nil) async throws -> WireChatMessage {
        var body: [String: Any] = ["senderId": userId, "content": content, "kind": "text"]
        if let replyToId { body["replyToId"] = replyToId }
        let data = try await postRaw("/api/conversations/\(conversationId)/messages", body: body)
        return try WireMessageEnvelope.extract(from: data)
    }

    public func markRead(conversationId: String) async throws { try postEmpty("/api/conversations/\(conversationId)/read") }
    public func togglePin(conversationId: String, pinned: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/pin", body: ["pinned": pinned]) }
    public func setMuted(conversationId: String, muted: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/mute", body: ["muted": muted]) }
    public func archive(conversationId: String, archived: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/archive", body: ["archived": archived]) }

    /// N3-b — per-viewer unread dot (PATCH, body { userId, on }).
    public func markUnread(conversationId: String, on: Bool) async throws {
        try patchEmpty("/api/conversations/\(conversationId)/mark-unread", body: ["userId": userId, "on": on])
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
        try postEmpty("/api/users/\(target)/block", body: ["userId": userId])
    }
    public func unblock(userId target: String) async throws {
        try postEmpty("/api/users/\(target)/unblock", body: ["userId": userId])
    }
    public func report(userId target: String, reason: String, details: String?) async throws {
        var body: [String: Any] = ["userId": userId, "reason": reason]
        if let details { body["details"] = details }
        try postEmpty("/api/users/\(target)/report", body: body)
    }

    // ── plumbing ─────────────────────────────────────────────
    /// URL builder that keeps query strings intact (appendingPathComponent
    /// would percent-encode "?", breaking every ?userId= route).
    private func url(_ path: String) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
            ?? URLComponents(string: "http://localhost:81")!
        if let queryStart = path.firstIndex(of: "?") {
            components?.path += String(path[..<queryStart])
            components?.query = String(path[path.index(after: queryStart)...])
        } else {
            components?.path += path
        }
        return components?.url ?? baseURL
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
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw Failure(kind: .network, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            var failure = Failure(kind: Self.kind(for: http.statusCode), message: String(data: data, encoding: .utf8))
            if let body = try? decoder.decode(WireErrorBody.self, from: data) {
                if let error = body.error, !error.isEmpty { failure.message = error }
                failure.code = body.code
                failure.suggestion = body.suggestion
                if body.code == "username_taken" { failure.kind = .validation }
            }
            throw failure
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

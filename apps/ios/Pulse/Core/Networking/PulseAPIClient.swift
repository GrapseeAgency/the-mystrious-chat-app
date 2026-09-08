import Foundation

/// REAL URLSession REST client — the exact routes the web app calls,
/// identified by `userId` (web parity), failures mapped to the same kinds
/// as Android's PulseResult.
public struct PulseAPIClient: Sendable {
    public struct Failure: Error, Equatable {
        public enum Kind: Equatable { case network, auth, forbidden, rateLimited, notFound, validation, server, unknown }
        public let kind: Kind
        public let message: String?
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

    // ── writes ───────────────────────────────────────────────
    public func sendMessage(conversationId: String, content: String) async throws -> WireChatMessage {
        try post("/api/conversations/\(conversationId)/messages",
                 body: ["senderId": userId, "content": content, "kind": "text"])
    }

    public func markRead(conversationId: String) async throws { try postEmpty("/api/conversations/\(conversationId)/read") }
    public func togglePin(conversationId: String, pinned: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/pin", body: ["pinned": pinned]) }
    public func setMuted(conversationId: String, muted: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/mute", body: ["muted": muted]) }
    public func archive(conversationId: String, archived: Bool) async throws { try postEmpty("/api/conversations/\(conversationId)/archive", body: ["archived": archived]) }
    public func block(userId target: String) async throws { try postEmpty("/api/users/\(target)/block") }
    public func unblock(userId target: String) async throws { try postEmpty("/api/users/\(target)/unblock") }
    public func report(userId target: String, reason: String, details: String?) async throws {
        var body: [String: Any] = ["reason": reason]
        if let details { body["details"] = details }
        try postEmpty("/api/users/\(target)/report", body: body)
    }

    // ── plumbing ─────────────────────────────────────────────
    private func get<T: Decodable>(_ path: String, as type: T.Type = T.self) async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "GET"
        return try await run(request)
    }

    private func post<T: Decodable>(_ path: String, body: [String: Any]) async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await run(request)
    }

    private func postEmpty(_ path: String, body: [String: Any]? = nil) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw Self.failure(response, path)
        }
    }

    private func run<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw Failure(kind: .network, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            throw Failure(kind: Self.kind(for: http.statusCode), message: String(data: data, encoding: .utf8))
        }
        return try decoder.decode(T.self, from: data)
    }

    private static func failure(_ response: URLResponse, _ path: String) -> Failure {
        guard let http = response as? HTTPURLResponse else { return Failure(kind: .network, message: path) }
        return Failure(kind: kind(for: http.statusCode), message: path)
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

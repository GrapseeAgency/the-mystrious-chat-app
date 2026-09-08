import Foundation

/// URLSession-based REST client — wire parity with the Next.js API.
/// Real-time uses Socket.IO (shared relay), calls use WebRTC (later waves).
public struct PulseAPIClient: Sendable {
    public struct Failure: Error, Equatable {
        public enum Kind: Equatable { case network, auth, forbidden, rateLimited, notFound, validation, server, unknown }
        public let kind: Kind
        public let message: String?
    }

    public let baseURL: URL
    private let session: URLSession
    private let decoder = JSONDecoder()

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// GET → decoded model; HTTP status maps to the same failure kinds as Android's PulseResult.
    public func get<T: Decodable>(_ path: String, as type: T.Type) async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "GET"
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw Failure(kind: .network, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            throw Failure(kind: Self.kind(for: http.statusCode), message: String(data: data, encoding: .utf8))
        }
        return try decoder.decode(T.self, from: data)
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

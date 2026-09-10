import Foundation

/// Gateway endpoints — iOS mirror of Android `PulseEndpoints`.
///
/// NO fake host is baked: a static CDN (raw.githubusercontent) cannot execute
/// `/api/*`, so pointing REST at one is a guaranteed 404. Instead the origin is
/// user-configured (Settings → Connection, persisted in UserDefaults) and both
/// REST and realtime derive from it. Nothing localhost, nothing 10.0.2.2 —
/// when no origin is set the client is honestly offline.
public enum PulseEndpoints {
    private static let baseKey = "net.serverBase"

    /// User-persisted origin ("https://host"), nil = offline-first.
    public static var configuredBase: String? {
        get {
            let raw = UserDefaults.standard.string(forKey: baseKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let raw, !raw.isEmpty else { return nil }
            return String(raw.dropLastWhile { $0 == "/" })
        }
        set {
            let clean = newValue?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let clean, !clean.isEmpty {
                UserDefaults.standard.set(String(clean.dropLastWhile { $0 == "/" }), forKey: baseKey)
            } else {
                UserDefaults.standard.removeObject(forKey: baseKey)
            }
        }
    }

    /// True once a real origin is configured.
    public static var isConfigured: Bool { configuredBase != nil }

    /// REST gateway base. Unconfigured = a fast-failing placeholder so callers
    /// surface "unreachable" honestly instead of a misleading CDN 404.
    public static var gatewayURL: URL {
        if let base = configuredBase, let url = URL(string: base) {
            return url
        }
        return URL(string: "https://offline.pulse.unconfigured")!
    }

    /// Socket.IO relay — same origin as REST; the sandbox-gateway convention
    /// `XTransformPort=3003` is appended per-request by PulseSocketClient
    /// (the edge's `/socket.io/` path rule 308-redirects, which breaks WS
    /// upgrades; the query-param route connects, handshakes and upgrades).
    public static var socketURL: URL? {
        guard let base = configuredBase, let url = URL(string: base) else { return nil }
        return url
    }
}

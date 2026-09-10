import Foundation

/// Gateway endpoints — iOS mirror of Android `PulseEndpoints`.
///
/// NO fake host is baked: a static CDN (raw.githubusercontent) cannot execute
/// `/api/*`, so pointing REST at one is a guaranteed 404. Resolution order:
///   1. User-configured origin (Settings → Connection, persisted in
///      UserDefaults) — always wins.
///   2. Wave 0 manifest override: `{cdn}/update-manifest.json` may carry
///      non-empty `gateway`/`socket` fields; when adopted they persist in the
///      Keychain ("endpoints.override") and re-apply on every launch, so an
///      offline start still retargets (Android parity).
///   3. Nothing configured → the client is honestly offline (fast-failing
///      placeholder, never a misleading CDN 404).
///
/// The SAME origin drives realtime: PulseSocketClient appends the
/// sandbox-gateway convention `XTransformPort=3003` per-request (the edge's
/// `/socket.io/` path rule 308-redirects, which breaks WS upgrades; the
/// query-param route connects, handshakes and upgrades — verified).
public enum PulseEndpoints {
    private static let baseKey = "net.serverBase"

    /// Distribution home of update-manifest.json — a static file, the one
    /// thing the CDN can serve. Solely used for the Wave 0 override probe.
    static let cdnBase = "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main"

    /// Trim trailing slashes without touching the leading part.
    private static func withoutTrailingSlashes(_ s: String) -> String {
        var t = Substring(s)
        while t.hasSuffix("/") { t = t.dropLast() }
        return String(t)
    }

    /// User-persisted origin ("https://host"), nil = not user-configured.
    public static var configuredBase: String? {
        get {
            let raw = UserDefaults.standard.string(forKey: baseKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let raw, !raw.isEmpty else { return nil }
            return withoutTrailingSlashes(raw)
        }
        set {
            let clean = newValue?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let clean, !clean.isEmpty {
                UserDefaults.standard.set(withoutTrailingSlashes(clean), forKey: baseKey)
            } else {
                UserDefaults.standard.removeObject(forKey: baseKey)
            }
        }
    }

    /// True once a real origin is configured (user field or manifest).
    public static var isConfigured: Bool { gatewayBase != nil }

    /// Manifest-adopted overrides (in-memory mirror of the Keychain copy).
    static var manifestGateway: String?
    static var manifestSocket: String?

    /// Effective REST base: user field > manifest override > nil (offline).
    static var gatewayBase: String? {
        if let configuredBase { return configuredBase }
        return manifestGateway
    }

    /// REST gateway base. Unconfigured = a fast-failing placeholder so callers
    /// surface "unreachable" honestly instead of a misleading CDN 404.
    public static var gatewayURL: URL {
        if let base = gatewayBase, let url = URL(string: base) {
            return url
        }
        return URL(string: "https://offline.pulse.unconfigured")!
    }

    /// Socket.IO relay — same origin as REST; the sandbox-gateway convention
    /// `XTransformPort=3003` is appended per-request by PulseSocketClient.
    /// nil = realtime disabled (offline-first, zero reconnect spam).
    public static var socketURL: URL? {
        if let configuredBase, let url = URL(string: configuredBase) { return url }
        let base = manifestSocket ?? manifestGateway
        guard let base, let url = URL(string: base) else { return nil }
        return url
    }

    /// Trims whitespace and drops empty results.
    private static func clean(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }

    /// Applies a manifest override. Blank strings are ignored (a half-filled
    /// manifest must never clobber a good value with an empty base). The user
    /// field always wins: overrides only fill the gaps when it is unset.
    public static func applyOverride(gateway: String?, socket: String?) {
        if let gateway = clean(gateway) { manifestGateway = withoutTrailingSlashes(gateway) }
        if let socket = clean(socket) { manifestSocket = withoutTrailingSlashes(socket) }
    }

    /// Re-applies the override persisted by a previous launch (best effort).
    public static func loadPersistedOverride() {
        guard let data = PulseKeychain.shared.load(account: PulseKeychain.endpointsAccount) else { return }
        struct StoredOverride: Decodable {
            let gateway: String?
            let socket: String?
        }
        guard let stored = try? JSONDecoder().decode(StoredOverride.self, from: data) else { return }
        applyOverride(gateway: stored.gateway, socket: stored.socket)
    }

    /// Fetches `{base}/update-manifest.json` (6s timeout) and, when it carries
    /// non-empty `gateway`/`socket` strings, applies + persists the override.
    /// `base` defaults to the distribution CDN (the manifest lives there).
    /// Silent failure is REQUIRED — an unreachable manifest must never block
    /// or break the launch path.
    public static func fetchManifestOverride(base: URL? = nil) async {
        let probeBase = base ?? URL(string: cdnBase)!
        var request = URLRequest(url: probeBase.appendingPathComponent("update-manifest.json"))
        request.timeoutInterval = 6
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse,
              (200 ..< 300).contains(http.statusCode) else { return }

        struct UpdateManifest: Decodable {
            let gateway: String?
            let socket: String?
        }
        guard let manifest = try? JSONDecoder().decode(UpdateManifest.self, from: data) else { return }
        let gateway = clean(manifest.gateway)
        let socket = clean(manifest.socket)
        guard gateway != nil || socket != nil else { return }

        applyOverride(gateway: gateway, socket: socket ?? gateway)

        // Persist for offline relaunches (blank = field not overridden yet).
        let payload: [String: String] = [
            "gateway": gateway ?? "",
            "socket": socket ?? "",
        ]
        if let encoded = try? JSONSerialization.data(withJSONObject: payload) {
            _ = PulseKeychain.shared.save(encoded, account: PulseKeychain.endpointsAccount)
        }
    }
}

import Foundation

/// One TURN/STUN relay from the deployment manifest `ice` array (Wave 3-HW).
/// `urls` accepts a single string or an array (both wire forms decode).
public struct PulseIceServer: Codable, Equatable {
    public var urls: [String]
    public var username: String?
    public var credential: String?

    private enum CodingKeys: String, CodingKey {
        case urls, username, credential
    }

    public init(urls: [String], username: String? = nil, credential: String? = nil) {
        self.urls = urls
        self.username = username
        self.credential = credential
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let list = try? container.decode([String].self, forKey: .urls) {
            urls = list
        } else if let single = try? container.decode(String.self, forKey: .urls) {
            urls = [single]
        } else {
            throw DecodingError.dataCorruptedError(forKey: .urls, in: container, debugDescription: "urls must be a string or array")
        }
        username = try? container.decodeIfPresent(String.self, forKey: .username)
        credential = try? container.decodeIfPresent(String.self, forKey: .credential)
    }
}

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
///      Wave 3-HW: an optional `ice` array (TURN/STUN + credentials) is
///      adopted the same way and consumed by the call engines.
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

    /// Manifest-adopted TURN/STUN JSON (Wave 3-HW) — raw `ice` array exactly
    /// as served; nil = engines keep their built-in Google STUN defaults.
    static var manifestIceJSON: String?

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

    /// Wave 1 — message media URL ({gateway}/api/uploads/{filePath|imagePath},
    /// spec §1.1 "Serve"). Same resolution the web <img src> applies
    /// (mirrors PulseTheme.photoURL): absolute URLs pass through, "/"-rooted
    /// paths resolve against the gateway, bare stored paths hang off
    /// /api/uploads/. nil = nothing usable (offline placeholder gateway).
    public static func mediaURL(_ filePath: String?) -> URL? {
        guard let filePath, !filePath.isEmpty else { return nil }
        if filePath.hasPrefix("http://") || filePath.hasPrefix("https://") {
            return URL(string: filePath)
        }
        if filePath.hasPrefix("/") {
            return URL(string: filePath, relativeTo: gatewayURL)?.absoluteURL
        }
        return URL(string: "\(gatewayURL.absoluteString)/api/uploads/\(filePath)")
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

    /// Applies the manifest `ice` override (Wave 3-HW). The JSON is validated
    /// by decoding it as `[PulseIceServer]` — invalid or empty arrays are
    /// ignored so a broken manifest can never strip a working STUN set.
    public static func applyIceOverride(_ json: String?) {
        guard let json = clean(json),
              let data = json.data(using: .utf8),
              let servers = try? JSONDecoder().decode([PulseIceServer].self, from: data),
              !servers.isEmpty else { return }
        manifestIceJSON = json
    }

    /// Re-applies the override persisted by a previous launch (best effort).
    public static func loadPersistedOverride() {
        guard let data = PulseKeychain.shared.load(account: PulseKeychain.endpointsAccount) else { return }
        struct StoredOverride: Decodable {
            let gateway: String?
            let socket: String?
            let iceJson: String?
        }
        guard let stored = try? JSONDecoder().decode(StoredOverride.self, from: data) else { return }
        applyOverride(gateway: stored.gateway, socket: stored.socket)
        applyIceOverride(stored.iceJson)
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
            let ice: [PulseIceServer]?
        }
        guard let manifest = try? JSONDecoder().decode(UpdateManifest.self, from: data) else { return }
        let gateway = clean(manifest.gateway)
        let socket = clean(manifest.socket)
        let iceJSON: String? = manifest.ice.flatMap { servers in
            guard !servers.isEmpty,
                  let encoded = try? JSONEncoder().encode(servers) else { return nil }
            return String(data: encoded, encoding: .utf8)
        }
        guard gateway != nil || socket != nil || iceJSON != nil else { return }

        applyOverride(gateway: gateway, socket: socket ?? gateway)
        applyIceOverride(iceJSON)

        // Persist for offline relaunches (blank = field not overridden yet).
        let payload: [String: String] = [
            "gateway": gateway ?? "",
            "socket": socket ?? "",
            "iceJson": iceJSON ?? "",
        ]
        if let encoded = try? JSONSerialization.data(withJSONObject: payload) {
            _ = PulseKeychain.shared.save(encoded, account: PulseKeychain.endpointsAccount)
        }
    }
}

import Foundation

/// Gateway endpoints — iOS mirror of Android `PulseEndpoints`.
/// The default base is the repo's public HTTPS CDN: reachable from ANY device
/// (the old `http://localhost:81` default only ever worked on the simulator).
///
/// Wave 0 — the baked defaults are OVERRIDABLE at runtime: the launch sequence
/// fetches `{gateway}/update-manifest.json` and, when it carries non-empty
/// `gateway`/`socket` fields, applies and persists them (PulseKeychain,
/// "endpoints.override"). The persisted copy is re-applied first on every
/// launch so the override survives offline starts (Android parity).
public enum PulseEndpoints {
    /// Baked default REST gateway base (swap at build time for a release).
    private static let bakedGateway = "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main"

    /// Runtime-overridable bases. Reads are plain String loads; writes go
    /// through `applyOverride` (single launch-writer + tests).
    static var gatewayBase: String = bakedGateway
    static var socketBase: String?

    /// REST gateway base (Next.js API routes through the gateway).
    public static var gatewayURL: URL {
        URL(string: gatewayBase) ?? URL(string: bakedGateway)!
    }

    /// Socket.IO relay — nil = realtime disabled (offline-first client, zero
    /// reconnect spam against a dead address). Set via the manifest override
    /// once a live relay is deployed.
    public static var socketURL: URL? {
        guard let socketBase else { return nil }
        return URL(string: socketBase)
    }

    /// Applies a manifest override. Blank strings are ignored (a half-filled
    /// manifest must never clobber a good baked default with an empty base).
    public static func applyOverride(gateway: String?, socket: String?) {
        if let gateway = Self.clean(gateway) { gatewayBase = gateway }
        if let socket = Self.clean(socket) { socketBase = socket }
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
    /// Silent failure is REQUIRED — an unreachable manifest must never block
    /// or break the launch path.
    public static func fetchManifestOverride(base: URL) async {
        var request = URLRequest(url: base.appendingPathComponent("update-manifest.json"))
        request.timeoutInterval = 6
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse,
              (200 ..< 300).contains(http.statusCode) else { return }

        struct UpdateManifest: Decodable {
            let gateway: String?
            let socket: String?
        }
        guard let manifest = try? JSONDecoder().decode(UpdateManifest.self, from: data) else { return }
        let gateway = Self.clean(manifest.gateway)
        let socket = Self.clean(manifest.socket)
        guard gateway != nil || socket != nil else { return }

        applyOverride(gateway: gateway, socket: socket)

        // Persist for offline relaunches (blank = field not overridden yet).
        let payload: [String: String] = [
            "gateway": gateway ?? "",
            "socket": socket ?? "",
        ]
        if let encoded = try? JSONSerialization.data(withJSONObject: payload) {
            _ = PulseKeychain.shared.save(encoded, account: PulseKeychain.endpointsAccount)
        }
    }

    /// Trims whitespace and drops empty results.
    private static func clean(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }
}

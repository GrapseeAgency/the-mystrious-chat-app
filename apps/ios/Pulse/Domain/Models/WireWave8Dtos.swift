import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 8 — platform & hardening wire DTOs (spec §7).
// Mirror of the live-verified gateway contracts:
//   • POST /api/users            → 201 { user: WireUser, token }
//   • POST /api/users/login      → 200 { user, token } | 404 { error }
//   • GET  /api/settings?userId= → 200 { preferences: PulsePrefs }
//   • PATCH /api/settings        → 200 { preferences }
// Tolerant decode house style: only id-critical fields stay required;
// everything else decodes as nil/default so older relays never crash
// the surface. `token` decodes tolerantly (the web ignores it today).
// ─────────────────────────────────────────────────────────────

// MARK: - Session tokens (A-1)

/// { user, token } — create + login envelope. `token` is the raw 32-byte
/// hex session secret (shown/persisted exactly once; the server stores
/// only its sha256). Optional so a pre-token gateway still decodes.
public struct WireAuthEnvelope: Codable, Sendable {
    public let user: WireUser
    public let token: String?
}

// MARK: - PulsePrefs (settings blob)

/// Device-side mirror of repo-root src/lib/prefs-defaults.ts PulsePrefs.
/// Every field is optional: a missing key decodes as nil (defaults apply),
/// an unknown key is ignored by synthesis — the exact tolerance of the
/// web's mergePrefs. Values are kept as raw strings so invalid server
/// tokens ("xl" radius) can be clamped by PulseWave8Logic.mergedPrefs
/// instead of failing the whole decode.
public struct WirePulsePrefs: Codable, Equatable, Sendable {
    public var bubbleRadius: String?
    public var density: String?
    public var wallpaper: String?
    public var notifPreviews: Bool?
    public var notifSound: Bool?
    public var notifVibrate: Bool?
    public var lastSeenVisible: Bool?
    public var readReceipts: Bool?
    public var typingVisible: Bool?
    public var reducedMotion: Bool?

    public init(
        bubbleRadius: String? = nil,
        density: String? = nil,
        wallpaper: String? = nil,
        notifPreviews: Bool? = nil,
        notifSound: Bool? = nil,
        notifVibrate: Bool? = nil,
        lastSeenVisible: Bool? = nil,
        readReceipts: Bool? = nil,
        typingVisible: Bool? = nil,
        reducedMotion: Bool? = nil
    ) {
        self.bubbleRadius = bubbleRadius
        self.density = density
        self.wallpaper = wallpaper
        self.notifPreviews = notifPreviews
        self.notifSound = notifSound
        self.notifVibrate = notifVibrate
        self.lastSeenVisible = lastSeenVisible
        self.readReceipts = readReceipts
        self.typingVisible = typingVisible
        self.reducedMotion = reducedMotion
    }

    /// PATCH body fragment — only the non-nil fields enter
    /// { userId, preferences: Partial<PulsePrefs> } (server shallow-merges).
    public func asPatchBody() -> [String: Any] {
        var body: [String: Any] = [:]
        if let bubbleRadius { body["bubbleRadius"] = bubbleRadius }
        if let density { body["density"] = density }
        if let wallpaper { body["wallpaper"] = wallpaper }
        if let notifPreviews { body["notifPreviews"] = notifPreviews }
        if let notifSound { body["notifSound"] = notifSound }
        if let notifVibrate { body["notifVibrate"] = notifVibrate }
        if let lastSeenVisible { body["lastSeenVisible"] = lastSeenVisible }
        if let readReceipts { body["readReceipts"] = readReceipts }
        if let typingVisible { body["typingVisible"] = typingVisible }
        if let reducedMotion { body["reducedMotion"] = reducedMotion }
        return body
    }
}

/// { preferences } — GET/PATCH /api/settings envelope.
public struct WirePrefsEnvelope: Codable, Sendable {
    public let preferences: WirePulsePrefs?
}

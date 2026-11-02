import Foundation

// Wave 8 - platform & hardening wire DTOs (spec §7).
// Mirror of the live-verified gateway contracts:
//   • POST /api/users            → 201 { user: WireUser, token }
//   • POST /api/users/login      → 200 { user, token } | 404 { error }
//   • GET  /api/settings?userId= → 200 { preferences: PulsePrefs }
//   • PATCH /api/settings        → 200 { preferences }
// Tolerant decode house style: only id-critical fields stay required;
// everything else decodes as nil/default so older relays never crash
// the surface. `token` decodes tolerantly (the web ignores it today).

// MARK: - Session tokens (A-1)

/// { user, token } - create + login envelope. `token` is the raw 32-byte
/// hex session secret (shown/persisted exactly once; the server stores
/// only its sha256). Optional so a pre-token gateway still decodes.
public struct WireAuthEnvelope: Codable, Sendable {
    public let user: WireUser
    public let token: String?
}

// MARK: - Conversation themes (R1-W2B F-FX-05)

/// One conversation's theme override - web ConvTheme parity (src/lib/conv-theme.ts:28-31):
/// `wallpaper` uses the exact global wallpaper token set, `tint` is the
/// optional per-room accent. Stored inside the prefs blob under
/// `chat.convThemes` (conv-theme.ts:22) as { conversationId: theme }.
public struct WireConvTheme: Codable, Equatable, Sendable {
    public var wallpaper: String?
    public var tint: String?

    public init(wallpaper: String? = nil, tint: String? = nil) {
        self.wallpaper = wallpaper
        self.tint = tint
    }
}

// MARK: - PulsePrefs (settings blob)

/// Device-side mirror of repo-root src/lib/prefs-defaults.ts PulsePrefs.
/// Every field is optional: a missing key decodes as nil (defaults apply),
/// an unknown key is ignored by synthesis - the exact tolerance of the
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
    /// Section 9 item 3: disclose STORY view receipts to story authors
    /// (server-enforced; off = never appear in their viewer lists or counts).
    public var storyViewReceipts: Bool?
    public var typingVisible: Bool?
    public var reducedMotion: Bool?
    /// R1-W2B F-FX-05 - per-conversation theme map; the wire key is the
    /// web's dotted "chat.convThemes" (prefs-defaults.ts:35), so this field
    /// carries an explicit CodingKey. Absent/unknown entries decode nil.
    public var convThemes: [String: WireConvTheme]?

    enum CodingKeys: String, CodingKey {
        case bubbleRadius, density, wallpaper, notifPreviews, notifSound
        case notifVibrate, lastSeenVisible, readReceipts, storyViewReceipts, typingVisible, reducedMotion
        case convThemes = "chat.convThemes"
    }

    public init(
        bubbleRadius: String? = nil,
        density: String? = nil,
        wallpaper: String? = nil,
        notifPreviews: Bool? = nil,
        notifSound: Bool? = nil,
        notifVibrate: Bool? = nil,
        lastSeenVisible: Bool? = nil,
        readReceipts: Bool? = nil,
        storyViewReceipts: Bool? = nil,
        typingVisible: Bool? = nil,
        reducedMotion: Bool? = nil,
        convThemes: [String: WireConvTheme]? = nil
    ) {
        self.bubbleRadius = bubbleRadius
        self.density = density
        self.wallpaper = wallpaper
        self.notifPreviews = notifPreviews
        self.notifSound = notifSound
        self.notifVibrate = notifVibrate
        self.lastSeenVisible = lastSeenVisible
        self.readReceipts = readReceipts
        self.storyViewReceipts = storyViewReceipts
        self.typingVisible = typingVisible
        self.reducedMotion = reducedMotion
        self.convThemes = convThemes
    }

    /// PATCH body fragment - only the non-nil fields enter
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
        if let storyViewReceipts { body["storyViewReceipts"] = storyViewReceipts }
        if let typingVisible { body["typingVisible"] = typingVisible }
        if let reducedMotion { body["reducedMotion"] = reducedMotion }
        // F-FX-05 - the map rides the web's dotted key; only a non-nil map
        // enters the patch (server shallow-merges + sanitizeConvThemeMap).
        if let convThemes {
            var wireMap: [String: Any] = [:]
            for (conversationId, theme) in convThemes {
                var entry: [String: Any] = [:]
                if let wallpaper = theme.wallpaper { entry["wallpaper"] = wallpaper }
                if let tint = theme.tint { entry["tint"] = tint }
                wireMap[conversationId] = entry
            }
            body["chat.convThemes"] = wireMap
        }
        return body
    }
}

/// Section 13 - GET /api/settings `fieldStamps` row: { field: { rev,
/// intentAt, writer } }. Tolerant: every member optional so an older relay
/// without the stamp map (or with partial rows) still decodes.
public struct WireFieldStamp: Codable, Sendable {
    public let rev: Int64?
    public let intentAt: String?
    public let writer: String?
}

/// { preferences } - GET/PATCH /api/settings envelope. Section 13 extends it
/// additively: `revision` is the per-user monotonic settings revision and
/// `fieldStamps` carries the per-field last-write provenance. Both decode as
/// nil against pre-Section-13 relays.
public struct WirePrefsEnvelope: Codable, Sendable {
    public let preferences: WirePulsePrefs?
    public let revision: Int64?
    public let fieldStamps: [String: WireFieldStamp]?
}

// MARK: - Section 13 durable receipts

/// PATCH /api/settings durable receipt - { preferences, revision,
/// applied: [fields], superseded: [fields], replayed? }. `superseded` names
/// the fields that LOST a last-write-wins comparison against another
/// device's newer intent: the server did NOT apply them and `preferences`
/// carries the winning value the device must adopt. Every member optional so
/// an older server (plain { preferences }) keeps decoding.
public struct WireSettingsMutation: Codable, Sendable {
    public let preferences: WirePulsePrefs?
    public let revision: Int64?
    public let applied: [String]?
    public let superseded: [String]?
    public let replayed: Bool?
}

/// PATCH /api/users/{id} durable receipt - { user, revision, replayed? }.
/// `revision` is the monotonic User.profileRev; `replayed: true` means the
/// SAME clientId hit the stored operation. Tolerant: `user` stays optional
/// so a pre-revision relay still decodes (the core sender upgrades it to the
/// guaranteed WireProfileReceipt before callers ever see it).
public struct WireProfileMutation: Codable, Sendable {
    public let user: WireUser?
    public let revision: Int64?
    public let replayed: Bool?

    public init(user: WireUser?, revision: Int64?, replayed: Bool?) {
        self.user = user
        self.revision = revision
        self.replayed = replayed
    }
}

/// The confirmed profile receipt - the core sender throws before returning
/// unless the envelope carried a real user row.
public struct WireProfileReceipt: Sendable {
    public let user: WireUser
    public let revision: Int64?
    public let replayed: Bool?
}

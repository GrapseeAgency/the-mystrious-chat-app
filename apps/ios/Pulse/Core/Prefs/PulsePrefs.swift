import Foundation
import Combine

/// The persisted viewer identity — stored as JSON in UserDefaults.
public struct PulseViewer: Codable, Equatable {
    public var id: String
    public var name: String
    public var username: String?
    public var color: String?
    public var avatar: String?

    public init(id: String, name: String, username: String? = nil, color: String? = nil, avatar: String? = nil) {
        self.id = id
        self.name = name
        self.username = username
        self.color = color
        self.avatar = avatar
    }

    init(from wire: WireUser) {
        self.init(id: wire.id, name: wire.name, username: wire.username, color: wire.color, avatar: wire.avatar)
    }
}

// ── Wave 8 — prefs value tokens (src/lib/prefs-defaults.ts parity) ──

/// Chat bubble corner style consumed by chat-room bubbles. Native corner
/// radii: md=10, lg=16, pill=26 (the "tail" corner stays 6 on all tokens).
public enum PulseBubbleRadius: String, CaseIterable, Sendable {
    case md, lg, pill

    /// The bubble's base corner radius (the tail corner subtracts to 6).
    public var cornerRadius: CGFloat {
        switch self {
        case .md: return 10
        case .lg: return 16
        case .pill: return 26
        }
    }

    var label: String {
        switch self {
        case .md: return "Medium"
        case .lg: return "Large"
        case .pill: return "Pill"
        }
    }
}

/// Message list density consumed by chat-room row spacing.
public enum PulseDensity: String, CaseIterable, Sendable {
    case cozy, compact

    /// Row spacing inside the message river (cozy keeps the Wave-0 rhythm).
    public var rowSpacing: CGFloat {
        switch self {
        case .cozy: return 6
        case .compact: return 2
        }
    }

    var label: String {
        switch self {
        case .cozy: return "Cozy"
        case .compact: return "Compact"
        }
    }
}

/// Chat wallpaper token consumed by the chat-room background + the
/// Appearance picker's native preview washes.
public enum PulseWallpaper: String, CaseIterable, Sendable {
    case none, aurora, dusk, forest, mono

    var label: String {
        switch self {
        case .none: return "None"
        case .aurora: return "Aurora"
        case .dusk: return "Dusk"
        case .forest: return "Forest"
        case .mono: return "Mono"
        }
    }
}

/// Where a navigation style lives visually (web NavStyleMeta.zone parity;
/// the web's 'overlay' zone belongs to the excluded radial style).
public enum PulseNavZone: Equatable, Sendable {
    case bottom, top, side
}

/// R4-A item 3 — the 8 phone-feasible navigation architectures, ported
/// from web src/lib/nav-registry.ts (:50-64; label + hint strings VERBATIM).
/// Raw values are byte-same with the web store under the EXACT key
/// "pulse.navStyle.v2". The five EXCLUDED web idioms (floating-dock,
/// command-bar, radial, gesture, contextual-dock — desktop / keyboard /
/// exotic gesture surfaces) deliberately have NO case: they still PARSE
/// (→ capsule fallback) so a future widening is drop-in safe.
public enum PulseNavStyle: String, CaseIterable, Sendable {
    case capsule
    case floatingTop = "floating-top"
    case pill
    case bottomBar = "bottom-bar"
    case tabBar = "tab-bar"
    case floatingTabBar = "floating-tab-bar"
    case rail
    case island

    /// Web NavStyleMeta.label — byte-identical strings.
    public var label: String {
        switch self {
        case .capsule: return "Floating Capsule"
        case .floatingTop: return "Floating Top Nav"
        case .pill: return "Pill Navigation"
        case .bottomBar: return "Bottom Bar"
        case .tabBar: return "Tab Bar"
        case .floatingTabBar: return "Floating Tab Bar"
        case .rail: return "Navigation Rail"
        case .island: return "Island Navigation"
        }
    }

    /// Web NavStyleMeta.hint — byte-identical strings.
    public var hint: String {
        switch self {
        case .capsule: return "Detached glass capsule dock — the default"
        case .floatingTop: return "Capsule bar floating beneath the top edge"
        case .pill: return "Single segmented pill with sliding fill"
        case .bottomBar: return "Classic edge-to-edge bottom bar"
        case .tabBar: return "iOS-style tab bar with tinted squircles"
        case .floatingTabBar: return "Detached card, elevated active tab"
        case .rail: return "Persistent vertical side rail"
        case .island: return "Dynamic-island pill that expands on tap"
        }
    }

    /// Web NavStyleMeta.zone (overlay/radial excluded with its style).
    public var zone: PulseNavZone {
        switch self {
        case .floatingTop: return .top
        case .rail: return .side
        default: return .bottom
        }
    }

    /// SF Symbol glyph for the Appearance picker cards.
    public var pickerIcon: String {
        switch self {
        case .capsule: return "capsule"
        case .floatingTop: return "rectangle.topthird.inset.filled"
        case .pill: return "switch.2"
        case .bottomBar: return "rectangle.bottomthird.inset.filled"
        case .tabBar: return "square.grid.2x2"
        case .floatingTabBar: return "dock.rectangle"
        case .rail: return "sidebar.left"
        case .island: return "record.circle"
        }
    }

    /// Web DEFAULT_NAV_STYLE.
    public static let defaultValue: PulseNavStyle = .capsule

    /// Tolerant decode — one of the 8 shipped ids parses through; EVERY
    /// other token (the 5 excluded web ids, junk, legacy values, nil) falls
    /// back to capsule so a stored future-style survives an app update and
    /// a junk value never breaks the shell (web getNavStyleMeta parity).
    public static func parse(_ raw: String?) -> PulseNavStyle {
        guard let raw else { return .defaultValue }
        return PulseNavStyle(rawValue: raw) ?? .defaultValue
    }
}

/// Resolved (non-optional) preference values — the device-side mirror of
/// web DEFAULT_PREFERENCES. PulseWave8Logic.mergedPrefs shallow-merges a
/// server patch over a base of these (web mergePrefs parity).
public struct PulsePrefsValues: Equatable, Sendable {
    public var bubbleRadius: PulseBubbleRadius = .lg
    public var density: PulseDensity = .cozy
    public var wallpaper: PulseWallpaper = .none
    public var notifPreviews = true
    public var notifSound = true
    public var notifVibrate = false
    public var lastSeenVisible = true
    public var readReceipts = true
    public var typingVisible = true
    public var reducedMotion = false

    public init(
        bubbleRadius: PulseBubbleRadius = .lg,
        density: PulseDensity = .cozy,
        wallpaper: PulseWallpaper = .none,
        notifPreviews: Bool = true,
        notifSound: Bool = true,
        notifVibrate: Bool = false,
        lastSeenVisible: Bool = true,
        readReceipts: Bool = true,
        typingVisible: Bool = true,
        reducedMotion: Bool = false
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
}

/// UserDefaults-backed prefs (web parity keys where they exist):
/// viewer identity, ambient FX mode ("fx.ambientMode"), appearance override,
/// chats-list filter (All / Unread / Groups — persisted like the web).
/// Wave 8 — adds the server-synced PulsePrefs blob (bubble radius, density,
/// wallpaper, notification + privacy toggles, reduced motion) with
/// optimistic local-first writes and a fire-and-forget PATCH funnel, plus
/// the LOCAL-only quiet-hours window and haptics master toggle (web
/// pulse.settings.v1 parity).
@MainActor
public final class PulsePrefs: ObservableObject {
    private let defaults: UserDefaults

    public static let ambientModeKey = "fx.ambientMode"
    public static let appearanceKey = "appearance"
    public static let chatsFilterKey = "chats.listFilter"
    /// W5-f — live-caption toggle for PTT voice rooms (web parity key
    /// "pulse-voice-captions"; house prefix "voice.captions"). The ONLY
    /// persisted artefact of the rooms feature — rooms themselves are
    /// ephemeral (spec §1.4).
    public static let voiceCaptionsKey = "voice.captions"
    private static let viewerKey = "pulse.viewer"
    // Wave 8 — server-synced blob + local alert keys (house "prefs." prefix).
    public static let bubbleRadiusKey = "prefs.bubbleRadius"
    public static let densityKey = "prefs.density"
    public static let wallpaperKey = "prefs.wallpaper"
    public static let notifPreviewsKey = "prefs.notifPreviews"
    public static let notifSoundKey = "prefs.notifSound"
    public static let notifVibrateKey = "prefs.notifVibrate"
    public static let lastSeenVisibleKey = "prefs.lastSeenVisible"
    public static let readReceiptsKey = "prefs.readReceipts"
    public static let typingVisibleKey = "prefs.typingVisible"
    public static let reducedMotionKey = "prefs.reducedMotion"
    public static let quietHoursOnKey = "quiet.hoursOn"
    public static let quietStartKey = "quiet.start"
    public static let quietEndKey = "quiet.end"
    public static let hapticsOnKey = "haptics.on"
    /// R1-W2B F-FX-05 — local mirror of the per-conversation theme map (the
    /// SERVER copy rides the settings blob under the web's "chat.convThemes"
    /// key; this UserDefaults key follows the house "prefs." namespace).
    public static let convThemesKey = "prefs.convThemes"
    /// R2-B R42 — local mirror of the per-conversation PERSONAL screen-
    /// security veil (myScreenPrivacy). The server copy rides the dedicated
    /// PATCH /api/conversations/[id]/screen-privacy route; this map is the
    /// instant local write-through so the veil engages without a round-trip.
    public static let screenPrivacyKey = "prefs.screenPrivacy"
    /// R2-D — the design-language selection. Key AND values are byte-identical
    /// to the web store (ui-theme.ts `pulse.uiTheme.v2`:
    /// glass|kinetic|minimal|dynamic|aero) so the two platforms converge.
    public static let uiThemeKey = "pulse.uiTheme.v2"
    /// R4-A item 3 — the navigation architecture. Key AND values byte-identical
    /// to the web store (nav-registry.ts `pulse.navStyle.v2`); the 5 excluded
    /// web ids + junk decode to capsule via PulseNavStyle.parse.
    public static let navStyleKey = "pulse.navStyle.v2"

    /// R1-W2G D46 — namespaced key builder for the durable last-position
    /// cache per space room ("space:lastpos:<roomId>"). The relay keeps the
    /// previous position in-memory only (mini-services/pulse-socket/index.ts
    /// :1288-1299; web client falls back to 0.5/0.5) — this key is the
    /// native DURABLE twin: a JSON-encoded SpaceLastPosition written by
    /// VoiceRoomSessionModel with the voiceCaptions direct-defaults pattern.
    public static func spaceLastPositionKey(_ roomId: String) -> String {
        "space:lastpos:\(roomId)"
    }

    public enum ChatsFilter: String, CaseIterable {
        case all, unread, groups
    }

    // ── remote sync seams (attached by PulseSession.attach(prefs:)) ──
    /// Every server-backed toggle calls this after the optimistic local
    /// write. The session PATCHes { userId, preferences } and reports the
    /// verdict back via `notePatchSettled`.
    public var patchRemote: ((WirePulsePrefs) -> Void)?
    /// 401 rotation rejections funnel to the session layer (token clear +
    /// re-login surface).
    public var authRejectionHandler: ((String?) -> Void)?
    /// Honest offline hint for the settings surface: PATCH failures keep
    /// the local value and surface the note ("server value wins" applies
    /// only to FETCH results).
    @Published public private(set) var lastSyncNote: String?

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        viewer = Self.readViewer(defaults)
        ambientMode = AmbientMode.parse(defaults.string(forKey: Self.ambientModeKey) ?? "aurora")
        appearance = defaults.string(forKey: Self.appearanceKey) ?? "system"
        chatsFilter = ChatsFilter(rawValue: defaults.string(forKey: Self.chatsFilterKey) ?? "") ?? .all
        voiceCaptions = defaults.bool(forKey: Self.voiceCaptionsKey)
        // Wave 8 — server blob (tolerant: bad/absent tokens fall back to
        // the web DEFAULT_PREFERENCES values below).
        bubbleRadius = PulseBubbleRadius(rawValue: defaults.string(forKey: Self.bubbleRadiusKey) ?? "") ?? .lg
        density = PulseDensity(rawValue: defaults.string(forKey: Self.densityKey) ?? "") ?? .cozy
        wallpaper = PulseWallpaper(rawValue: defaults.string(forKey: Self.wallpaperKey) ?? "") ?? .none
        notifPreviews = Self.bool(defaults, Self.notifPreviewsKey, default: true)
        notifSound = Self.bool(defaults, Self.notifSoundKey, default: true)
        notifVibrate = Self.bool(defaults, Self.notifVibrateKey, default: false)
        lastSeenVisible = Self.bool(defaults, Self.lastSeenVisibleKey, default: true)
        readReceipts = Self.bool(defaults, Self.readReceiptsKey, default: true)
        typingVisible = Self.bool(defaults, Self.typingVisibleKey, default: true)
        reducedMotion = Self.bool(defaults, Self.reducedMotionKey, default: false)
        // Wave 8 — LOCAL quiet hours + haptics master (pulse.settings.v1
        // defaults: off / 22:00 / 07:00, haptics on).
        quietHoursOn = defaults.bool(forKey: Self.quietHoursOnKey)
        quietStart = defaults.string(forKey: Self.quietStartKey) ?? "22:00"
        quietEnd = defaults.string(forKey: Self.quietEndKey) ?? "07:00"
        hapticsOn = Self.bool(defaults, Self.hapticsOnKey, default: true)
        // R1-W2B F-FX-05 — cached theme map (tolerant: bad JSON → empty map;
        // the server blob overwrites it on the next successful sync).
        convThemes = Self.readConvThemes(defaults)
        // R2-B R42 — cached personal-veil map (tolerant: bad JSON → empty).
        screenPrivacy = Self.readScreenPrivacy(defaults)
        // R2-D — design language (tolerant: junk/legacy → glass, web parity).
        uiTheme = PulseUiTheme.parse(defaults.string(forKey: Self.uiThemeKey))
        // R4-A item 3 — nav architecture (tolerant: excluded ids/junk →
        // capsule, web parity).
        navStyle = PulseNavStyle.parse(defaults.string(forKey: Self.navStyleKey))
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
    }

    @Published public private(set) var viewer: PulseViewer?
    @Published public private(set) var ambientMode: AmbientMode
    @Published public private(set) var appearance: String
    @Published public private(set) var chatsFilter: ChatsFilter
    /// W5-f — voice-room live captions (default off, setChatsFilter pattern).
    @Published public private(set) var voiceCaptions: Bool

    // ── Wave 8 — server-synced PulsePrefs blob ──────────────
    @Published public private(set) var bubbleRadius: PulseBubbleRadius
    @Published public private(set) var density: PulseDensity
    @Published public private(set) var wallpaper: PulseWallpaper
    @Published public private(set) var notifPreviews: Bool
    @Published public private(set) var notifSound: Bool
    @Published public private(set) var notifVibrate: Bool
    @Published public private(set) var lastSeenVisible: Bool
    @Published public private(set) var readReceipts: Bool
    @Published public private(set) var typingVisible: Bool
    @Published public private(set) var reducedMotion: Bool

    // ── Wave 8 — LOCAL quiet hours + haptics master ─────────
    @Published public private(set) var quietHoursOn: Bool
    @Published public private(set) var quietStart: String
    @Published public private(set) var quietEnd: String
    @Published public private(set) var hapticsOn: Bool

    // ── R1-W2B F-FX-05 — per-conversation themes (server-synced) ──
    @Published public private(set) var convThemes: [String: WireConvTheme]
    // ── R2-B R42 — per-conversation PERSONAL screen-security veil ──
    @Published public private(set) var screenPrivacy: [String: Bool]
    // ── R2-D — the five locked design languages (ui-theme.ts parity) ──
    @Published public private(set) var uiTheme: PulseUiThemeId
    // ── R4-A item 3 — the navigation architecture (nav-registry parity) ──
    @Published public private(set) var navStyle: PulseNavStyle

    public var hasIdentity: Bool { viewer != nil }

    /// The resolved (non-optional) snapshot — the merge helper's base.
    public var resolvedValues: PulsePrefsValues {
        PulsePrefsValues(
            bubbleRadius: bubbleRadius,
            density: density,
            wallpaper: wallpaper,
            notifPreviews: notifPreviews,
            notifSound: notifSound,
            notifVibrate: notifVibrate,
            lastSeenVisible: lastSeenVisible,
            readReceipts: readReceipts,
            typingVisible: typingVisible,
            reducedMotion: reducedMotion
        )
    }

    /// Wave 8 — quiet-hours verdict at call time (web isQuietHoursNow
    /// parity; gates the incoming attention toasts + PulseHaptics).
    public var isQuietHoursNow: Bool {
        PulseWave8Logic.isQuietHoursNow(enabled: quietHoursOn, start: quietStart, end: quietEnd)
    }

    // ── identity ─────────────────────────────────────────────

    public func setViewer(_ viewer: PulseViewer?) {
        let previousId = self.viewer?.id
        self.viewer = viewer
        if let viewer {
            if let data = try? JSONEncoder().encode(viewer) {
                defaults.set(data, forKey: Self.viewerKey)
            }
        } else {
            defaults.removeObject(forKey: Self.viewerKey)
        }
        // Wave 0 — secure mirror (viewer.identity + token slot for later).
        // Best effort: UserDefaults stays the source of truth.
        PulseKeychain.shared.saveViewer(viewer)
        // Wave 8 — the session token is IDENTITY-BOUND: forgetting (nil)
        // and switching identities clears it so no request ever carries a
        // stale Bearer. The create/login flows re-persist the fresh token
        // AFTER setViewer, so rotation lands intact.
        if viewer?.id != previousId {
            PulseKeychain.shared.clearSessionToken()
        }
    }

    public func setAmbientMode(_ mode: AmbientMode) {
        ambientMode = mode
        defaults.set(mode.rawValue, forKey: Self.ambientModeKey)
    }

    public func setAppearance(_ value: String) {
        appearance = value
        defaults.set(value, forKey: Self.appearanceKey)
    }

    /// Cycles system → light → dark (header theme toggle).
    public func cycleAppearance() {
        switch appearance {
        case "light": setAppearance("dark")
        case "dark": setAppearance("system")
        default: setAppearance("light")
        }
    }

    public func setChatsFilter(_ filter: ChatsFilter) {
        chatsFilter = filter
        defaults.set(filter.rawValue, forKey: Self.chatsFilterKey)
    }

    /// R2-D — pick a design language (glass | kinetic | minimal | dynamic |
    /// aero). The raw value stored under "pulse.uiTheme.v2" is byte-identical
    /// to the web's zustand-persist payload.
    public func setUiTheme(_ theme: PulseUiThemeId) {
        uiTheme = theme
        defaults.set(theme.rawValue, forKey: Self.uiThemeKey)
    }

    /// R4-A item 3 — pick a navigation architecture (the raw value stored
    /// under "pulse.navStyle.v2" is byte-identical to the web's payload;
    /// RootView mirrors the change into the dock renderer).
    public func setNavStyle(_ style: PulseNavStyle) {
        navStyle = style
        defaults.set(style.rawValue, forKey: Self.navStyleKey)
    }

    /// W5-f — persist the voice captions toggle (L89-92 pattern).
    public func setVoiceCaptions(_ enabled: Bool) {
        voiceCaptions = enabled
        defaults.set(enabled, forKey: Self.voiceCaptionsKey)
    }

    // ── R1-W2B F-FX-05 — per-conversation theme setters ──

    /// Web setConvTheme parity (conv-theme.ts:195-215): merges the patch into
    /// the conversation's entry — a fresh entry seeds from the CURRENT global
    /// wallpaper so tint-only rooms stay deterministic. Optimistic local
    /// write + full-map PATCH through the settings funnel.
    public func setConvTheme(conversationId: String, wallpaper: PulseWallpaper?, tint: String?) {
        var map = convThemes ?? [:]
        var entry = map[conversationId] ?? WireConvTheme(wallpaper: self.wallpaper.rawValue, tint: nil)
        if let wallpaper { entry.wallpaper = wallpaper.rawValue }
        if let tint {
            entry.tint = Self.convTints.contains(tint) ? tint : nil
        } else if wallpaper == nil {
            entry.tint = nil // explicit nil tint clears (web patch.tint:null)
        }
        map[conversationId] = entry
        storeConvThemes(map)
        patch { $0.convThemes = map }
    }

    /// Web clearConvTheme parity — the room falls back to the global default.
    public func clearConvTheme(conversationId: String) {
        var map = convThemes ?? [:]
        guard map[conversationId] != nil else { return }
        map[conversationId] = nil
        storeConvThemes(map)
        patch { $0.convThemes = map }
    }

    private func storeConvThemes(_ map: [String: WireConvTheme]) {
        convThemes = map
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: Self.convThemesKey)
        }
    }

    // ── R2-B R42 — personal screen-security veil ──

    /// Instant local write-through for MY veil on one conversation; the
    /// server mirror PATCH runs from the caller (GroupInfoView) and the
    /// verdict lands via adoptServerScreenPrivacy.
    public func setScreenPrivacy(conversationId: String, on: Bool) {
        var map = screenPrivacy
        map[conversationId] = on
        storeScreenPrivacy(map)
    }

    /// Server truth landed (detail fetch or PATCH echo): the server value
    /// wins for that conversation — the same shallow-merge contract as every
    /// other server-backed blob field.
    public func adoptServerScreenPrivacy(conversationId: String, on: Bool) {
        guard screenPrivacy[conversationId] != on else { return }
        setScreenPrivacy(conversationId: conversationId, on: on)
    }

    private func storeScreenPrivacy(_ map: [String: Bool]) {
        screenPrivacy = map
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: Self.screenPrivacyKey)
        }
    }

    /// The accent tints the per-conversation picker offers (web CONV_TINTS
    /// verbatim — conv-theme.ts:51).
    public static let convTints = ["emerald", "rose", "amber", "violet", "teal"]

    // ── Wave 8 — server-synced setters (optimistic local first) ──

    public func setBubbleRadius(_ value: PulseBubbleRadius) {
        bubbleRadius = value
        defaults.set(value.rawValue, forKey: Self.bubbleRadiusKey)
        patch { $0.bubbleRadius = value.rawValue }
    }

    public func setDensity(_ value: PulseDensity) {
        density = value
        defaults.set(value.rawValue, forKey: Self.densityKey)
        patch { $0.density = value.rawValue }
    }

    public func setWallpaper(_ value: PulseWallpaper) {
        wallpaper = value
        defaults.set(value.rawValue, forKey: Self.wallpaperKey)
        patch { $0.wallpaper = value.rawValue }
    }

    public func setNotifPreviews(_ enabled: Bool) {
        notifPreviews = enabled
        defaults.set(enabled, forKey: Self.notifPreviewsKey)
        patch { $0.notifPreviews = enabled }
    }

    public func setNotifSound(_ enabled: Bool) {
        notifSound = enabled
        defaults.set(enabled, forKey: Self.notifSoundKey)
        patch { $0.notifSound = enabled }
    }

    public func setNotifVibrate(_ enabled: Bool) {
        notifVibrate = enabled
        defaults.set(enabled, forKey: Self.notifVibrateKey)
        patch { $0.notifVibrate = enabled }
    }

    public func setLastSeenVisible(_ enabled: Bool) {
        lastSeenVisible = enabled
        defaults.set(enabled, forKey: Self.lastSeenVisibleKey)
        patch { $0.lastSeenVisible = enabled }
    }

    public func setReadReceipts(_ enabled: Bool) {
        readReceipts = enabled
        defaults.set(enabled, forKey: Self.readReceiptsKey)
        patch { $0.readReceipts = enabled }
    }

    public func setTypingVisible(_ enabled: Bool) {
        typingVisible = enabled
        defaults.set(enabled, forKey: Self.typingVisibleKey)
        patch { $0.typingVisible = enabled }
    }

    public func setReducedMotion(_ enabled: Bool) {
        reducedMotion = enabled
        defaults.set(enabled, forKey: Self.reducedMotionKey)
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
        patch { $0.reducedMotion = enabled }
    }

    // ── Wave 8 — LOCAL quiet hours + haptics setters ─────────

    public func setQuietHoursOn(_ enabled: Bool) {
        quietHoursOn = enabled
        defaults.set(enabled, forKey: Self.quietHoursOnKey)
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
    }

    public func setQuietStart(_ value: String) {
        quietStart = value
        defaults.set(value, forKey: Self.quietStartKey)
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
    }

    public func setQuietEnd(_ value: String) {
        quietEnd = value
        defaults.set(value, forKey: Self.quietEndKey)
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
    }

    public func setHapticsOn(_ enabled: Bool) {
        hapticsOn = enabled
        defaults.set(enabled, forKey: Self.hapticsOnKey)
        Self.applyHapticGate(enabled: enabled, quietNow: isQuietHoursNow)
    }

    /// Wave 8 — the haptic gate lives in PulseHaptics (evaluated at call
    /// time); the prefs model owns the two inputs (master toggle + the
    /// quiet-hours snapshot, kept fresh by the session's 1s loop).
    nonisolated private static func applyHapticGate(enabled: Bool, quietNow: Bool) {
        PulseHaptics.isEnabled = enabled
        PulseHaptics.quietNow = quietNow
    }

    /// Minute-tick refresh (the session's 1s loop calls this) so the quiet
    /// window opens/closes without a settings interaction.
    public func refreshQuietGate() {
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
    }

    // ── Wave 8 — server sync ─────────────────────────────────

    /// GET /api/settings — the SERVER value wins (web mergePrefs parity:
    /// defaults → stored blob). Local values act as the base; every server
    /// field that decoded real value clamps over it. Offline-first: a
    /// failed fetch keeps the local state untouched.
    public func syncFromServer(api: PulseAPIClient) async {
        do {
            let server = try await api.settings()
            applyServer(server)
        } catch let failure as PulseAPIClient.Failure where failure.kind == .auth {
            // Present-but-invalid token — the session layer clears it and
            // surfaces re-login (server contract, Wave 8).
            authRejectionHandler?(failure.message)
        } catch {
            // Offline / unknown user (local_ ids) — local values stay
            // authoritative. Honest, by design.
        }
    }

    /// Server blob landed (fresh fetch or PATCH echo) — merge server-wins
    /// over the local resolved values and persist. No PATCH loopback: the
    /// server IS the source here.
    public func applyServer(_ server: WirePulsePrefs) {
        let merged = PulseWave8Logic.mergedPrefs(base: resolvedValues, patch: server)
        bubbleRadius = merged.bubbleRadius
        defaults.set(merged.bubbleRadius.rawValue, forKey: Self.bubbleRadiusKey)
        density = merged.density
        defaults.set(merged.density.rawValue, forKey: Self.densityKey)
        wallpaper = merged.wallpaper
        defaults.set(merged.wallpaper.rawValue, forKey: Self.wallpaperKey)
        notifPreviews = merged.notifPreviews
        defaults.set(merged.notifPreviews, forKey: Self.notifPreviewsKey)
        notifSound = merged.notifSound
        defaults.set(merged.notifSound, forKey: Self.notifSoundKey)
        notifVibrate = merged.notifVibrate
        defaults.set(merged.notifVibrate, forKey: Self.notifVibrateKey)
        lastSeenVisible = merged.lastSeenVisible
        defaults.set(merged.lastSeenVisible, forKey: Self.lastSeenVisibleKey)
        readReceipts = merged.readReceipts
        defaults.set(merged.readReceipts, forKey: Self.readReceiptsKey)
        typingVisible = merged.typingVisible
        defaults.set(merged.typingVisible, forKey: Self.typingVisibleKey)
        reducedMotion = merged.reducedMotion
        defaults.set(merged.reducedMotion, forKey: Self.reducedMotionKey)
        Self.applyHapticGate(enabled: hapticsOn, quietNow: isQuietHoursNow)
        // R1-W2B F-FX-05 — the server's theme map wins when present (same
        // shallow-merge contract as every other blob field).
        if let serverThemes = server.convThemes {
            convThemes = serverThemes
            if let data = try? JSONEncoder().encode(serverThemes) {
                defaults.set(data, forKey: Self.convThemesKey)
            }
        }
    }

    /// PATCH verdict from the session funnel: success clears the hint,
    /// failure keeps the local value + surfaces the honest offline note.
    public func notePatchSettled(successful: Bool, note: String?) {
        lastSyncNote = successful ? nil : (note ?? "Saved on this device — the server didn't answer.")
    }

    /// Optimistic write funnel: the local field is ALREADY updated; this
    /// hands the single-field patch to the session's PATCH sender.
    private func patch(_ configure: (inout WirePulsePrefs) -> Void) {
        guard let patchRemote else { return }
        var wire = WirePulsePrefs()
        configure(&wire)
        patchRemote(wire)
    }

    // ── plumbing ─────────────────────────────────────────────

    /// Bool with a default — UserDefaults.bool() reads absent keys as false,
    /// which would flip the true-defaulted toggles on first launch.
    private static func bool(_ defaults: UserDefaults, _ key: String, default defaultValue: Bool) -> Bool {
        defaults.object(forKey: key) == nil ? defaultValue : defaults.bool(forKey: key)
    }

    private static func readViewer(_ defaults: UserDefaults) -> PulseViewer? {
        guard let data = defaults.data(forKey: viewerKey) else { return nil }
        return try? JSONDecoder().decode(PulseViewer.self, from: data)
    }

    /// Cached conv-theme map; anything unreadable degrades to an empty map.
    private static func readConvThemes(_ defaults: UserDefaults) -> [String: WireConvTheme] {
        guard let data = defaults.data(forKey: convThemesKey) else { return [:] }
        return (try? JSONDecoder().decode([String: WireConvTheme].self, from: data)) ?? [:]
    }

    /// Cached personal-veil map; anything unreadable degrades to an empty map.
    private static func readScreenPrivacy(_ defaults: UserDefaults) -> [String: Bool] {
        guard let data = defaults.data(forKey: screenPrivacyKey) else { return [:] }
        return (try? JSONDecoder().decode([String: Bool].self, from: data)) ?? [:]
    }
}

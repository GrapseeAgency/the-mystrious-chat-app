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

/// UserDefaults-backed prefs (web parity keys where they exist):
/// viewer identity, ambient FX mode ("fx.ambientMode"), appearance override,
/// chats-list filter (All / Unread / Groups — persisted like the web).
@MainActor
public final class PulsePrefs: ObservableObject {
    private let defaults: UserDefaults

    public static let ambientModeKey = "fx.ambientMode"
    public static let appearanceKey = "appearance"
    public static let chatsFilterKey = "chats.listFilter"
    private static let viewerKey = "pulse.viewer"

    public enum ChatsFilter: String, CaseIterable {
        case all, unread, groups
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        viewer = Self.readViewer(defaults)
        ambientMode = AmbientMode.parse(defaults.string(forKey: Self.ambientModeKey) ?? "aurora")
        appearance = defaults.string(forKey: Self.appearanceKey) ?? "system"
        chatsFilter = ChatsFilter(rawValue: defaults.string(forKey: Self.chatsFilterKey) ?? "") ?? .all
    }

    @Published public private(set) var viewer: PulseViewer?
    @Published public private(set) var ambientMode: AmbientMode
    @Published public private(set) var appearance: String
    @Published public private(set) var chatsFilter: ChatsFilter

    public var hasIdentity: Bool { viewer != nil }

    public func setViewer(_ viewer: PulseViewer?) {
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

    private static func readViewer(_ defaults: UserDefaults) -> PulseViewer? {
        guard let data = defaults.data(forKey: viewerKey) else { return nil }
        return try? JSONDecoder().decode(PulseViewer.self, from: data)
    }
}

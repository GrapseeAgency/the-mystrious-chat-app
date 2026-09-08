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
/// viewer identity, ambient FX mode ("fx.ambientMode"), appearance override.
@MainActor
public final class PulsePrefs: ObservableObject {
    private let defaults: UserDefaults

    public static let ambientModeKey = "fx.ambientMode"
    public static let appearanceKey = "appearance"
    private static let viewerKey = "pulse.viewer"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        viewer = Self.readViewer(defaults)
        ambientMode = AmbientMode.parse(defaults.string(forKey: Self.ambientModeKey) ?? "aurora")
        appearance = defaults.string(forKey: Self.appearanceKey) ?? "system"
    }

    @Published public private(set) var viewer: PulseViewer?
    @Published public private(set) var ambientMode: AmbientMode
    @Published public private(set) var appearance: String

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
    }

    public func setAmbientMode(_ mode: AmbientMode) {
        ambientMode = mode
        defaults.set(mode.rawValue, forKey: Self.ambientModeKey)
    }

    public func setAppearance(_ value: String) {
        appearance = value
        defaults.set(value, forKey: Self.appearanceKey)
    }

    private static func readViewer(_ defaults: UserDefaults) -> PulseViewer? {
        guard let data = defaults.data(forKey: viewerKey) else { return nil }
        return try? JSONDecoder().decode(PulseViewer.self, from: data)
    }
}

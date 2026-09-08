import SwiftUI

extension PulseTheme {
    // Additional accents (web AVATAR_GRADIENTS 400/600 pairs).
    static let teal = Color(red: 0.063, green: 0.722, blue: 0.651)
    static let violet = Color(red: 0.545, green: 0.365, blue: 0.965)
    static let amber = Color(red: 0.961, green: 0.620, blue: 0.043)
    static let rose = Color(red: 0.984, green: 0.445, blue: 0.522)

    /// Tailwind 400→600 gradient pairs keyed by the server's color names
    /// (src/lib/pulse-utils.ts AVATAR_GRADIENTS — the exact web outcome).
    static func gradient(named name: String?) -> LinearGradient {
        let colors: [Color]
        switch name {
        case "rose": colors = [Color(red: 0.984, green: 0.445, blue: 0.522), Color(red: 0.882, green: 0.114, blue: 0.282)]
        case "amber": colors = [Color(red: 0.984, green: 0.749, blue: 0.141), Color(red: 0.851, green: 0.467, blue: 0.024)]
        case "violet": colors = [Color(red: 0.655, green: 0.545, blue: 0.980), Color(red: 0.486, green: 0.227, blue: 0.929)]
        case "teal": colors = [Color(red: 0.176, green: 0.831, blue: 0.749), Color(red: 0.051, green: 0.580, blue: 0.533)]
        case "orange": colors = [Color(red: 0.984, green: 0.573, blue: 0.235), Color(red: 0.918, green: 0.345, blue: 0.047)]
        case "pink": colors = [Color(red: 0.957, green: 0.447, blue: 0.694), Color(red: 0.859, green: 0.153, blue: 0.467)]
        case "cyan": colors = [Color(red: 0.133, green: 0.827, blue: 0.933), Color(red: 0.031, green: 0.569, blue: 0.698)]
        default: colors = [Color(red: 0.204, green: 0.827, blue: 0.600), Color(red: 0.020, green: 0.588, blue: 0.412)]
        }
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Flat tint from a wire color name (falls back to emerald).
    static func color(named name: String?) -> Color {
        gradient(named: name).colors.first ?? emerald
    }

    /// Photo URL resolution matching the web <img src> handling: absolute
    /// URLs pass through; /uploads paths hang off the gateway.
    static func photoURL(_ path: String?, base: URL) -> URL? {
        guard let path, !path.isEmpty else { return nil }
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            return URL(string: path)
        }
        if path.hasPrefix("/") {
            return URL(string: path, relativeTo: base)?.absoluteURL
        }
        return URL(string: "\(base.absoluteString)/api/uploads/\(path)")
    }

    static func photoURL(_ path: String?) -> URL? {
        photoURL(path, base: PulseEndpoints.gatewayURL)
    }
}

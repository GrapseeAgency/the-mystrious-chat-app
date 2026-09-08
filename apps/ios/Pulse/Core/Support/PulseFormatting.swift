import SwiftUI

/// Motion presets — native translations of the web spring system
/// (src/lib/motion.ts): snappy / soft / bouncy / gentle.
extension Animation {
    /// Tabs, press states, pills — fast settle, no wobble.
    public static let pulseSnappy = Animation.spring(response: 0.32, dampingFraction: 0.8)
    /// Sheets, cards, layout shifts.
    public static let pulseSoft = Animation.spring(response: 0.45, dampingFraction: 0.85)
    /// Badges, reactions, bubbles — visible overshoot.
    public static let pulseBouncy = Animation.spring(response: 0.3, dampingFraction: 0.55)
    /// Ambient drift.
    public static let pulseGentle = Animation.spring(response: 0.7, dampingFraction: 0.95)

    /// Reduced-motion substitution: same distance, no spring energy.
    public static func pulse(_ preset: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.18) : preset
    }
}

/// Shared micro-interaction — scale to 0.94 on press with the snappy spring.
public struct PulseButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1.0)
            .animation(.pulse(.pulseSnappy, reduceMotion: reduceMotion), value: configuration.isPressed)
    }
}

/// Date/time + initials + palette helpers shared by every screen.
public enum PulseFormat {
    // ISO dates arrive like 2026-09-04T14:11:18.131Z (fractional seconds).
    private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain = ISO8601DateFormatter()

    public static func date(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        return isoFractional.date(from: iso) ?? isoPlain.date(from: iso)
    }

    /// Row trailing stamp: clock time today, weekday this week, else date.
    public static func rowTime(_ iso: String?) -> String {
        guard let date = date(iso) else { return "" }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return Self.clock.string(from: date)
        }
        if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: Date())).day, days < 7 {
            return Self.weekday.string(from: date)
        }
        return Self.day.string(from: date)
    }

    /// Day-separator capsule label ("Today", "Yesterday", else "Sep 4").
    public static func dayLabel(_ iso: String?) -> String {
        guard let date = date(iso) else { return "" }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return Self.separator.string(from: date)
    }

    public static func clockTime(_ iso: String?) -> String {
        guard let date = date(iso) else { return "" }
        return Self.clock.string(from: date)
    }

    /// Voice-note chip label ("0:42").
    public static func duration(_ ms: Double?) -> String {
        guard let ms, ms > 0 else { return "" }
        let total = Int(ms / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// Up to two-letter initials for palette avatars.
    public static func initials(of name: String) -> String {
        let parts = name.split(separator: " ").filter { !$0.isEmpty }
        let first = parts.first?.first.map(String.init) ?? ""
        let second = parts.count > 1 ? parts[1].first.map(String.init) : (parts.first?.dropFirst().first.map(String.init) ?? "")
        let joined = first + second
        return joined.isEmpty ? "?" : joined.uppercased()
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()
    private static let weekday: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEE"
        return f
    }()
    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f
    }()
    private static let separator: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f
    }()
}

/// Ambient FX modes — parity with web WEBGL_MODES. Persisted under the
/// same prefs key family ("fx.ambientMode", default aurora).
public enum AmbientMode: String, CaseIterable, Identifiable {
    case off, aurora, caustics, mesh, stars, liquid

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .off: return "Off"
        case .aurora: return "Aurora"
        case .caustics: return "Caustics"
        case .mesh: return "Mesh"
        case .stars: return "Stars"
        case .liquid: return "Liquid"
        }
    }

    public static func parse(_ raw: String?) -> AmbientMode {
        AmbientMode(rawValue: raw ?? "") ?? .aurora
    }
}

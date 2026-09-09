import SwiftUI
import UIKit

// Adaptive token set for the home rebuild — every zinc shade, surface and
// hairline the web glass recipes use, resolved per color scheme at draw time.
extension PulseTheme {
    // ── exact accents (web palette §13) ──────────────────────
    static let emerald400 = Color(red: 0.204, green: 0.827, blue: 0.600) // #34d399
    static let emerald500 = Color(red: 0.063, green: 0.725, blue: 0.506) // #10b981
    static let emerald600 = Color(red: 0.020, green: 0.588, blue: 0.412) // #059669
    static let teal500 = Color(red: 0.078, green: 0.722, blue: 0.651)    // #14b8a6
    static let teal600 = Color(red: 0.051, green: 0.580, blue: 0.533)    // #0d9488
    static let amber500 = Color(red: 0.961, green: 0.620, blue: 0.043)   // #f59e0b
    static let amber600 = Color(red: 0.851, green: 0.467, blue: 0.024)   // #d97706
    static let rose400 = Color(red: 0.984, green: 0.445, blue: 0.522)    // #fb7185
    static let rose500 = Color(red: 0.957, green: 0.247, blue: 0.369)    // #f43f5e
    static let violet400 = Color(red: 0.655, green: 0.545, blue: 0.980)  // #a78bfa
    static let violet700 = Color(red: 0.427, green: 0.157, blue: 0.851)  // #6d28d9
    static let purple400 = Color(red: 0.753, green: 0.518, blue: 0.988)  // #c084fc
    static let purple600 = Color(red: 0.576, green: 0.200, blue: 0.918)  // #9333ea
    static let fuchsia400 = Color(red: 0.910, green: 0.475, blue: 0.976) // #e879f9
    static let fuchsia500 = Color(red: 0.851, green: 0.275, blue: 0.937) // #d946ef

    // ── zinc scale (identical in both modes) ─────────────────
    static func zinc(_ level: Int) -> Color {
        switch level {
        case 50: return Color(red: 0.980, green: 0.980, blue: 0.980)
        case 100: return Color(red: 0.957, green: 0.957, blue: 0.961)
        case 200: return Color(red: 0.894, green: 0.894, blue: 0.906)
        case 300: return Color(red: 0.831, green: 0.831, blue: 0.847)
        case 400: return Color(red: 0.631, green: 0.631, blue: 0.667)
        case 500: return Color(red: 0.443, green: 0.443, blue: 0.478)
        case 600: return Color(red: 0.322, green: 0.322, blue: 0.357)
        case 700: return Color(red: 0.247, green: 0.247, blue: 0.275)
        case 800: return Color(red: 0.153, green: 0.153, blue: 0.165)
        case 900: return Color(red: 0.094, green: 0.094, blue: 0.106)
        default: return Color(red: 0.035, green: 0.035, blue: 0.043) // 950 == legacy ink
        }
    }

    // ── adaptive surfaces / hairlines (light / dark) ─────────
    private static func adaptive(_ light: UIColor, _ dark: UIColor) -> Color {
        Color(UIColor { traits in traits.userInterfaceStyle == .dark ? dark : light })
    }

    /// SwiftUI.Color flavor — the zinc/accent token helpers pass Color values
    /// (zinc(_:), emerald600, …), which do NOT implicitly convert to UIColor.
    private static func adaptive(_ light: Color, _ dark: Color) -> Color {
        Color(UIColor { traits in traits.userInterfaceStyle == .dark ? UIColor(dark) : UIColor(light) })
    }

    /// Page wash behind everything — the translucent layer that makes glass read.
    static var pageWash: Color {
        adaptive(
            UIColor.white.withAlphaComponent(0.30),
            UIColor(red: 0.035, green: 0.035, blue: 0.043, alpha: 0.20),
        )
    }

    /// Conversation row fill — white/80 light, zinc-900/70 dark (flat, cheap).
    static var rowFill: Color {
        adaptive(
            UIColor.white.withAlphaComponent(0.80),
            UIColor(red: 0.094, green: 0.094, blue: 0.106, alpha: 0.70),
        )
    }

    /// Row inner hairline — white/40 light, white 6% dark.
    static var rowRim: Color {
        adaptive(
            UIColor.white.withAlphaComponent(0.40),
            UIColor.white.withAlphaComponent(0.06),
        )
    }

    /// Glass pill fill — zinc-100/80 light, zinc-900/60 dark.
    static var glassFill: Color {
        adaptive(
            UIColor(red: 0.957, green: 0.957, blue: 0.961, alpha: 0.80),
            UIColor(red: 0.094, green: 0.094, blue: 0.106, alpha: 0.60),
        )
    }

    /// Glass pill ring — zinc-200/70 light, white/10 dark.
    static var hairlineStrong: Color {
        adaptive(
            UIColor(red: 0.894, green: 0.894, blue: 0.906, alpha: 0.70),
            UIColor.white.withAlphaComponent(0.10),
        )
    }

    /// Soft hairline — zinc-100 light, zinc-800 dark.
    static var hairlineSoft: Color {
        adaptive(zinc(100), zinc(800))
    }

    /// Panel separator — zinc-200 light, zinc-800 dark.
    static var hairlinePanel: Color {
        adaptive(zinc(200), zinc(800))
    }

    /// Strong titles — zinc-900 light, zinc-50 dark.
    static var titleOnWash: Color { adaptive(zinc(900), zinc(50)) }

    /// Body titles on panels — zinc-900 light, zinc-100 dark.
    static var titleOnPanel: Color { adaptive(zinc(900), zinc(100)) }

    /// Primary body text — zinc-600 light, zinc-300 dark.
    static var textPrimary: Color { adaptive(zinc(600), zinc(300)) }

    /// Muted body text — zinc-500 light, zinc-400 dark.
    static var textSecondary: Color { adaptive(zinc(500), zinc(400)) }

    /// Faint text — zinc-400 light, zinc-500 dark.
    static var textTertiary: Color { adaptive(zinc(400), zinc(500)) }

    /// Emerald accent that brightens in dark mode (600 light / 400 dark).
    static var accent: Color { adaptive(emerald600, emerald400) }

    /// Presence dot offline tint — zinc-300 light, zinc-600 dark.
    static var presenceOffline: Color { adaptive(zinc(300), zinc(600)) }

    /// Story ring "seen" tint — zinc-300 light, zinc-600 dark.
    static var ringSeen: Color { adaptive(zinc(300), zinc(600)) }

    /// Story ring "none" tint — zinc-200 light, zinc-700 dark.
    static var ringNone: Color { adaptive(zinc(200), zinc(700)) }

    /// Unread badge outer ring — white light, zinc-900 dark.
    static var badgeRing: Color { adaptive(.white, zinc(900)) }

    /// Filter chip inactive fill — zinc-100 light, zinc-800 dark.
    static var chipFill: Color { adaptive(zinc(100), zinc(800)) }

    /// Pressed overlay on rows — zinc-900 4% light, white 5% dark.
    static var pressOverlay: Color {
        adaptive(
            UIColor.black.withAlphaComponent(0.04),
            UIColor.white.withAlphaComponent(0.05),
        )
    }

    /// Pinned wash — emerald 4.5% light, 6% dark.
    static var pinnedWash: Color {
        adaptive(
            UIColor(red: 0.063, green: 0.725, blue: 0.506, alpha: 0.045),
            UIColor(red: 0.063, green: 0.725, blue: 0.506, alpha: 0.06),
        )
    }

    /// Dock/panel shadow strength — heavier in dark.
    static var panelShadowOpacity: Double { 0.14 }

    // ── gradients ────────────────────────────────────────────
    /// Emerald→teal brand gradient (compose button, dock badge, glyph tiles).
    static var brandGradient: LinearGradient {
        LinearGradient(colors: [emerald500, teal600], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Active dock pill — emerald 500/20 → 500/6 top-to-bottom (dark: 400/16 → 5%).
    static var dockPillGradient: LinearGradient {
        LinearGradient(
            colors: [adaptive(emerald500.opacity(0.20), emerald400.opacity(0.16)),
                     adaptive(emerald500.opacity(0.06), emerald400.opacity(0.05))],
            startPoint: .top, endPoint: .bottom,
        )
    }

    /// Group avatar gradient picked by hashing the conversation id
    /// (web GROUP_GRADIENTS — violet family, 4 variants).
    static func groupGradient(for id: String) -> LinearGradient {
        let palettes: [[Color]] = [
            [violet400, purple600],
            [fuchsia400, purple600],
            [purple400, violet700],
            [fuchsia500, violet700],
        ]
        let picked = palettes[Self.hashString(id) % palettes.count]
        return LinearGradient(colors: picked, startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Web hashString — h = (h * 31 + charCode) | 0, folded positive.
    static func hashString(_ value: String) -> Int {
        var h: Int32 = 0
        for unit in value.utf16 {
            h = (h &* 31) &+ Int32(bitPattern: UInt32(unit))
        }
        return Int(UInt32(bitPattern: h) & 0x7FFF_FFFF)
    }

    /// Streak heat ring sweep — amber → rose → fading amber (conic, web §7.4).
    static var heatRingGradient: AngularGradient {
        let stops: [Gradient.Stop] = [
            .init(color: amber500.opacity(0.9), location: 0),
            .init(color: rose400.opacity(0.85), location: 0.38),
            .init(color: amber500.opacity(0.35), location: 0.70),
            .init(color: .clear, location: 0.85),
            .init(color: amber500.opacity(0.9), location: 1.0),
        ]
        return AngularGradient(stops: stops, center: .center)
    }

    /// Legacy flat accent tints kept for older surfaces (Hub tiles, room UI).
    static let teal = Color(red: 0.063, green: 0.722, blue: 0.651)
    static let violet = Color(red: 0.545, green: 0.365, blue: 0.965)
    static let amber = Color(red: 0.961, green: 0.620, blue: 0.043)
    static let rose = Color(red: 0.984, green: 0.445, blue: 0.522)

    /// Unseen story ring sweep — the four emerald/teal greens (web §5).
    static var storyRingGradient: AngularGradient {
        AngularGradient(
            stops: [
                .init(color: Color(red: 0.204, green: 0.827, blue: 0.600), location: 0),   // #34d399
                .init(color: Color(red: 0.078, green: 0.722, blue: 0.651), location: 0.33), // #14b8a6
                .init(color: Color(red: 0.431, green: 0.906, blue: 0.718), location: 0.66), // #6ee7b7
                .init(color: Color(red: 0.063, green: 0.725, blue: 0.506), location: 1.0),  // #10b981
            ],
            center: .center,
        )
    }

    // ── member-color helpers (web AVATAR_GRADIENTS, 400→600 pairs) ──
    private static func palette(named name: String?) -> [Color] {
        switch name {
        case "rose": return [rose400, rose500]
        case "amber": return [Color(red: 0.984, green: 0.749, blue: 0.141), amber600]
        case "violet": return [violet400, Color(red: 0.486, green: 0.227, blue: 0.929)]
        case "teal": return [Color(red: 0.176, green: 0.831, blue: 0.749), teal600]
        case "orange": return [Color(red: 0.984, green: 0.573, blue: 0.235), Color(red: 0.918, green: 0.345, blue: 0.047)]
        case "pink": return [Color(red: 0.957, green: 0.447, blue: 0.694), Color(red: 0.859, green: 0.153, blue: 0.467)]
        case "cyan": return [Color(red: 0.133, green: 0.827, blue: 0.933), Color(red: 0.031, green: 0.569, blue: 0.698)]
        default: return [emerald400, emerald600]
        }
    }

    /// Avatar gradient keyed by the server's color name (web gradientFor).
    static func gradient(named name: String?) -> LinearGradient {
        LinearGradient(colors: palette(named: name), startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Flat tint from a wire color name (falls back to emerald).
    static func color(named name: String?) -> Color {
        palette(named: name).first ?? emerald
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

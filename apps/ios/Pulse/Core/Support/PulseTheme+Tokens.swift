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

    // ── R2-D — active design language (web ui-theme.ts R25 parity) ──────
    // PulsePrefs owns the persisted selection; RootView mirrors it here so
    // every PulseTheme-fed view swaps tokens without any screen redesign.
    // Main-thread only (set at shell attach + on change).
    static var activeUiTheme: PulseUiThemeId = .glass

    /// THE accent token — theme-driven (glass emerald #10b981, kinetic ink
    /// flipping to near-white in dark, minimal zinc, dynamic amber, aero
    /// sky). Resolved per color-scheme trait at draw time.
    static var accent: Color {
        let id = activeUiTheme
        let light = PulseUiTheme.tokens(for: id, dark: false).accent
        let dark = PulseUiTheme.tokens(for: id, dark: true).accent
        return adaptive(light.color, dark.color)
    }

    /// Theme secondary accent (glass sky #0ea5e9, kinetic rose, minimal
    /// zinc-400, dynamic pink, aero indigo) — adaptive like accent.
    static var accent2: Color {
        let id = activeUiTheme
        let light = PulseUiTheme.tokens(for: id, dark: false).accent2
        let dark = PulseUiTheme.tokens(for: id, dark: true).accent2
        return adaptive(light.color, dark.color)
    }

    /// Panel radius base for the active language (glass 28 base; kinetic 14,
    /// minimal 22, dynamic 26, aero 24 — globals.css --ui-radius-panel).
    static var radiusPanel: CGFloat {
        PulseUiTheme.tokens(for: activeUiTheme, dark: false).radiusPanel
    }

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
    /// Brand gradient — accent → accent2 of the ACTIVE design language
    /// (glass keeps the emerald→teal feel via its sky secondary; kinetic
    /// ink→rose, minimal zinc pair, dynamic amber→pink, aero sky→indigo).
    static var brandGradient: LinearGradient {
        let id = activeUiTheme
        let light = PulseUiTheme.tokens(for: id, dark: false)
        let dark = PulseUiTheme.tokens(for: id, dark: true)
        let start = adaptive(light.accent.color, dark.accent.color)
        let end = adaptive(light.accent2.color, dark.accent2.color)
        return LinearGradient(colors: [start, end], startPoint: .topLeading, endPoint: .bottomTrailing)
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

// ─────────────────────────────────────────────────────────────
// R2-D — the five locked design languages, ported verbatim from
// web src/lib/ui-theme.ts:35-76 (meta: label/tagline/detail/swatch/motion)
// + the token blocks of src/app/globals.css:124-240 (accent pair, radius
// panel, page/panel/border colors, blur, panel alpha per light+dark).
// Persistence rides PulsePrefs under the web's exact key
// `pulse.uiTheme.v2` with byte-identical values glass|kinetic|minimal|dynamic|aero.
// ─────────────────────────────────────────────────────────────

/// Theme ids — raw values are the wire strings (web UiThemeId).
public enum PulseUiThemeId: String, CaseIterable, Sendable {
    case glass, kinetic, minimal, dynamic, aero
}

/// One language's picker metadata (web UiThemeMeta — strings byte-same).
public struct PulseUiThemeMeta: Equatable, Sendable {
    public let id: PulseUiThemeId
    public let label: String
    public let tagline: String
    /// one-line description for pickers
    public let detail: String
    /// preview accent pair (hex) for swatches — web swatch order preserved
    public let swatch: [String]
    /// motion personality — surfaces may pick springs by theme
    public let motion: String
}

/// A decoded CSS color (hex + alpha) — pure value so tokens stay testable.
public struct PulseUiThemeColor: Equatable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double
    public let alpha: Double

    /// "#RRGGBB" / "RRGGBB" (+ separate alpha, globals rgba). Junk input
    /// degrades to opaque black — never crashes, never traps.
    public init(hex: String, alpha: Double = 1.0) {
        let digits = hex.filter { $0.isHexDigit }
        let padded = digits.count >= 6
            ? String(digits.prefix(6))
            : digits + String(repeating: "0", count: 6 - digits.count)
        let value = UInt64(padded, radix: 16) ?? 0
        self.red = Double((value >> 16) & 0xFF) / 255.0
        self.green = Double((value >> 8) & 0xFF) / 255.0
        self.blue = Double(value & 0xFF) / 255.0
        self.alpha = alpha
    }

    public var color: Color {
        Color(red: red, green: green, blue: blue, opacity: alpha)
    }
}

/// The resolved token set for one language + scheme (globals.css --ui-*).
public struct PulseUiThemeTokens: Equatable, Sendable {
    public let accent: PulseUiThemeColor
    public let accent2: PulseUiThemeColor
    /// 28pt base (glass); kinetic 14 · minimal 22 · dynamic 26 · aero 24.
    public let radiusPanel: CGFloat
    public let pageBg: PulseUiThemeColor
    public let panelBg: PulseUiThemeColor
    public let panelBorder: PulseUiThemeColor
    /// --ui-blur (pt); 0 = the language renders hard edges (kinetic).
    public let blur: CGFloat
    /// --ui-panel-alpha == panelBg alpha (kept explicit for surfaces that
    /// layer their own material under the wash).
    public var panelAlpha: Double { panelBg.alpha }
}

public enum PulseUiTheme {

    /// Web DEFAULT_UI_THEME.
    public static let defaultId: PulseUiThemeId = .glass

    /// The metadata table — web UI_THEMES verbatim (ui-theme.ts:35-76).
    public static func meta(for id: PulseUiThemeId) -> PulseUiThemeMeta {
        switch id {
        case .glass:
            return PulseUiThemeMeta(
                id: .glass,
                label: "Immersive Glass",
                tagline: "Glassmorphic Chat UI",
                detail: "Layered frosted glass, aurora backdrop, specular edges, elastic motion.",
                swatch: ["#10b981", "#0ea5e9"],
                motion: "elastic",
            )
        case .kinetic:
            return PulseUiThemeMeta(
                id: .kinetic,
                label: "Kinetic",
                tagline: "Kinetic UI",
                detail: "High-contrast ink, sharp corners, bold type, whip-crack springs.",
                swatch: ["#18181b", "#f43f5e"],
                motion: "crisp",
            )
        case .minimal:
            return PulseUiThemeMeta(
                id: .minimal,
                label: "Quiet Minimal",
                tagline: "Motion-Driven Minimalist UI",
                detail: "Hairlines and whitespace. Motion whispers, structure speaks.",
                swatch: ["#52525b", "#a1a1aa"],
                motion: "quiet",
            )
        case .dynamic:
            return PulseUiThemeMeta(
                id: .dynamic,
                label: "Dynamic",
                tagline: "Dynamic Minimalism",
                detail: "Soft neutrals with vivid gradient accents and playful bounce.",
                swatch: ["#f59e0b", "#ec4899"],
                motion: "playful",
            )
        case .aero:
            return PulseUiThemeMeta(
                id: .aero,
                label: "Aero Kinetic",
                tagline: "Kinetic Minimalist Interface",
                detail: "Frost-stroke panels on cool graphite, gliding inertia.",
                swatch: ["#38bdf8", "#818cf8"],
                motion: "glide",
            )
        }
    }

    public static func allMeta() -> [PulseUiThemeMeta] {
        PulseUiThemeId.allCases.map { meta(for: $0) }
    }

    /// Tolerant decode — web isUiThemeId parity: anything outside the five
    /// locked ids (nil, junk, legacy values) falls back to glass.
    public static func parse(_ raw: String?) -> PulseUiThemeId {
        guard let raw, !raw.isEmpty else { return defaultId }
        return PulseUiThemeId(rawValue: raw) ?? defaultId
    }

    /// Token resolution (globals.css [data-ui='ui-*'] blocks, light+dark).
    /// Kinetic is the only language whose accent flips in dark (ink → paper).
    public static func tokens(for id: PulseUiThemeId, dark: Bool) -> PulseUiThemeTokens {
        switch id {
        case .glass:
            // ui-glass — aurora wash over warm paper / near-black.
            return PulseUiThemeTokens(
                accent: PulseUiThemeColor(hex: "#10b981"),
                accent2: PulseUiThemeColor(hex: "#0ea5e9"),
                radiusPanel: 28,
                pageBg: dark ? PulseUiThemeColor(hex: "#09090b") : PulseUiThemeColor(hex: "#f3f9f5"),
                panelBg: dark
                    ? PulseUiThemeColor(hex: "#ffffff", alpha: 0.08)
                    : PulseUiThemeColor(hex: "#ffffff", alpha: 0.55),
                panelBorder: dark
                    ? PulseUiThemeColor(hex: "#ffffff", alpha: 0.14)
                    : PulseUiThemeColor(hex: "#092a1f", alpha: 0.10),
                blur: 28,
            )
        case .kinetic:
            // ui-kinetic — ink on paper, hard edges, high contrast.
            return PulseUiThemeTokens(
                accent: dark ? PulseUiThemeColor(hex: "#fafafa") : PulseUiThemeColor(hex: "#18181b"),
                accent2: PulseUiThemeColor(hex: "#f43f5e"),
                radiusPanel: 14,
                pageBg: dark ? PulseUiThemeColor(hex: "#111113") : PulseUiThemeColor(hex: "#f4f4f5"),
                panelBg: dark ? PulseUiThemeColor(hex: "#101012") : PulseUiThemeColor(hex: "#ffffff"),
                panelBorder: dark
                    ? PulseUiThemeColor(hex: "#ffffff", alpha: 0.85)
                    : PulseUiThemeColor(hex: "#000000", alpha: 0.82),
                blur: 0,
            )
        case .minimal:
            // ui-minimal — hairlines, whitespace, whisper motion.
            return PulseUiThemeTokens(
                accent: PulseUiThemeColor(hex: "#52525b"),
                accent2: PulseUiThemeColor(hex: "#a1a1aa"),
                radiusPanel: 22,
                pageBg: dark ? PulseUiThemeColor(hex: "#0c0c0e") : PulseUiThemeColor(hex: "#fbfbfc"),
                panelBg: dark
                    ? PulseUiThemeColor(hex: "#141417", alpha: 0.9)
                    : PulseUiThemeColor(hex: "#ffffff", alpha: 0.92),
                panelBorder: dark
                    ? PulseUiThemeColor(hex: "#ffffff", alpha: 0.07)
                    : PulseUiThemeColor(hex: "#000000", alpha: 0.06),
                blur: 14,
            )
        case .dynamic:
            // ui-dynamic — soft neutrals + vivid gradient accents.
            return PulseUiThemeTokens(
                accent: PulseUiThemeColor(hex: "#f59e0b"),
                accent2: PulseUiThemeColor(hex: "#ec4899"),
                radiusPanel: 26,
                pageBg: dark ? PulseUiThemeColor(hex: "#0c0b0d") : PulseUiThemeColor(hex: "#faf9f7"),
                panelBg: dark
                    ? PulseUiThemeColor(hex: "#18161a", alpha: 0.78)
                    : PulseUiThemeColor(hex: "#ffffff", alpha: 0.82),
                panelBorder: dark
                    ? PulseUiThemeColor(hex: "#ffffff", alpha: 0.08)
                    : PulseUiThemeColor(hex: "#000000", alpha: 0.06),
                blur: 22,
            )
        case .aero:
            // ui-aero — frost strokes on cool graphite.
            return PulseUiThemeTokens(
                accent: PulseUiThemeColor(hex: "#38bdf8"),
                accent2: PulseUiThemeColor(hex: "#818cf8"),
                radiusPanel: 24,
                pageBg: dark ? PulseUiThemeColor(hex: "#0e1118") : PulseUiThemeColor(hex: "#eef1f6"),
                panelBg: dark
                    ? PulseUiThemeColor(hex: "#11151e", alpha: 0.6)
                    : PulseUiThemeColor(hex: "#ffffff", alpha: 0.55),
                panelBorder: dark
                    ? PulseUiThemeColor(hex: "#818cf8", alpha: 0.2)
                    : PulseUiThemeColor(hex: "#818cf8", alpha: 0.22),
                blur: 34,
            )
        }
    }
}

import SwiftUI

/// Native ambient field — the outcome of the web's WebGL system rebuilt with
/// Apple APIs: aurora / mesh / stars run as real per-pixel Metal kernels
/// (AmbientShader.metal via .colorEffect, iOS 17+), caustics / liquid as
/// graceful Canvas-gradient approximations. Renders nothing for .off.
/// Reduce Motion renders ONE static frame (fixed time), matching the web.
public struct AmbientFieldView: View {
    public let mode: AmbientMode
    public let dark: Bool
    /// Preview strips force a single frame and drop fullscreen bleed.
    public var preview: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The web starts its loop at t = 14.2 — a pleasing moment of the field.
    private let epoch = Date(timeIntervalSinceNow: -14.2)

    public init(mode: AmbientMode, dark: Bool, preview: Bool = false) {
        self.mode = mode
        self.dark = dark
        self.preview = preview
    }

    public var body: some View {
        Group {
            switch mode {
            case .off:
                Color.clear
            case .aurora, .mesh, .stars:
                shaderField
            case .caustics, .liquid:
                CanvasAmbient(mode: mode, dark: dark, reduceMotion: reduceMotion)
            }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea(edges: preview ? [] : .all)
        .accessibilityHidden(true)
    }

    // ── Metal path (aurora / mesh / stars) ───────────────────
    private var shaderField: some View {
        Group {
            if reduceMotion || preview {
                staticShader(time: 14.2)
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                    staticShader(time: timeline.date.timeIntervalSince(epoch))
                }
            }
        }
    }

    private func staticShader(time: Double) -> some View {
        GeometryReader { geo in
            let w = max(Float(geo.size.width), 1)
            let h = max(Float(geo.size.height), 1)
            Rectangle()
                .colorEffect(fieldShader(width: w, height: h, time: time))
        }
    }

    private func fieldShader(width: Float, height: Float, time: Double) -> Shader {
        let common: [Shader.Argument] = [
            .float2(width, height),
            .float(Float(time)),
            .float(1.0),
            .float(dark ? 1.0 : 0.0),
        ]
        switch mode {
        case .mesh:
            return ShaderLibrary.meshField(common[0], common[1], common[2], common[3])
        case .stars:
            return ShaderLibrary.starsField(common[0], common[1], common[2], common[3])
        default:
            return ShaderLibrary.auroraField(common[0], common[1], common[2], common[3])
        }
    }
}

/// Canvas approximations for the two fluid modes (allowed by the wave spec —
/// the aurora family carries the signature look; these stay tasteful echoes).
private struct CanvasAmbient: View {
    let mode: AmbientMode
    let dark: Bool
    let reduceMotion: Bool

    var body: some View {
        Group {
            if reduceMotion {
                Canvas { context, size in
                    draw(&context, size: size, time: 14.2)
                }
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                    Canvas { context, size in
                        draw(&context, size: size, time: timeline.date.timeIntervalSinceReferenceDate)
                    }
                }
            }
        }
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, time: Double) {
        if mode == .caustics {
            drawCaustics(&context, size: size, time: time)
        } else {
            drawLiquid(&context, size: size, time: time)
        }
    }

    private func drawCaustics(_ context: inout GraphicsContext, size: CGSize, time: Double) {
        let base = dark ? Color(red: 0.008, green: 0.050, blue: 0.055) : Color(red: 0.940, green: 0.970, blue: 0.970)
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(base))

        var glow = context
        glow.addFilter(.blur(radius: dark ? 26 : 34))
        glow.blendMode = dark ? .plusLighter : .normal
        let aqua = dark ? Color(red: 0.30, green: 0.91, blue: 0.85) : Color(red: 0.10, green: 0.55, blue: 0.55).opacity(0.35)
        let emerald = dark ? Color(red: 0.063, green: 0.725, blue: 0.506) : Color(red: 0.063, green: 0.50, blue: 0.38).opacity(0.22)
        for (index, seed) in [0.0, 2.1, 4.2].enumerated() {
            let cx = size.width * (0.30 + 0.16 * sin(time * (0.28 + seed * 0.04) + seed))
            let cy = size.height * (0.34 + 0.20 * cos(time * (0.22 + seed * 0.03) + seed * 1.7))
            let r = min(size.width, size.height) * (0.30 + 0.08 * sin(time * 0.4 + seed)) * (index == 1 ? 1.2 : 1.0)
            let rect = CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)
            glow.fill(Path(ellipseIn: rect), with: .radialGradient(
                Gradient(colors: [index == 1 ? emerald : aqua, .clear]),
                center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: r,
            ))
        }
    }

    private func drawLiquid(_ context: inout GraphicsContext, size: CGSize, time: Double) {
        let base = dark ? Color(red: 0.010, green: 0.042, blue: 0.036) : Color(red: 0.968, green: 0.976, blue: 0.972)
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(base))

        var glow = context
        glow.addFilter(.blur(radius: dark ? 44 : 56))
        glow.blendMode = dark ? .plusLighter : .normal

        // Five drifting metaball centers (Lissajous family, web R31-b spirit).
        let specs: [(CGFloat, CGFloat, CGFloat, CGFloat, Color)] = [
            (0.34, 0.38, 0.11, 0.31, dark ? Color(red: 0.063, green: 0.725, blue: 0.506) : Color(red: 0.063, green: 0.60, blue: 0.44).opacity(0.3)),
            (0.62, 0.30, 0.13, 0.24, dark ? Color(red: 0.078, green: 0.722, blue: 0.651) : Color(red: 0.078, green: 0.60, blue: 0.54).opacity(0.24)),
            (0.50, 0.68, 0.10, 0.27, dark ? Color(red: 0.957, green: 0.247, blue: 0.369).opacity(0.55) : Color(red: 0.957, green: 0.35, blue: 0.45).opacity(0.14)),
            (0.78, 0.58, 0.12, 0.19, dark ? Color(red: 0.063, green: 0.725, blue: 0.506).opacity(0.8) : Color(red: 0.063, green: 0.55, blue: 0.42).opacity(0.2)),
            (0.24, 0.66, 0.09, 0.23, dark ? Color(red: 0.078, green: 0.722, blue: 0.651).opacity(0.7) : Color(red: 0.078, green: 0.58, blue: 0.52).opacity(0.18)),
        ]
        let minDim = min(size.width, size.height)
        for (ax, ay, fx, fy, color) in specs {
            let cx = size.width * ax + minDim * 0.14 * sin(time * fx * 2.4 + ay * 6.0)
            let cy = size.height * ay + minDim * 0.12 * cos(time * fy * 2.1 + ax * 6.0)
            let r = minDim * (0.24 + 0.05 * sin(time * 0.5 + fx * 5.0))
            let rect = CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)
            glow.fill(Path(ellipseIn: rect), with: .radialGradient(
                Gradient(colors: [color, .clear]),
                center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: r,
            ))
        }
    }
}

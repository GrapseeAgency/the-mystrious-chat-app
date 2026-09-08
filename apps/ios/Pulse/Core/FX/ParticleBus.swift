import SwiftUI

/// Shared particle celebration bus — native twin of the web's
/// fireParticles() window event (confetti / hearts / stars / burst).
/// The overlay is a full-screen Canvas that only animates while particles
/// are alive, never blocks interaction, caps at 400 particles and no-ops
/// under Reduce Motion.
@MainActor
public final class ParticleBus: ObservableObject {
    public enum Kind: String {
        case confetti, hearts, stars, burst
    }

    public struct Particle {
        var x: CGFloat, y: CGFloat        // 0..1 viewport space
        var vx: CGFloat, vy: CGFloat      // per-second velocity
        var size: CGFloat
        var colorSeed: Int
        var rotation: Double
        var angularVelocity: Double
        var bornAt: Double                // seconds since bus epoch
        var life: Double
        var kind: Kind
    }

    @Published private(set) var hasAliveParticles = false

    private var particles: [Particle] = []
    private let epoch = Date()
    private let cap = 400

    public var epochDate: Date { epoch }

    /// Fire a celebration burst. center is 0..1 x/y (web defaults x 0.5, y 0.6).
    public func fire(kind: Kind, count: Int = 80, center: CGPoint = CGPoint(x: 0.5, y: 0.6)) {
        guard !reduceMotionDisabled else { return }
        let now = Date().timeIntervalSince(epoch)
        var fresh: [Particle] = []
        fresh.reserveCapacity(count)
        for index in 0..<count {
            let angle = Double.random(in: 0..<(2 * .pi))
            let speed = Double.random(in: 0.28...0.95)
            let spread = kind == .confetti ? 0.55 : 0.9
            fresh.append(Particle(
                x: center.x + CGFloat(cos(angle)) * 0.015,
                y: center.y + CGFloat(sin(angle)) * 0.015,
                vx: CGFloat(cos(angle) * speed * spread),
                vy: CGFloat(sin(angle) * speed * spread - (kind == .confetti ? 0.35 : 0.1)),
                size: CGFloat.random(in: 5...11),
                colorSeed: index,
                rotation: Double.random(in: 0..<(2 * .pi)),
                angularVelocity: Double.random(in: -3.5...3.5),
                bornAt: now,
                life: Double.random(in: 1.1...1.9),
                kind: kind,
            ))
        }
        particles.append(contentsOf: fresh)
        if particles.count > cap {
            particles.removeFirst(particles.count - cap)
        }
        hasAliveParticles = true
    }

    /// Flag the bus can consult to skip bursts entirely (reduce motion).
    public var reduceMotionDisabled = false

    /// Particles still alive at `now` (and lazily retires dead ones).
    /// The published idle flip is deferred off the view-update pass.
    public func aliveParticles(now: Double) -> [Particle] {
        let alive = particles.filter { now - $0.bornAt < $0.life }
        particles = alive
        if alive.isEmpty && hasAliveParticles {
            Task { @MainActor in
                self.hasAliveParticles = false
            }
        }
        return alive
    }

    func color(for particle: Particle) -> Color {
        let palette = Self.palette(for: particle.kind)
        if palette.count == 1 { return palette[0] }
        return palette[abs(particle.colorSeed) % palette.count]
    }

    private static func palette(for kind: Kind) -> [Color] {
        switch kind {
        case .hearts: return [Color(red: 0.984, green: 0.445, blue: 0.522)]
        case .confetti: return [Color(red: 0.063, green: 0.725, blue: 0.506), Color(red: 0.078, green: 0.722, blue: 0.651), Color(red: 0.545, green: 0.365, blue: 0.965), Color(red: 0.961, green: 0.620, blue: 0.043)]
        case .stars: return [Color(red: 0.92, green: 0.98, blue: 1.0), Color(red: 0.078, green: 0.722, blue: 0.651)]
        case .burst: return [Color(red: 0.063, green: 0.725, blue: 0.506), Color(red: 0.078, green: 0.722, blue: 0.651), Color(red: 0.92, green: 0.98, blue: 1.0)]
        }
    }
}

/// Full-screen overlay — mount above everything with allowsHitTesting(false).
public struct ParticleOverlayView: View {
    @ObservedObject var bus: ParticleBus
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(bus: ParticleBus) {
        self.bus = bus
    }

    public var body: some View {
        Group {
            if reduceMotion {
                EmptyView()
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 45.0, paused: !bus.hasAliveParticles)) { timeline in
                    Canvas { context, size in
                        draw(&context, size: size, now: timeline.date.timeIntervalSince(bus.epochDate))
                    }
                }
            }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, now: Double) {
        for particle in bus.aliveParticles(now: now) {
            let t = now - particle.bornAt
            let gravity: CGFloat = particle.kind == .confetti ? 0.55 : 0.18
            let drag: CGFloat = particle.kind == .confetti ? 0.35 : 0.9
            let decay = CGFloat(exp(-t * drag))
            let x = (particle.x + particle.vx * decay * CGFloat(t)) * size.width
            let y = (particle.y + particle.vy * decay * CGFloat(t) + gravity * CGFloat(t) * CGFloat(t)) * size.height
            let fade = 1.0 - CGFloat(t / particle.life)
            let wobble: CGFloat = particle.kind == .hearts ? CGFloat(sin(t * 6 + particle.rotation)) * 6 : 0
            let side = particle.size * (0.6 + 0.4 * fade)

            var item = context
            item.opacity = Double(max(fade, 0))
            item.blendMode = .plusLighter
            let rect = CGRect(x: x - side / 2 + wobble, y: y - side / 2, width: side, height: side)
            switch particle.kind {
            case .hearts:
                item.fill(heartPath(in: rect), with: .color(bus.color(for: particle)))
            case .confetti:
                var spin = rect
                item.translateBy(x: spin.midX, y: spin.midY)
                item.rotate(by: .radians(particle.rotation + particle.angularVelocity * t))
                spin.origin = CGPoint(x: -spin.width / 2, y: -spin.height / 2)
                item.fill(Path(spin), with: .color(bus.color(for: particle)))
            default:
                item.fill(Path(ellipseIn: rect), with: .color(bus.color(for: particle)))
            }
        }
    }

    private func heartPath(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width
        let h = rect.height
        path.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        path.addCurve(
            to: CGPoint(x: rect.minX, y: rect.minY + h * 0.3),
            control1: CGPoint(x: rect.minX + w * 0.05, y: rect.minY + h * 0.75),
            control2: CGPoint(x: rect.minX, y: rect.minY + h * 0.5),
        )
        path.addArc(
            center: CGPoint(x: rect.minX + w * 0.25, y: rect.minY + h * 0.25),
            radius: w * 0.25, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false,
        )
        path.addArc(
            center: CGPoint(x: rect.minX + w * 0.75, y: rect.minY + h * 0.25),
            radius: w * 0.25, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false,
        )
        path.addCurve(
            to: CGPoint(x: rect.midX, y: rect.maxY),
            control1: CGPoint(x: rect.maxX, y: rect.minY + h * 0.5),
            control2: CGPoint(x: rect.maxX - w * 0.05, y: rect.minY + h * 0.75),
        )
        path.closeSubpath()
        return path
    }
}

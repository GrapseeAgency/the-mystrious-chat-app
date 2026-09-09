import SwiftUI
import UIKit

/// Palette avatar — photo when present, else initials over a member-color
/// gradient, with an emerald presence ring. Web outcome, SwiftUI implementation.
public struct PulseAvatar: View {
    public let name: String
    public var color: Color
    public var photoURL: URL?
    public var online: Bool
    public var size: CGFloat

    public init(name: String, color: Color, photoURL: URL? = nil, online: Bool = false, size: CGFloat = 52) {
        self.name = name
        self.color = color
        self.photoURL = photoURL
        self.online = online
        self.size = size
    }

    public var body: some View {
        ZStack {
            if let photoURL {
                AsyncImage(url: photoURL) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        initialsLayer
                    }
                }
            } else {
                initialsLayer
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(
            Circle()
                .strokeBorder(online ? PulseTheme.emerald : Color.clear, lineWidth: 2.5)
                .animation(.pulse(.pulseBouncy, reduceMotion: false), value: online)
        )
    }

    private var initialsLayer: some View {
        ZStack {
            LinearGradient(
                colors: [color.opacity(0.9), color],
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )
            Text(PulseFormat.initials(of: name))
                .font(.system(size: size * 0.36, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
        }
    }
}

/// Three drifting dots for "typing…" — bouncy ease, respects nothing since it
/// is content signaling, but scales down under Reduce Motion instead of animating.
public struct TypingDotsView: View {
    @State private var animating = false

    public init() {}

    public var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 5, height: 5)
                    .offset(y: animating ? -2.5 : 1.5)
                    .animation(
                        .easeInOut(duration: 0.45)
                            .repeatForever()
                            .delay(Double(index) * 0.15),
                        value: animating,
                    )
            }
        }
        .onAppear { animating = true }
    }
}

/// Emerald unread capsule — bouncy scale-in like the web badge.
public struct UnreadBadge: View {
    public let count: Int

    public init(count: Int) {
        self.count = count
    }

    public var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.caption.weight(.bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(PulseTheme.emerald))
            .transition(.scale(scale: 0.4).combined(with: .opacity))
    }
}

/// Centered capsule for system messages (joined, day separators).
public struct CapsuleLabel: View {
    public let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(.ultraThinMaterial))
    }
}

// ══════════════════════════════════════════════════════════════
// Home-rebuild primitives (N10-b) — squircle, glass recipes,
// presence halo, streak heat ring, story rings, badges, chips.
// ══════════════════════════════════════════════════════════════

/// Superellipse ("squircle") silhouette — the web .pulse-squircle mask.
/// Groups and channels are THINGS (squircle); people stay circular.
public struct SuperellipseShape: Shape {
    public init() {}

    public func path(in rect: CGRect) -> Path {
        let n: CGFloat = 5 // iOS-icon-style superellipse exponent
        let cx = rect.midX
        let cy = rect.midY
        let a = rect.width / 2
        let b = rect.height / 2
        var points: [CGPoint] = []
        let steps = 96
        for i in 0...steps {
            let t = (CGFloat(i) / CGFloat(steps)) * 2 * .pi
            let cosT = cos(t)
            let sinT = sin(t)
            let x = cx + a * sign(cosT) * pow(abs(cosT), 2.0 / n)
            let y = cy + b * sign(sinT) * pow(abs(sinT), 2.0 / n)
            points.append(CGPoint(x: x, y: y))
        }
        return Path { path in
            guard let first = points.first else { return }
            path.move(to: first)
            for point in points.dropFirst() {
                path.addLine(to: point)
            }
            path.closeSubpath()
        }
    }

    private func sign(_ value: CGFloat) -> CGFloat {
        value < 0 ? -1 : 1
    }
}

/// Top-edge specular highlight for deep glass cards (the web glass-sheen).
struct GlassSheen: View {
    var cornerRadius: CGFloat

    var body: some View {
        LinearGradient(
            colors: [Color.white.opacity(0.55), Color.white.opacity(0.0)],
            startPoint: .top, endPoint: .bottom,
        )
        .blendMode(.plusLighter)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .allowsHitTesting(false)
    }
}

/// One row of the chats loading skeleton — 48pt circle + two shimmer bars.
struct RowSkeletonView: View {
    @State private var shimmering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(shimmerFill)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(shimmerFill)
                    .frame(width: 120, height: 14)
                RoundedRectangle(cornerRadius: 4)
                    .fill(shimmerFill)
                    .frame(width: 220, height: 12)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                shimmering = true
            }
        }
    }

    private var shimmerFill: Color {
        PulseTheme.zinc(200).opacity(shimmering ? 0.45 : 0.9)
    }
}

/// Pulsing emerald presence halo behind online DM avatars (web PresenceGlow).
struct PresenceHalo: View {
    @State private var pulsing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                Circle()
                    .strokeBorder(PulseTheme.emerald400.opacity(0.5), lineWidth: 2)
                    .padding(-3)
            } else {
                Circle()
                    .strokeBorder(PulseTheme.emerald400.opacity(pulsing ? 0.18 : 0.60), lineWidth: 2)
                    .padding(-3)
                    .scaleEffect(pulsing ? 1.14 : 1.0)
                    .shadow(color: PulseTheme.emerald500.opacity(0.35), radius: 7)
                    .onAppear {
                        withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                            pulsing = true
                        }
                    }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Snapchat-style live-streak heat ring — conic amber/rose sweep around a
/// DM avatar. `level` 1/2/3 sets ring thickness and overhang (web §7.4).
struct StreakHeatRing: View {
    let level: Int

    var body: some View {
        let pad = CGFloat(level == 3 ? 5 : level == 2 ? 4 : 3)
        Circle()
            .strokeBorder(PulseTheme.heatRingGradient, lineWidth: pad - 1)
            .frame(width: 48 + pad, height: 48 + pad)
            .allowsHitTesting(false)
    }
}

/// Row unread capsule — 18pt emerald pill, white 2pt ring, "99+" cap.
struct RowUnreadBadge: View {
    let count: Int

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .frame(minWidth: 18, minHeight: 18)
            .background(Capsule().fill(PulseTheme.emerald500))
            .overlay(Capsule().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
            .shadow(color: PulseTheme.emerald600.opacity(0.40), radius: 2, y: 1)
    }
}

/// Muted BellOff chip — count when unread, "Muted" text when read.
struct MutedChip: View {
    let unreadCount: Int

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "bell.slash")
                .font(.system(size: 10))
            if unreadCount > 0 {
                Text(unreadCount > 99 ? "99+" : "\(unreadCount)")
            } else {
                Text("Muted").font(.system(size: 10, weight: .semibold))
            }
        }
        .font(.system(size: 10, weight: .bold))
        .foregroundStyle(unreadCount > 0 ? PulseTheme.textPrimary : PulseTheme.textTertiary)
        .padding(.horizontal, 6)
        .frame(height: 18)
        .background {
            if unreadCount > 0 {
                Capsule().fill(PulseTheme.chipFill)
            }
        }
        .overlay {
            if unreadCount > 0 {
                Capsule().strokeBorder(PulseTheme.badgeRing, lineWidth: 2)
            }
        }
    }
}

/// Tiny uppercase section header with an emerald count chip + hairline.
struct SectionHeader: View {
    let label: String
    var count: Int = 0

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(PulseTheme.textTertiary)
            if count > 0 {
                Text(count > 99 ? "99+" : "\(count)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(PulseTheme.accent)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 16, minHeight: 16)
                    .background(Capsule().fill(PulseTheme.emerald500.opacity(0.15)))
            }
            Rectangle()
                .fill(PulseTheme.hairlineSoft)
                .frame(height: 1)
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 2)
    }
}

/// List-row avatar — DM circle (member gradient + initials + glass rim +
/// presence dot) or group squircle (violet gradient by id hash + initials,
/// circular photo override). Web user-avatar.tsx / GroupAvatar outcome.
struct RowAvatar: View {
    enum AvatarShape { case circle, squircle }

    let name: String
    var colorName: String?
    var photoPath: String?
    var size: CGFloat = 48
    var groupID: String? = nil
    var avatarShape: AvatarShape = .circle
    var showPresence: Bool = false
    var online: Bool = false

    private var gradient: LinearGradient {
        if let groupID {
            return PulseTheme.groupGradient(for: groupID)
        }
        return PulseTheme.gradient(named: colorName)
    }

    private var photo: URL? {
        PulseTheme.photoURL(photoPath)
    }

    private var initialsFont: CGFloat {
        max(11, round(size * (groupID != nil ? 0.34 : 0.36)))
    }

    var body: some View {
        ZStack {
            if let photo {
                AsyncImage(url: photo) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        initialsLayer
                    }
                }
            } else {
                initialsLayer
            }
        }
        .frame(width: size, height: size)
        .clipShape(clipShape)
        .overlay(clipShape.strokeBorder(rimGradient, lineWidth: 1))
        .overlay(alignment: .bottomTrailing) {
            if showPresence {
                let dot = max(9, round(size * 0.26))
                Circle()
                    .fill(online ? PulseTheme.emerald500 : PulseTheme.presenceOffline)
                    .frame(width: dot, height: dot)
                    .overlay(Circle().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
                    .offset(x: 1, y: 1)
            }
        }
    }

    private var clipShape: some Shape {
        switch avatarShape {
        case .circle: return AnyShape(Circle())
        case .squircle:
            // Circular photo overrides the palette tile silhouette (web parity).
            return AnyShape(photo != nil ? AnyShape(Circle()) : AnyShape(SuperellipseShape()))
        }
    }

    private var rimGradient: LinearGradient {
        LinearGradient(
            colors: [Color.white.opacity(0.55), Color.white.opacity(0.08)],
            startPoint: .top, endPoint: .bottom,
        )
    }

    private var initialsLayer: some View {
        ZStack {
            gradient
            Text(PulseFormat.initials(of: name))
                .font(.system(size: initialsFont, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
        }
    }
}

/// One cell of the stories row — 56pt ring with an animated conic sweep for
/// unseen status, static zinc ring for seen/none, and the emerald plus badge
/// on "My status" when no live story exists.
struct StoryRingCell: View {
    let name: String
    let color: String?
    let ring: Ring
    let plus: Bool
    let label: String
    let onPress: () -> Void

    enum Ring { case unseen, seen, none }

    @State private var spinning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let cellSize: CGFloat = 56
    private var innerSize: CGFloat { cellSize - 5 }

    var body: some View {
        Button(action: onPress) {
            VStack(spacing: 4) {
                ZStack {
                    if ring == .unseen {
                        Circle()
                            .fill(PulseTheme.storyRingGradient)
                            .rotationEffect(.degrees(spinning ? 360 : 0))
                            .onAppear {
                                guard !reduceMotion else { return }
                                withAnimation(.linear(duration: 6).repeatForever(autoreverses: false)) {
                                    spinning = true
                                }
                            }
                    } else {
                        Circle()
                            .fill(ring == .seen ? PulseTheme.ringSeen : PulseTheme.ringNone)
                    }
                    Circle()
                        .fill(PulseTheme.badgeRing)
                        .padding(2.5)
                    RowAvatar(
                        name: name,
                        colorName: color,
                        photoPath: nil,
                        size: innerSize,
                        shape: .circle,
                    )
                }
                .frame(width: cellSize, height: cellSize)
                .overlay(alignment: .bottomTrailing) {
                    if plus {
                        Image(systemName: "plus")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 20, height: 20)
                            .background(Circle().fill(PulseTheme.emerald500))
                            .overlay(Circle().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
                            .offset(x: 2, y: 2)
                    }
                }
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .lineLimit(1)
                    .frame(width: 64)
            }
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(ring == .unseen ? "\(label) — new status" : label)
    }
}

/// Glass action sheet wrapper presenting the system share sheet for
/// exported chat transcripts.
struct ActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

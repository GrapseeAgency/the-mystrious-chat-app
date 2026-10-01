import SwiftUI
import UIKit

/// Palette avatar - photo when present, else initials over a member-color
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
                .strokeBorder(online ? PulseTheme.emberOnline : Color.clear, lineWidth: 2.5)
                .animation(.pulse(.pulseBouncy, reduceMotion: PulseMotion.reduceMotion), value: online)
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

/// Three drifting dots for "typing…" - bouncy ease, respects nothing since it
/// is content signaling, but scales down under Reduce Motion instead of animating.
public struct TypingDotsView: View {
    @State private var animating = false

    public init() {}

    public var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(PulseTheme.emberOnline)
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

/// Ember unread capsule - bouncy scale-in like the web badge.
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
            .background(Capsule().fill(PulseTheme.emberRed))
            .transition(.scale(scale: 0.4).combined(with: .opacity))
    }
}

/// Centered capsule for system messages (joined, day separators).
/// Ember: 11pt white 40% on a white 8% glass capsule.
public struct CapsuleLabel: View {
    public let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(Color.white.opacity(0.40))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.white.opacity(0.08)))
    }
}

// ══════════════════════════════════════════════════════════════
// Home-rebuild primitives (N10-b) - squircle, glass recipes,
// presence halo, streak heat ring, story rings, badges, chips.
// ══════════════════════════════════════════════════════════════

/// Superellipse ("squircle") silhouette - the web .pulse-squircle mask.
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

/// One row of the chats loading skeleton - 48pt circle + two shimmer bars.
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
        Color.white.opacity(shimmering ? 0.08 : 0.16)
    }
}

/// Pulsing ember presence halo behind online DM avatars (web PresenceGlow).
struct PresenceHalo: View {
    @State private var pulsing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                Circle()
                    .strokeBorder(PulseTheme.emberOnline.opacity(0.5), lineWidth: 2)
                    .padding(-3)
            } else {
                Circle()
                    .strokeBorder(PulseTheme.emberOnline.opacity(pulsing ? 0.18 : 0.60), lineWidth: 2)
                    .padding(-3)
                    .scaleEffect(pulsing ? 1.14 : 1.0)
                    .shadow(color: PulseTheme.emberGlowBottom.opacity(0.35), radius: 7)
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

/// Snapchat-style live-streak heat ring - conic amber/rose sweep around a
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

/// Row unread badge - 20pt ember-red circle, white 12pt bold count.
struct RowUnreadBadge: View {
    let count: Int

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .frame(minWidth: 20, minHeight: 20)
            .background(Circle().fill(PulseTheme.emberRed))
            .overlay(Circle().strokeBorder(PulseTheme.emberDotRing, lineWidth: 2))
    }
}

/// Muted BellOff chip - count when unread, "Muted" text when read.
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
        .foregroundStyle(Color.white.opacity(unreadCount > 0 ? 0.85 : 0.35))
        .padding(.horizontal, 6)
        .frame(height: 18)
        .background {
            if unreadCount > 0 {
                Capsule().fill(Color.white.opacity(0.08))
            }
        }
        .overlay {
            if unreadCount > 0 {
                Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
            }
        }
    }
}

/// Tiny uppercase section header with a quiet count chip + hairline.
struct SectionHeader: View {
    let label: String
    var count: Int = 0

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(Color.white.opacity(0.40))
            if count > 0 {
                Text(count > 99 ? "99+" : "\(count)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.55))
                    .padding(.horizontal, 6)
                    .frame(minWidth: 16, minHeight: 16)
                    .background(Capsule().fill(Color.white.opacity(0.10)))
            }
            Rectangle()
                .fill(Color.white.opacity(0.10))
                .frame(height: 1)
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 2)
    }
}

/// List-row avatar - DM circle (member gradient + initials + glass rim +
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
        .overlay(clipShape.stroke(rimGradient, lineWidth: 1))
        .overlay(alignment: .bottomTrailing) {
            if showPresence {
                // Ember presence dot: amber at 11pt on row-scale avatars,
                // ringed in the backdrop ground so it reads on any fill.
                let dot: CGFloat = size >= 52 ? 11 : max(8, round(size * 0.22))
                Circle()
                    .fill(online ? PulseTheme.emberOnline : PulseTheme.presenceOffline)
                    .frame(width: dot, height: dot)
                    .overlay(Circle().strokeBorder(PulseTheme.emberDotRing, lineWidth: size >= 52 ? 2.5 : 2))
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

/// One cell of the stories rail (PULSE EMBER, EMB-I):
///   • "You" tile (no live story) = 56pt circle, white 8% fill, white plus.
///   • story cards = 64x88pt rounded-14 thumbnails via the existing avatar
///     pipeline, 2pt ring: ember sweep (#FFB86B to #FF7A3D) when unseen,
///     quiet white when seen. Name sits below at 11pt white.
struct StoryRingCell: View {
    let name: String
    let color: String?
    let ring: Ring
    let plus: Bool
    let label: String
    let onPress: () -> Void
    /// Avatar path (existing pipeline) for the card fill.
    var photoPath: String? = nil
    /// Latest story image path - wins over the avatar when present.
    var thumbnailPath: String? = nil

    enum Ring { case unseen, seen, none }

    private let cardSize = CGSize(width: 64, height: 88)
    private let youTileSize: CGFloat = 56

    var body: some View {
        Button(action: onPress) {
            VStack(spacing: 5) {
                if ring == .none {
                    youTile
                } else {
                    storyCard
                }
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.90))
                    .lineLimit(1)
                    .frame(width: 68)
            }
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(ring == .unseen ? "\(label) — new status" : label)
    }

    /// The "You" cell - white 8% circle with a centered plus glyph.
    private var youTile: some View {
        ZStack {
            Circle()
                .fill(Color.white.opacity(0.08))
            Circle()
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white)
        }
        .frame(width: youTileSize, height: youTileSize)
    }

    /// The story card - 64x88 rounded 14, ring sweep when unseen.
    private var storyCard: some View {
        thumbnail
            .frame(width: cardSize.width, height: cardSize.height)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(ring == .unseen ? AnyShapeStyle(PulseTheme.emberSignalGradient) : AnyShapeStyle(Color.white.opacity(0.22)), lineWidth: 2),
            )
            .overlay(alignment: .bottomTrailing) {
                if plus {
                    Image(systemName: "plus")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(PulseTheme.emberGlowBottom))
                        .overlay(Circle().strokeBorder(PulseTheme.emberDotRing, lineWidth: 2))
                        .offset(x: 3, y: 3)
                }
            }
    }

    /// Fill: newest story image, else the avatar, else gradient initials.
    private var thumbnail: some View {
        ZStack {
            if let url = PulseTheme.photoURL(thumbnailPath ?? photoPath) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        initialsLayer
                    }
                }
            } else {
                initialsLayer
            }
            LinearGradient(
                colors: [Color.clear, Color.black.opacity(0.35)],
                startPoint: .center, endPoint: .bottom,
            )
        }
    }

    private var initialsLayer: some View {
        ZStack {
            PulseTheme.gradient(named: color)
            Text(PulseFormat.initials(of: name))
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
        }
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

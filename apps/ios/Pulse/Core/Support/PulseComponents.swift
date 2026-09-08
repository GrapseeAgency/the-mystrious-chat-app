import SwiftUI

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

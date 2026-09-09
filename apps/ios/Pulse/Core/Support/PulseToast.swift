import SwiftUI

/// Honest-toast center — one visible message at a time, auto-dismiss.
/// The home page and the dock surface every not-yet-built feature through
/// this (no fakes, no dead taps). Lives on the session so every surface
/// can fire a toast without new plumbing.
@MainActor
public final class ToastCenter: ObservableObject {
    @Published public private(set) var message: String?
    private var hideTask: Task<Void, Never>?

    public init() {}

    public func show(_ text: String) {
        hideTask?.cancel()
        withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) {
            message = text
        }
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            guard !Task.isCancelled, let self else { return }
            withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) {
                self.message = nil
            }
        }
    }
}

/// Bottom floating glass toast capsule — reads like the web sonner stack.
/// Hit-testing disabled so toasts never block the controls beneath them.
struct ToastHostView: View {
    @ObservedObject var center: ToastCenter

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            if let message = center.message {
                Label {
                    Text(message)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                        .multilineTextAlignment(.center)
                } icon: {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(PulseTheme.accent)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .frame(maxWidth: 340)
                .background(
                    Capsule()
                        .fill(.ultraThinMaterial)
                        .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1)),
                )
                .shadow(color: .black.opacity(0.16), radius: 14, y: 6)
                .padding(.bottom, 110)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .allowsHitTesting(false)
        .animation(.pulse(.pulseSnappy, reduceMotion: false), value: center.message)
    }
}

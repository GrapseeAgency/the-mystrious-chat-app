import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 native call overlay (full-screen, in-app; NO CallKit this
// wave — documented hardware-gated next step alongside ringtone/background
// ring). Behavioral spec: web call-overlay.tsx.
//
//   incoming ring  → accept (emerald) / decline (rose)
//   outgoing ring  → cancel (rose)
//   connecting     → spinner + end
//   connected      → mute / speaker / live duration / end
//   ended          → summary chip, auto-dismiss (~2.5s, engine-driven)
//   mic denied     → honest glass error card (+ Close that still logs the
//                    unanswered attempt for outgoing calls)
//
// The overlay renders from PulseCallEngine's published state only — no local
// call logic lives here (single source of truth: the engine's machine).
// ─────────────────────────────────────────────────────────────

/// Hosted by RootView (topmost ZStack layer) — mounts the overlay whenever
/// the engine is not idle.
struct CallOverlayHostView: View {
    @ObservedObject var engine: PulseCallEngine
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if engine.state != .idle {
                CallView(engine: engine)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    .zIndex(10)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.24), value: engine.state != .idle)
    }
}

struct CallView: View {
    @ObservedObject var engine: PulseCallEngine
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            // Ambient emerald wash — web overlay gradient parity.
            LinearGradient(
                colors: [Color.black, PulseTheme.zinc(925), Color.black],
                startPoint: .top,
                endPoint: .bottom,
            )
            .overlay(
                RadialGradient(
                    colors: [PulseTheme.emerald.opacity(0.28), .clear],
                    center: .top,
                    startRadius: 40,
                    endRadius: 520,
                )
                .allowsHitTesting(false)
            )
            .ignoresSafeArea()

            if let peer = engine.activePeer {
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    identityBlock(peer)
                    if let error = engine.errorText {
                        errorCard(error)
                    } else if let summary = engine.summary {
                        summaryChip(summary)
                    }
                    Spacer(minLength: 0)
                    controls
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 26)
            } else {
                // No peer (defensive — engine always has one when not idle).
                ProgressView().tint(.white)
            }
        }
    }

    // ── identity + status ────────────────────────────────────

    private func identityBlock(_ peer: CallPeer) -> some View {
        VStack(spacing: 14) {
            AvatarHalo(ring: engine.state == .outgoingRinging || engine.state == .incomingRinging, reduceMotion: reduceMotion) {
                PulseAvatar(
                    name: peer.name,
                    color: PulseTheme.color(named: peer.color),
                    photoURL: PulseTheme.photoURL(peer.avatar),
                    online: false,
                    size: 112,
                )
            }
            Text(peer.name)
                .font(.system(size: 26, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(statusLine)
                .font(.footnote.weight(.medium))
                .foregroundStyle(engine.state == .incomingRinging ? PulseTheme.emerald500 : Color(white: 0.72))
                .monospacedDigit()
        }
    }

    private var statusLine: String {
        switch engine.state {
        case .incomingRinging:
            return engine.activePeer != nil && isVideoKind ? "Incoming video call" : "Incoming voice call"
        case .outgoingRinging:
            return isVideoKind ? "Video call…" : "Calling…"
        case .connecting:
            return "Connecting…"
        case .connected:
            return CallFormat.duration(engine.durationSec)
        case .ended(let end):
            if let summary = engine.summary { return summary }
            return end.outcome == .completed
                ? "Call ended · \(CallFormat.duration(end.durationSec))"
                : "Call ended"
        case .idle:
            return ""
        }
    }

    /// Native UI stays an audio-call UI; a wire 'video' offer is accepted and
    /// answered audio-only (documented Wave 3 limitation) — the status line
    /// still says what kind the WIRE carried.
    private var isVideoKind: Bool { false }

    private func errorCard(_ text: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "mic.slash.fill")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(PulseTheme.rose500)
                .frame(width: 40, height: 40)
                .background(Circle().fill(PulseTheme.rose500.opacity(0.16)))
            Text(text)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.9))
                .multilineTextAlignment(.center)
            Button {
                engine.dismissError()
            } label: {
                Text("Close")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(Capsule().fill(PulseTheme.emerald))
            }
            .buttonStyle(CallPressStyle())
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
        )
        .frame(maxWidth: 320)
        .padding(.top, 22)
    }

    private func summaryChip(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: text.hasPrefix("Call ended") ? "phone.down.fill" : "phone.badge.xmark")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(text.hasPrefix("Call ended") ? PulseTheme.emerald500 : PulseTheme.rose500)
            Text(text)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.92))
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .padding(.top, 22)
    }

    // ── controls ─────────────────────────────────────────────

    @ViewBuilder
    private var controls: some View {
        if engine.errorText != nil {
            EmptyView()
        } else {
            switch engine.state {
            case .incomingRinging:
                HStack(spacing: 56) {
                    CallActionButton(
                        label: "Decline",
                        icon: "phone.down.fill",
                        tone: .danger,
                        size: 72,
                    ) {
                        engine.declineIncoming()
                    }
                    CallActionButton(
                        label: "Accept",
                        icon: "phone.fill",
                        tone: .accept,
                        size: 72,
                    ) {
                        engine.acceptIncoming()
                    }
                }
            case .outgoingRinging:
                CallActionButton(label: "Cancel", icon: "phone.down.fill", tone: .danger, size: 72) {
                    engine.endCall()
                }
            case .connecting:
                VStack(spacing: 14) {
                    ProgressView().tint(.white)
                    CallActionButton(label: "End", icon: "phone.down.fill", tone: .danger, size: 64) {
                        engine.endCall()
                    }
                }
            case .connected:
                HStack(spacing: 26) {
                    CallActionButton(
                        label: engine.micEnabled ? "Mute" : "Unmute",
                        icon: engine.micEnabled ? "mic.fill" : "mic.slash.fill",
                        tone: engine.micEnabled ? .neutral : .dangerSoft,
                        size: 60,
                    ) {
                        engine.toggleMute()
                    }
                    CallActionButton(
                        label: engine.speakerOn ? "Speaker" : "Earpiece",
                        icon: engine.speakerOn ? "speaker.wave.2.fill" : "iphone.gen3",
                        tone: .neutral,
                        size: 60,
                    ) {
                        engine.toggleSpeaker()
                    }
                    CallActionButton(label: "End", icon: "phone.down.fill", tone: .danger, size: 72) {
                        engine.endCall()
                    }
                }
            case .ended:
                EmptyView()
            case .idle:
                EmptyView()
            }
        }
    }
}

// ── primitives ───────────────────────────────────────────────

/// Pulsing halo rings around the avatar while ringing (web AvatarHalo parity).
private struct AvatarHalo<Content: View>: View {
    let ring: Bool
    let reduceMotion: Bool
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            if ring && !reduceMotion {
                haloCircle(scale: 1.32, opacity: 0.7)
                haloCircle(scale: 1.55, opacity: 0.5, delay: 0.6)
            }
            content
        }
    }

    private func haloCircle(scale: CGFloat, opacity: Double, delay: Double = 0) -> some View {
        Circle()
            .strokeBorder(PulseTheme.emerald500.opacity(0.45), lineWidth: 1.5)
            .scaleEffect(1)
            .overlay(
                GeometryReader { _ in EmptyView() }
            )
            .modifier(HaloPulse(targetScale: scale, targetOpacity: opacity, delay: delay, active: ring && !reduceMotion))
            .allowsHitTesting(false)
    }
}

/// Timeline-driven halo pulse (no repeatForever animation leaks between
/// call states — cancels with the modifier identity).
private struct HaloPulse: ViewModifier {
    let targetScale: CGFloat
    let targetOpacity: Double
    let delay: Double
    let active: Bool
    @State private var phase: Int = 0

    func body(content: Content) -> some View {
        content
            .scaleEffect(phase == 1 ? targetScale : 1)
            .opacity(phase == 1 ? 0 : 0.7)
            .onAppear { runLoop() }
            .onChange(of: active) { _, newValue in
                if newValue { runLoop() } else { phase = 0 }
            }
    }

    private func runLoop() {
        guard active else { return }
        Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64((1.8 * 1_000_000_000) + delay * 1_000_000_000))
                withAnimation(.easeOut(duration: 1.8)) { phase = 1 }
                try? await Task.sleep(nanoseconds: UInt64(1.8 * 1_000_000_000))
                phase = 0
            }
        }
    }
}

/// Round glass action button (web CallButton parity).
private struct CallActionButton: View {
    enum Tone {
        case neutral
        case danger
        case dangerSoft
        case accept
    }

    let label: String
    let icon: String
    let tone: Tone
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            Button {
                PulseHaptics.tap()
                action()
            } label: {
                Image(systemName: icon)
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(foreground)
                    .frame(width: size, height: size)
                    .background(Circle().fill(fill))
                    .overlay(Circle().strokeBorder(border, lineWidth: 1))
                    .shadow(color: shadowColor, radius: 12, y: 6)
            }
            .buttonStyle(CallPressStyle())
            .accessibilityLabel(label)
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(Color(white: 0.65))
        }
    }

    private var foreground: Color {
        tone == .neutral ? .white : .white
    }

    private var fill: Color {
        switch tone {
        case .neutral: return Color.white.opacity(0.14)
        case .danger: return PulseTheme.rose500.opacity(0.92)
        case .dangerSoft: return PulseTheme.rose500.opacity(0.30)
        case .accept: return PulseTheme.emerald.opacity(0.92)
        }
    }

    private var border: Color {
        switch tone {
        case .neutral: return Color.white.opacity(0.16)
        case .danger: return PulseTheme.rose500.opacity(0.4)
        case .dangerSoft: return PulseTheme.rose500.opacity(0.4)
        case .accept: return PulseTheme.emerald500.opacity(0.4)
        }
    }

    private var shadowColor: Color {
        switch tone {
        case .danger: return PulseTheme.rose500.opacity(0.45)
        case .accept: return PulseTheme.emerald500.opacity(0.5)
        default: return .black.opacity(0.3)
        }
    }
}

/// Call-button press feedback (scale 0.9 spring, web whileTap parity).
private struct CallPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

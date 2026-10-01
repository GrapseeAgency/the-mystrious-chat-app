import Combine
import SwiftUI
import WebRTC

// Pulse - GROUP call surfaces (3-d; web group-call-overlay.tsx parity).
//
//   GroupCallOverlayHostView - RootView-level full-screen host (mounts the
//                              overlay whenever the engine is not idle).
//   GroupCallView            - participant grid (LazyVGrid), self tile +
//                              remote tiles (video where kind=video),
//                              header (title · status · member count),
//                              mic/camera/leave controls, honest mic-denied
//                              error card, ended card ('You left · M:SS').
//   GroupCallBannerHostView  - the shell-level incoming ring banner +
//                              the 'Ongoing group call · N in call - Join'
//                              banner (web mounts both at SHELL level,
//                              main-shell.tsx :555). Suppressed while
//                              CallKit owns the incoming presentation.
//
// Rendering binds PulseGroupCallEngine published state only - no call
// logic lives here (single source of truth: the engine + the policy).
// Styling follows the 1:1 CallView conventions (glass cards, emerald
// wash, CallActionButton footer).

/// Hosted by RootView (next to the 1:1 CallOverlayHostView, above the tab
/// chrome) - mounts the group overlay whenever the engine is not idle.
struct GroupCallOverlayHostView: View {
    @ObservedObject var engine: PulseGroupCallEngine
    let viewer: PulseViewer?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if engine.uiState != .idle {
                GroupCallView(engine: engine, viewer: viewer)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    .zIndex(11)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.24), value: engine.uiState != .idle)
    }
}

struct GroupCallView: View {
    @ObservedObject var engine: PulseGroupCallEngine
    let viewer: PulseViewer?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
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

            VStack(spacing: 0) {
                header
                if let error = engine.errorText {
                    errorCard(error)
                } else if engine.uiState == .ended, let summary = engine.summary {
                    endedCard(summary)
                } else {
                    participantGrid
                }
                if engine.uiState != .ended, engine.errorText == nil {
                    controls
                        .padding(.bottom, 26)
                } else {
                    Color.clear.frame(height: 26)
                }
            }
        }
    }

    // header (title · status · member count)

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(engine.displayTitle)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(statusLine)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color(white: 0.72))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            HStack(spacing: 5) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 11, weight: .semibold))
                Text("\(engine.members.count)")
                    .font(.system(size: 12, weight: .semibold))
                    .monospacedDigit()
            }
            .foregroundStyle(.white.opacity(0.85))
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(Capsule().fill(Color.white.opacity(0.10)))
            .accessibilityLabel("Members in call")
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 12)
    }

    private var statusLine: String {
        switch engine.uiState {
        case .joining:
            return "Connecting…"
        case .active:
            return "\(engine.kind == .video ? "Video" : "Voice") call · \(CallFormat.duration(engine.durationSec))"
        case .ended:
            return engine.summary ?? ""
        case .idle:
            return ""
        }
    }

    // participant grid (web GroupCallOverlay parity)

    private var gridColumns: [GridItem] {
        // Web gridCols: 1 tile → single column; else two (mobile width).
        engine.members.count <= 1
            ? [GridItem(.flexible(), spacing: 12)]
            : [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    }

    private var remoteMembers: [PulseGroupCallMember] {
        engine.members.filter { $0.id != viewer?.id }
    }

    private var participantGrid: some View {
        ScrollView {
            LazyVGrid(columns: gridColumns, spacing: 12) {
                selfTile
                ForEach(remoteMembers, id: \.id) { member in
                    remoteTile(member)
                }
                if engine.uiState == .joining {
                    joiningTile
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }

    private func tileFrame<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
            )
    }

    private var selfTile: some View {
        tileFrame {
            ZStack {
                Color.white.opacity(0.05)
                if engine.kind == .video, engine.cameraEnabled, let track = engine.localVideoTrack {
                    VideoTrackView(track: track, mirrored: true)
                } else {
                    PulseAvatar(
                        name: viewer?.name ?? "You",
                        color: PulseTheme.color(named: viewer?.color),
                        photoURL: PulseTheme.photoURL(viewer?.avatar),
                        online: true,
                        size: 56,
                    )
                }
                tileCaption(isMuted: !engine.micEnabled) {
                    Text("You")
                }
            }
        }
        .accessibilityLabel("Your tile")
    }

    private func remoteTile(_ member: PulseGroupCallMember) -> some View {
        let track = engine.remoteTracks[member.id]
        let connected = engine.peerStates[member.id] == .connected
        return tileFrame {
            ZStack {
                Color.white.opacity(0.05)
                if engine.kind == .video, let track {
                    VideoTrackView(track: track, mirrored: false)
                } else {
                    PulseAvatar(
                        name: member.name,
                        color: PulseTheme.color(named: member.color),
                        photoURL: PulseTheme.photoURL(member.avatar),
                        online: false,
                        size: 56,
                    )
                }
                tileCaption(isMuted: nil) {
                    HStack(spacing: 5) {
                        if !connected {
                            Circle()
                                .fill(PulseTheme.amber)
                                .frame(width: 6, height: 6)
                        }
                        Text(member.name)
                            .lineLimit(1)
                    }
                }
            }
        }
        .accessibilityLabel("\(member.name)'s tile")
    }

    private var joiningTile: some View {
        tileFrame {
            ZStack {
                Color.white.opacity(0.05)
                VStack(spacing: 10) {
                    ProgressView().tint(.white)
                    Text("Connecting…")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
        }
    }

    /// The bottom gradient caption strip (name + muted glyph), web tile
    /// footer parity (`bg-gradient-to-t from-black/70`).
    private func tileCaption<Label: View>(isMuted: Bool?, @ViewBuilder label: () -> Label) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            HStack(spacing: 6) {
                label()
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if isMuted == true {
                    Image(systemName: "mic.slash.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(PulseTheme.rose500)
                        .accessibilityLabel("Mic muted")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(
                LinearGradient(
                    colors: [Color.black.opacity(0.72), .clear],
                    startPoint: .bottom,
                    endPoint: .top,
                )
            )
        }
        .allowsHitTesting(false)
    }

    // error / ended cards (CallView glass card parity)

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
        .padding(.horizontal, 24)
        .padding(.top, 22)
    }

    private func endedCard(_ text: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "phone.down.fill")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(PulseTheme.emerald500)
                .frame(width: 40, height: 40)
                .background(Circle().fill(PulseTheme.emerald500.opacity(0.16)))
            Text(text)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.92))
                .multilineTextAlignment(.center)
            Button {
                engine.dismissSummary()
            } label: {
                Text("Done")
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
        .padding(.horizontal, 24)
        .padding(.top, 22)
    }

    // controls (mic · leave · camera)

    private var controls: some View {
        HStack(spacing: 26) {
            CallActionButton(
                label: engine.micEnabled ? "Mute" : "Unmute",
                icon: engine.micEnabled ? "mic.fill" : "mic.slash.fill",
                tone: engine.micEnabled ? .neutral : .dangerSoft,
                size: 60,
            ) {
                engine.toggleMic()
            }
            CallActionButton(label: "Leave", icon: "phone.down.fill", tone: .danger, size: 72) {
                engine.leaveCall()
            }
            if engine.kind == .video {
                CallActionButton(
                    label: engine.cameraEnabled ? "Camera" : "Camera off",
                    icon: engine.cameraEnabled ? "video.fill" : "video.slash.fill",
                    tone: engine.cameraEnabled ? .neutral : .dangerSoft,
                    size: 60,
                ) {
                    engine.toggleCamera()
                }
            }
        }
    }
}

// shell-level banners (web GroupCallRingBanner parity)

/// RootView-level banner host: the live incoming ring AND the
/// 'Ongoing group call' discovery banner (both mount at shell level on
/// web). Suppressed while CallKit presents the ring (no double-ring).
@MainActor
struct GroupCallBannerHostView: View {
    @ObservedObject var engine: PulseGroupCallEngine
    @ObservedObject private var callKit = PulseCallKitCoordinator.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            if engine.uiState == .idle {
                if engine.ring != nil, !callKit.ownsIncomingPresentation {
                    ringBanner
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                } else if engine.ongoingElsewhere {
                    ongoingBanner
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                }
            }
            Spacer(minLength: 0)
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: bannerKey)
        .padding(.horizontal, 12)
        .padding(.top, 2)
    }

    private var bannerKey: String {
        "\(engine.ring != nil && !callKit.ownsIncomingPresentation)-\(engine.ongoingElsewhere)-\(engine.uiState == .idle)"
    }

    private var ringBanner: some View {
        Group {
            if let ring = engine.ring {
                HStack(spacing: 12) {
                    bannerIcon("phone.fill", pulse: true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ring.title.isEmpty ? "Group call from \(ring.caller.name)" : "\(ring.title) · group call")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(PulseTheme.emeraldDeep)
                            .lineLimit(1)
                        Text("\(ring.kind == .video ? "Video" : "Voice") call · started by \(ring.caller.name)")
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(PulseTheme.emeraldDeep.opacity(0.75))
                            .lineLimit(1)
                    }
                    Spacer(minLength: 6)
                    bannerButton("Ignore", prominent: false) {
                        engine.dismissRing()
                    }
                    bannerButton("Join", prominent: true) {
                        engine.joinCall()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(bannerBackground)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Group call from \(ring.caller.name)")
            }
        }
    }

    private var ongoingBanner: some View {
        HStack(spacing: 12) {
            bannerIcon("person.2.fill", pulse: false)
            VStack(alignment: .leading, spacing: 2) {
                Text("Ongoing group call · \(engine.ongoingMembers.count) in call")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.emeraldDeep)
                    .lineLimit(1)
                Text(ongoingNames)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(PulseTheme.emeraldDeep.opacity(0.75))
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            bannerButton("Ignore", prominent: false) {
                engine.ignoreOngoing()
            }
            bannerButton("Join", prominent: true) {
                engine.joinOngoing()
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(bannerBackground)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Ongoing group call, \(engine.ongoingMembers.count) in call")
    }

    /// Web banner names: the first three members' first names joined.
    private var ongoingNames: String {
        engine.ongoingMembers
            .prefix(3)
            .map { $0.name.split(separator: " ").first.map(String.init) ?? $0.name }
            .joined(separator: ", ")
    }

    private func bannerIcon(_ glyph: String, pulse: Bool) -> some View {
        ZStack {
            if pulse && !reduceMotion {
                Circle()
                    .fill(PulseTheme.emerald.opacity(0.20))
                    .scaleEffect(1.0)
                    .modifier(BannerPulse())
            }
            Image(systemName: glyph)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(PulseTheme.emeraldDeep)
        }
        .frame(width: 40, height: 40)
        .background(Circle().fill(PulseTheme.emerald.opacity(0.20)))
    }

    private func bannerButton(_ label: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            action()
        } label: {
            Text(label)
                .font(.system(size: 12, weight: prominent ? .bold : .medium))
                .foregroundStyle(prominent ? .white : PulseTheme.emeraldDeep.opacity(0.7))
                .padding(.horizontal, prominent ? 14 : 10)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(prominent ? PulseTheme.emerald : PulseTheme.emerald.opacity(0.10))
                )
        }
        .buttonStyle(.plain)
    }

    private var bannerBackground: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(PulseTheme.emerald.opacity(0.10))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(PulseTheme.emerald.opacity(0.30), lineWidth: 1)
            )
    }
}

/// Ring-banner pulse (web animate-ping parity; reduced-motion aware).
/// `.task` cancels the loop with the modifier identity - no animation
/// leaks between banner instances.
private struct BannerPulse: ViewModifier {
    @State private var phase = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(phase ? 1.55 : 1.0)
            .opacity(phase ? 0 : 0.8)
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_600_000_000)
                    withAnimation(.easeOut(duration: 1.4)) { phase.toggle() }
                }
            }
    }
}

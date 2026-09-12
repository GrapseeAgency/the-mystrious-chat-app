import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f voice room surface (walkie-talkie PTT).
// Native rebuild of web voice-room-sheet.tsx (R21-b + R48):
// roster tiles with speaking glow, hold ≥260 ms = talk while held,
// tap <260 ms = latch (tap again stops), mute + banner, captions
// toggle + strip, honest status/error states, Join/Leave, and the
// footer contract "never recorded or stored" (spec §1.1).
// ─────────────────────────────────────────────────────────────

struct VoiceRoomView: View {
    @ObservedObject var model: VoiceRoomSessionModel
    let conversationId: String

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                statusStrip
                if let issue = model.micIssue {
                    micIssueStrip(issue)
                }
                if model.voice.status == .joined {
                    rosterGrid
                    captionsStrip
                }
                controls
                footer
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
    }

    // ── status + honest states ───────────────────────────

    private var statusStrip: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusTint)
                .frame(width: 8, height: 8)
            Text(model.voice.statusLine)
                .font(.footnote.weight(.medium))
                .foregroundStyle(PulseTheme.textSecondary)
            Spacer()
            if model.voice.micMuted {
                Label("Muted", systemImage: "mic.slash.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.amber)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(PulseTheme.glassFill))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Voice room status: \(model.voice.statusLine)")
    }

    private var statusTint: Color {
        switch model.voice.status {
        case .joined: return model.voice.connected ? PulseTheme.emerald500 : PulseTheme.amber
        case .joining, .idle: return PulseTheme.zinc(400)
        case .error: return PulseTheme.rose500
        }
    }

    private func micIssueStrip(_ issue: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(PulseTheme.amber)
            Text(issue)
                .font(.footnote)
                .foregroundStyle(PulseTheme.textPrimary)
            Spacer()
            Button("Try again") {
                PulseHaptics.tap()
                Task { await model.retryMicAccess() }
            }
            .font(.footnote.weight(.semibold))
            .accessibilityLabel("Retry microphone access")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(PulseTheme.chipFill))
        .accessibilityElement(children: .combine)
    }

    // ── roster tiles (VR-2) ──────────────────────────────

    private let columns = [GridItem(.adaptive(minimum: 84), spacing: 12)]

    private var rosterGrid: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("In the room")
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(PulseTheme.textTertiary)
            if model.voice.roster.isEmpty {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Syncing roster…")
                        .font(.footnote)
                        .foregroundStyle(PulseTheme.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 22)
            } else {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(model.voice.roster) { peer in
                        PeerTile(
                            name: peer.name ?? "Someone",
                            colorName: peer.color,
                            speaking: model.voice.speakingIds.contains(peer.id),
                        )
                    }
                }
            }
        }
    }

    // ── captions (VR-7) ──────────────────────────────────

    private var captionsStrip: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                PulseHaptics.tap()
                model.setCaptions(!model.captionsEnabled)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: model.captionsEnabled ? "captions.bubble.fill" : "captions.bubble")
                    Text("Live captions")
                        .font(.footnote.weight(.semibold))
                    Spacer()
                    TogglePill(on: model.captionsEnabled)
                }
                .foregroundStyle(model.captionsEnabled ? PulseTheme.accent : PulseTheme.textSecondary)
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Live captions")
            .accessibilityValue(model.captionsEnabled ? "On" : "Off")

            if model.captionsEnabled, !model.captions.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(model.captions) { caption in
                        Text("\(caption.name): \(caption.text)")
                            .font(.footnote)
                            .foregroundStyle(PulseTheme.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(RoundedRectangle(cornerRadius: 10).fill(PulseTheme.chipFill))
                    }
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(PulseTheme.glassFill))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    // ── controls (join / PTT / leave) ────────────────────

    private var controls: some View {
        VStack(spacing: 14) {
            switch model.voice.status {
            case .joined:
                PTTButton(model: model)
                Button {
                    PulseHaptics.warning()
                    model.leaveVoice()
                } label: {
                    Label("Leave voice room", systemImage: "phone.down.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PulseTheme.rose500)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(PulseTheme.chipFill))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Leave voice room")
            case .error:
                VStack(spacing: 10) {
                    Text(model.voice.errorText ?? "Voice room unavailable")
                        .font(.footnote)
                        .foregroundStyle(PulseTheme.rose500)
                        .multilineTextAlignment(.center)
                    Button {
                        PulseHaptics.tap()
                        model.joinVoice(conversationId: conversationId)
                    } label: {
                        Text("Try again")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Capsule().fill(PulseTheme.rose500))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Try joining the voice room again")
                }
            case .joining:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Connecting…")
                        .font(.footnote)
                        .foregroundStyle(PulseTheme.textTertiary)
                }
                .padding(.vertical, 18)
            case .idle:
                Button {
                    PulseHaptics.tap()
                    model.joinVoice(conversationId: conversationId)
                } label: {
                    Label("Join voice room", systemImage: "waveform")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(Capsule().fill(PulseTheme.brandGradient))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Join voice room")
            }
        }
    }

    private var footer: some View {
        Text("Walkie-talkie is live audio — never recorded or stored.")
            .font(.caption2)
            .foregroundStyle(PulseTheme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 8)
    }
}

// ── shared room primitives ───────────────────────────────────

/// One roster tile with the speaking glow (voice:ptt drives it).
struct PeerTile: View {
    let name: String
    let colorName: String?
    let speaking: Bool

    var body: some View {
        VStack(spacing: 6) {
            RowAvatar(name: name, colorName: colorName, photoPath: nil, size: 56)
                .overlay(
                    Circle()
                        .strokeBorder(PulseTheme.emerald400.opacity(speaking ? 0.9 : 0), lineWidth: 2.5)
                        .shadow(color: PulseTheme.emerald500.opacity(speaking ? 0.55 : 0), radius: 9)
                        .animation(.pulse(.pulseBouncy, reduceMotion: false), value: speaking),
                )
            Text(name)
                .font(.caption.weight(.medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name)\(speaking ? ", speaking" : "")")
    }
}

/// Small on/off pill used by toggles across the room surfaces.
struct TogglePill: View {
    let on: Bool

    var body: some View {
        Text(on ? "On" : "Off")
            .font(.caption2.weight(.bold))
            .foregroundStyle(on ? .white : PulseTheme.textTertiary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(on ? PulseTheme.emerald500 : PulseTheme.chipFill))
    }
}

/// THE push-to-talk control (VR-3): press ≥260 ms = talk while held;
/// tap <260 ms = latch (tap again stops). Accessibility action = pure
/// toggle. Disabled honestly when muted / not joined / disconnected.
struct PTTButton: View {
    @ObservedObject var model: VoiceRoomSessionModel

    /// Web parity hold threshold (voice-room-sheet.tsx).
    private let holdThresholdMs: UInt64 = 260

    @State private var pressStart: Date?
    @State private var holdTask: Task<Void, Never>?

    private var transmitting: Bool {
        model.voice.transmitting
    }

    var body: some View {
        VStack(spacing: 8) {
            Circle()
                .fill(
                    transmitting
                        ? AnyShapeStyle(PulseTheme.brandGradient)
                        : AnyShapeStyle(PulseTheme.chipFill),
                )
                .frame(width: 108, height: 108)
                .overlay(
                    Circle()
                        .strokeBorder(
                            PulseTheme.emerald400.opacity(transmitting ? 0.9 : 0),
                            lineWidth: 3,
                        )
                        .shadow(color: PulseTheme.emerald500.opacity(transmitting ? 0.6 : 0), radius: 14),
                )
                .overlay(
                    Image(systemName: "mic.fill")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundStyle(transmitting ? .white : PulseTheme.textSecondary),
                )
                .opacity(model.canTransmit ? 1 : 0.45)
                .contentShape(Circle())
                .gesture(pushToTalkGesture)
                .animation(.pulse(.pulseBouncy, reduceMotion: false), value: transmitting)
                .accessibilityLabel(transmitting ? "Stop talking" : "Push to talk")
                .accessibilityHint("Hold to talk, tap to latch")
                .accessibilityAddTraits(model.canTransmit ? [] : .isButton)
                .accessibilityAction(named: Text("Toggle talk")) {
                    model.setPtt(on: !transmitting)
                }

            Text(transmitting ? "Talking…" : model.voice.micMuted ? "Muted" : "Hold to talk")
                .font(.caption.weight(.semibold))
                .foregroundStyle(transmitting ? PulseTheme.accent : PulseTheme.textTertiary)
        }
        .onDisappear {
            holdTask?.cancel()
            pressStart = nil
            // A surface teardown must never leave a stuck transmission.
            if transmitting {
                model.setPtt(on: false)
            }
        }
    }

    private var pushToTalkGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard pressStart == nil else { return }
                pressStart = Date()
                PulseHaptics.tap()
                holdTask = Task { [weak model] in
                    try? await Task.sleep(nanoseconds: holdThresholdMs * 1_000_000)
                    guard !Task.isCancelled else { return }
                    model?.setPtt(on: true) // hold mode — talk while pressed
                    PulseHaptics.success()
                }
            }
            .onEnded { _ in
                let start = pressStart
                pressStart = nil
                holdTask?.cancel()
                holdTask = nil
                guard let start else { return }
                let heldMs = Date().timeIntervalSince(start) * 1000
                if heldMs >= Double(holdThresholdMs) {
                    model.setPtt(on: false) // hold ends on release
                } else {
                    model.setPtt(on: !transmitting) // tap = latch toggle
                }
            }
    }
}

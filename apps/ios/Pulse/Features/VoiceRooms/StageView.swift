import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f stage surface (Clubhouse hierarchy).
// Native rebuild of web stage-room-sheet.tsx (R24-c): join always as
// listener (first joiner of a fresh room = host server-side), host
// card + speaker tiles + FIFO hand queue + listeners row, raise-hand
// toggle, host approve/decline/mute, two-tap End (2600 ms reset),
// Claim host when the seat is empty, and DEFECT FIX #3 — every joined
// role holds a voice seat, so the AUDIENCE HEARS (spec §1.2).
// ─────────────────────────────────────────────────────────────

struct StageView: View {
    @ObservedObject var model: VoiceRoomSessionModel
    let conversationId: String

    @State private var endConfirm = StageEndConfirm()
    @State private var endResetTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                if !model.stage.joined {
                    joinCard
                } else if model.stage.syncing {
                    syncingCard
                } else {
                    if let host = model.stage.state?.host {
                        hostCard(host)
                    } else {
                        emptyHostCard
                    }
                    speakerGrid
                    handQueue
                    listenersRow
                    controls
                }
                footer
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .onAppear {
            if !model.stage.joined {
                model.joinStage(conversationId: conversationId)
            }
        }
        .onDisappear {
            // ST-7 — close = leave (reopen lands as a plain listener).
            if model.stage.joined {
                model.leaveStage()
            }
            endResetTask?.cancel()
        }
    }

    // ── header ───────────────────────────────────────────

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "crown.fill")
                .foregroundStyle(PulseTheme.amber500)
            Text("Stage")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Spacer()
            if model.stage.joined {
                HStack(spacing: 4) {
                    Image(systemName: "headphones")
                        .font(.caption2)
                    Text("\(model.stage.listenerTotal)")
                        .font(.caption.weight(.bold))
                }
                .foregroundStyle(PulseTheme.textSecondary)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(Capsule().fill(PulseTheme.chipFill))
                .accessibilityLabel("\(model.stage.listenerTotal) listeners")
            }
        }
    }

    // ── entry states ─────────────────────────────────────

    private var joinCard: some View {
        Button {
            PulseHaptics.tap()
            model.joinStage(conversationId: conversationId)
        } label: {
            Label("Join the stage", systemImage: "crown")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(Capsule().fill(PulseTheme.brandGradient))
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Join the stage")
    }

    private var syncingCard: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text("Syncing stage…")
                .font(.footnote)
                .foregroundStyle(PulseTheme.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(PulseTheme.glassFill))
    }

    // ── host + speakers (ST-2) ───────────────────────────

    private func hostCard(_ host: WireStagePerson) -> some View {
        HStack(spacing: 12) {
            RowAvatar(name: host.name ?? "Host", colorName: host.color, photoPath: nil, size: 52)
                .overlay(
                    Circle().strokeBorder(PulseTheme.amber500.opacity(0.9), lineWidth: 2),
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(host.name ?? "Host")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Label(model.stage.isHost ? "You host this stage" : "Host", systemImage: "crown.fill")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(PulseTheme.amber600)
            }
            Spacer()
            if model.voice.speakingIds.contains(host.id) {
                speakingDot
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(PulseTheme.amber500.opacity(0.14)),
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(PulseTheme.amber500.opacity(0.55), lineWidth: 1),
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Host \(host.name ?? "")")
    }

    private var emptyHostCard: some View {
        VStack(spacing: 8) {
            Image(systemName: "crown")
                .font(.title3)
                .foregroundStyle(PulseTheme.textTertiary)
            Text("The host seat is empty — no auto-promotion.")
                .font(.footnote)
                .foregroundStyle(PulseTheme.textSecondary)
            Button {
                PulseHaptics.tap()
                model.claimHost()
            } label: {
                Text("Claim host")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 9)
                    .background(Capsule().fill(PulseTheme.amber600))
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Claim host seat")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(PulseTheme.glassFill))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    private var speakingDot: some View {
        Circle()
            .fill(PulseTheme.emerald500)
            .frame(width: 9, height: 9)
            .shadow(color: PulseTheme.emerald500.opacity(0.6), radius: 5)
            .accessibilityLabel("Speaking")
    }

    private var speakerGrid: some View {
        let speakers = model.stage.speakerTiles
        return VStack(alignment: .leading, spacing: 8) {
            Text("Speakers")
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(PulseTheme.textTertiary)
            if speakers.isEmpty {
                Text("No speakers yet")
                    .font(.footnote)
                    .foregroundStyle(PulseTheme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 14)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 12)], spacing: 12) {
                    ForEach(speakers) { person in
                        PeerTile(
                            name: person.name ?? "Someone",
                            colorName: person.color,
                            speaking: model.voice.speakingIds.contains(person.id),
                        )
                    }
                }
            }
        }
    }

    // ── hand queue (ST-3) ────────────────────────────────

    private var handQueue: some View {
        let hands = model.stage.handQueue
        return VStack(alignment: .leading, spacing: 8) {
            if !hands.isEmpty {
                Text("Raised hands")
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(0.8)
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            ForEach(Array(hands.enumerated()), id: \.element.id) { index, person in
                HStack(spacing: 10) {
                    Text("\(index + 1)")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .frame(width: 18)
                    RowAvatar(name: person.name ?? "Someone", colorName: person.color, photoPath: nil, size: 36)
                    Text(person.name ?? "Someone")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(PulseTheme.textPrimary)
                    Spacer()
                    if model.stage.isHost {
                        Button {
                            PulseHaptics.success()
                            model.approveHand(userId: person.id)
                        } label: {
                            Label("Approve", systemImage: "checkmark")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(Capsule().fill(PulseTheme.emerald500))
                        }
                        .buttonStyle(PulseButtonStyle())
                        .accessibilityLabel("Approve \(person.name ?? "this person") as speaker")
                        Button {
                            PulseHaptics.warning()
                            model.muteMember(userId: person.id) // decline = dismiss the hand
                        } label: {
                            Label("Decline", systemImage: "xmark")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PulseTheme.textSecondary)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(Capsule().fill(PulseTheme.chipFill))
                        }
                        .buttonStyle(PulseButtonStyle())
                        .accessibilityLabel("Decline \(person.name ?? "this person")")
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.chipFill))
            }
        }
    }

    // ── listeners row (ST-2) ─────────────────────────────

    private var listenersRow: some View {
        let listeners = model.stage.listenerRow
        return Group {
            if !listeners.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Listeners")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(0.8)
                        .foregroundStyle(PulseTheme.textTertiary)
                    HStack(spacing: 8) {
                        ForEach(listeners.prefix(4)) { person in
                            RowAvatar(name: person.name ?? "Someone", colorName: person.color, photoPath: nil, size: 32)
                                .accessibilityLabel("Listener \(person.name ?? "")")
                        }
                        if listeners.count > 4 {
                            Text("+\(listeners.count - 4) more")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PulseTheme.textTertiary)
                        }
                        Spacer()
                    }
                }
            }
        }
    }

    // ── controls ─────────────────────────────────────────

    private var controls: some View {
        VStack(spacing: 12) {
            if model.stage.canSpeak {
                PTTButton(model: model)
            } else {
                Button {
                    PulseHaptics.tap()
                    model.setHandRaised(!model.stage.handRaised)
                } label: {
                    Label(
                        model.stage.handRaised ? "Lower hand" : "Raise hand",
                        systemImage: model.stage.handRaised ? "hand.raised.fill" : "hand.raised",
                    )
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(model.stage.handRaised ? PulseTheme.amber600 : PulseTheme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(PulseTheme.chipFill))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel(model.stage.handRaised ? "Lower my hand" : "Raise my hand")
            }

            if model.stage.isHost {
                endStageButton
            }

            Button {
                PulseHaptics.warning()
                model.leaveStage()
            } label: {
                Label("Leave stage", systemImage: "arrow.down.right.circle")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PulseTheme.textSecondary)
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Leave stage")
        }
    }

    /// ST-5 — two-tap End with a 2600 ms reset (pure StageEndConfirm).
    private var endStageButton: some View {
        Button {
            let nowMs = Date().timeIntervalSince1970 * 1000
            if endConfirm.tap(nowMs: nowMs) {
                endResetTask?.cancel()
                PulseHaptics.warning()
                model.endStage()
            } else {
                PulseHaptics.tap()
                endResetTask?.cancel()
                endResetTask = Task {
                    try? await Task.sleep(nanoseconds: UInt64(StageEndConfirm.resetMs * 1_000_000))
                    guard !Task.isCancelled else { return }
                    endConfirm.reset()
                }
            }
        } label: {
            Label(endConfirm.armed ? "Tap again to end" : "End stage", systemImage: "xmark.octagon.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(endConfirm.armed ? .white : PulseTheme.rose500)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Capsule().fill(endConfirm.armed ? AnyShapeStyle(PulseTheme.rose500) : AnyShapeStyle(PulseTheme.chipFill)))
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("End stage")
        .accessibilityHint(endConfirm.armed ? "Tap again within 3 seconds to confirm" : "Requires two taps")
    }

    private var footer: some View {
        Text("Stage roles are claimed live — nothing is recorded or stored.")
            .font(.caption2)
            .foregroundStyle(PulseTheme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 8)
    }
}

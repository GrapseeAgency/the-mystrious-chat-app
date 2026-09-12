import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f voice rooms surface HOST + chat entry.
//
// Hosting (R2): the three room surfaces present through ONE
// fullScreenCover whose `item` is VoiceRoomSessionModel.surface —
// the session model's published surface state (open/closed + active
// kind) is the single driver, exactly like the call overlay is
// driven by engine state (RootView CallOverlayHostView) and the
// Stories covers are driven by presence state (ChatsView). Hosted at
// RootView level so membership survives navigation: closing the chat
// room, switching tabs or popping the stack can never tear the cover
// away from a live room.
//
// Closing (VR-1): the cover's close calls closeSurface() — voice
// membership SURVIVES (the "Voice · N live" pill reopens it), stage
// close = stage:leave (ST-7), space close = space:leave (SP-1); a
// transmitting PTT force-stops on close via PTTButton.onDisappear.
//
// Entry: the ChatRoomView header gains the room launcher (mic icon, tints
// active while any room of that conversation is joined — VR-1; the menu
// offers Voice room / Stage / Space, the web palette/tray parity) and the
// "Voice · N live" pill (voice joined + surface closed → tap to reopen).
// ─────────────────────────────────────────────────────────────

/// fullScreenCover(item:) needs an Identifiable item — a local wrapper
/// carries the surface kind plus a stable id (kept OUT of the session
/// model so the @MainActor class gains no retroactive conformances).
private struct RoomsSurfaceItem: Identifiable {
    let id: Int
    let kind: VoiceRoomSessionModel.Surface

    init(kind: VoiceRoomSessionModel.Surface) {
        self.kind = kind
        switch kind {
        case .voice: id = 0
        case .stage: id = 1
        case .space: id = 2
        }
    }
}

/// Mounted by RootView next to CallOverlayHostView — renders nothing,
/// presents the cover whenever the model's surface state is open.
struct VoiceRoomsSurfaceHost: View {
    @ObservedObject var model: VoiceRoomSessionModel

    var body: some View {
        // Zero-footprint presenter: the cover is driven by the binding, not
        // by hit-testing (the shell underneath must stay interactive).
        Color.clear
            .frame(width: 0, height: 0)
            .fullScreenCover(item: surfaceItem) { item in
                VoiceRoomSurface(model: model, kind: item.kind)
            }
    }

    /// The session model owns the surface state (private(set)); the binding
    /// routes system-driven dismissals through closeSurface() so closing is
    /// NEVER interpreted as leaving the voice room.
    private var surfaceItem: Binding<RoomsSurfaceItem?> {
        Binding(
            get: { model.surface.map { RoomsSurfaceItem(kind: $0) } },
            set: { newValue in
                if let kind = newValue?.kind {
                    model.open(kind)
                } else {
                    model.closeSurface()
                }
            },
        )
    }
}

/// The presented surface: house page wash + a header with the honest close
/// affordance + the kind-selected room view (voice / stage / space).
struct VoiceRoomSurface: View {
    @ObservedObject var model: VoiceRoomSessionModel
    let kind: VoiceRoomSessionModel.Surface

    private var title: String {
        switch kind {
        case .voice: return "Voice room"
        case .stage: return "Stage"
        case .space: return "Space"
        }
    }

    private var icon: String {
        switch kind {
        case .voice: return "waveform"
        case .stage: return "crown.fill"
        case .space: return "map.fill"
        }
    }

    private var subtitle: String {
        switch kind {
        case .voice: return "Closing keeps the room running."
        case .stage: return "Closing leaves the stage."
        case .space: return "Closing leaves the room."
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            content
        }
        .background(PulseTheme.pageWash.ignoresSafeArea())
    }

    @ViewBuilder
    private var content: some View {
        let conversationId = model.surfaceConversationId ?? ""
        switch kind {
        case .voice:
            VoiceRoomView(model: model, conversationId: conversationId)
        case .stage:
            StageView(model: model, conversationId: conversationId)
        case .space:
            SpaceView(model: model, conversationId: conversationId)
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PulseTheme.emerald500)
                .frame(width: 34, height: 34)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PulseTheme.emerald500.opacity(0.15)))
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            Spacer()
            Button {
                PulseHaptics.tap()
                model.closeSurface() // voice keeps running (VR-1)
            } label: {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(PulseTheme.chipFill))
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Close the room surface")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }
}

/// ChatRoomView toolbar entry — the mic button + the live pill. Own
/// @ObservedObject so the header re-renders on room state changes even
/// though the parent only observes the session.
struct VoiceRoomChatEntry: View {
    @ObservedObject var model: VoiceRoomSessionModel
    let conversationId: String

    private var inRoom: Bool {
        model.isInRoom(conversationId)
    }

    /// VR-1 — joined to THIS conversation's voice room and the surface is
    /// closed: the pill is the re-entry door (tap → reopen the surface).
    private var showPill: Bool {
        model.voice.status != .idle
            && model.voice.conversationId == conversationId
            && model.surface == nil
    }

    var body: some View {
        HStack(spacing: 10) {
            if showPill {
                Button {
                    PulseHaptics.tap()
                    model.open(.voice)
                } label: {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(PulseTheme.emerald500)
                            .frame(width: 6, height: 6)
                        Text("Voice · \(liveCount) live")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(PulseTheme.emerald600)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(PulseTheme.emerald500.opacity(0.15)))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Voice room live with \(liveCount) people — reopen")
            }

            // The room launcher — web parity for the tray/palette entries
            // (pulse:open-stage / pulse:open-space) + the header mic:
            // one item, three room kinds, tinted while in-room.
            Menu {
                Button {
                    voiceTapped()
                } label: {
                    Label("Voice room", systemImage: "waveform")
                }
                Button {
                    stageTapped()
                } label: {
                    Label("Stage", systemImage: "crown")
                }
                Button {
                    spaceTapped()
                } label: {
                    Label("Space", systemImage: "map")
                }
            } label: {
                Image(systemName: inRoom ? "waveform.circle.fill" : "waveform.circle")
                    .font(.body)
                    .foregroundStyle(inRoom ? PulseTheme.accent : PulseTheme.textSecondary)
            }
            .accessibilityLabel(inRoom ? "Rooms — you're in one" : "Open a voice room, stage or space")
        }
    }

    /// Roster count with the honest floor: you are live even while the
    /// roster is still syncing (first voice:roster hasn't landed yet).
    private var liveCount: Int {
        max(model.voice.roster.count, 1)
    }

    private func voiceTapped() {
        PulseHaptics.tap()
        if model.voice.status != .idle {
            // Already in a voice room (this or another conversation) —
            // surface it instead of silently swallowing the tap.
            model.open(.voice)
        } else {
            // joinVoice opens the surface (VR-1) and runs permission →
            // engine → optimistic join + emit.
            model.joinVoice(conversationId: conversationId)
        }
    }

    private func stageTapped() {
        PulseHaptics.tap()
        if model.stage.joined {
            model.open(.stage)
        } else {
            model.joinStage(conversationId: conversationId) // ST-1 join-as-listener
        }
    }

    private func spaceTapped() {
        PulseHaptics.tap()
        if model.space.joined {
            model.open(.space)
        } else {
            model.joinSpace(conversationId: conversationId) // SP-1 join
        }
    }
}

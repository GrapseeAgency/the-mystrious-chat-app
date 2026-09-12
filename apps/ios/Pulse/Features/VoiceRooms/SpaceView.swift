import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f space surface (spatial presence, spec §1.3).
// Native rebuild of web space-sheet.tsx (R24-c): the ~4/3.4 aspect
// map with tap-to-move AND drag movement (client 80 ms throttle +
// clamp 0..1 live in SpaceModel.localMove), the self dot with halo
// rendered at the OPTIMISTIC target and reconciled to the server
// self-position only after 300 ms of finger idle (WEB DEFECT FIX #4),
// the NEARBY chip rail (euclidean ≤ 0.18, reused straight from the
// model's proximity computation), the "N in room" pill, and honest
// connecting/connected/error states — the error state is REACHABLE
// after 6 failed reconnect attempts (WEB DEFECT FIX #5; web spun
// forever). Ephemeral presence — nothing is recorded or stored.
// ─────────────────────────────────────────────────────────────

struct SpaceView: View {
    @ObservedObject var model: VoiceRoomSessionModel
    let conversationId: String

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                statusStrip
                mapCard
                nearbyRail
                controls
                footer
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .onAppear {
            if !model.space.joined {
                model.joinSpace(conversationId: conversationId)
            }
        }
        .onDisappear {
            // SP-1 — space is presence, not membership: closing the surface
            // (or the host teardown) leaves the room. closeSurface() already
            // left it; the guard keeps this idempotent.
            if model.space.joined {
                model.leaveSpace()
            }
        }
    }

    // ── status + honest states (SP-5 / FIX #5) ───────────

    private var statusStrip: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusTint)
                .frame(width: 8, height: 8)
            Text(statusText)
                .font(.footnote.weight(.medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
            Spacer()
            participantPill
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(PulseTheme.glassFill))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Space status: \(statusText), \(model.space.players.count) in room")
    }

    private var statusText: String {
        switch model.space.status {
        case .connecting: return "Connecting to the room…"
        case .connected: return "Live — move around, get near people"
        case .error: return model.space.errorText ?? "Can't reach the room right now."
        }
    }

    private var statusTint: Color {
        switch model.space.status {
        case .connected: return PulseTheme.emerald500
        case .connecting: return PulseTheme.amber
        case .error: return PulseTheme.rose500
        }
    }

    /// SP-2 — the "N in room" pill (web parity: players.length).
    private var participantPill: some View {
        HStack(spacing: 4) {
            Image(systemName: "person.2.fill")
                .font(.caption2)
            Text("\(model.space.players.count) in room")
                .font(.caption.weight(.bold))
        }
        .foregroundStyle(model.space.status == .connected ? PulseTheme.emerald600 : PulseTheme.textSecondary)
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Capsule().fill(PulseTheme.chipFill))
        .accessibilityLabel("\(model.space.players.count) players in the space")
    }

    // ── the map (SP-2 / SP-3 / FIX #4) ───────────────────

    private var mapCard: some View {
        SpaceMap(
            players: model.space.players,
            myId: model.space.myId,
            targetX: model.space.targetX,
            targetY: model.space.targetY,
            nearbyIds: Set(model.space.nearbyPlayers.map(\.id)),
            onMove: { x, y in
                // The model owns clamp 0..1 + the 80 ms client throttle +
                // the optimistic target; the emit only fires when allowed.
                model.moveSpace(x: x, y: y)
            },
        )
    }

    // ── nearby rail (SP-4 — model proximity, ≤ 0.18) ─────

    private var nearbyRail: some View {
        let nearby = model.space.nearbyPlayers
        let others = model.space.players.filter { $0.id != model.space.myId }
        return Group {
            if others.isEmpty {
                railCopy("No one else here right now — invite people to the room.")
            } else if nearby.isEmpty {
                railCopy("\(others.count) \(others.count == 1 ? "person" : "people") in the space — move closer to gather.")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        HStack(spacing: 4) {
                            Image(systemName: "bolt.fill")
                            Text("NEARBY")
                        }
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(PulseTheme.emerald600)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(PulseTheme.emerald500.opacity(0.15)))

                        ForEach(nearby) { player in
                            HStack(spacing: 6) {
                                RowAvatar(name: player.name, colorName: player.color, photoPath: nil, size: 20)
                                Text(player.name)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(PulseTheme.textPrimary)
                                    .lineLimit(1)
                            }
                            .padding(.leading, 4)
                            .padding(.trailing, 10)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(PulseTheme.chipFill))
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("\(player.name) is nearby")
                        }
                    }
                    .padding(.vertical, 1)
                }
            }
        }
    }

    private func railCopy(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(PulseTheme.textTertiary)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 6)
    }

    // ── controls ─────────────────────────────────────────

    private var controls: some View {
        VStack(spacing: 12) {
            if model.space.status == .error {
                // FIX #5 — the honest retry: reset the seat (attempts → 0)
                // and re-join; the error state is recoverable, never a spin.
                Button {
                    PulseHaptics.tap()
                    model.leaveSpace()
                    model.joinSpace(conversationId: conversationId)
                } label: {
                    Text("Try again")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(PulseTheme.brandGradient))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Try joining the space again")
            }

            Button {
                PulseHaptics.warning()
                model.leaveSpace()
                model.closeSurface()
            } label: {
                Label("Leave the space", systemImage: "location.slash.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PulseTheme.rose500)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(PulseTheme.chipFill))
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Leave the space")
        }
    }

    private var footer: some View {
        Text("Positions are live presence — never recorded or stored.")
            .font(.caption2)
            .foregroundStyle(PulseTheme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 8)
    }
}

// ── the spatial surface ──────────────────────────────────────

/// The draggable map. Rendering truth: OTHER dots sit at their server
/// positions; the SELF dot sits at the optimistic target (instant on
/// tap/drag) and snaps to the server self-position when the model
/// reconciles (no local move in the last 300 ms — FIX #4).
private struct SpaceMap: View {
    let players: [SpaceModel.Player]
    let myId: String
    let targetX: Double
    let targetY: Double
    let nearbyIds: Set<String>
    let onMove: (Double, Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack {
                backdrop
                ForEach(players.filter { $0.id != myId }) { player in
                    playerDot(player)
                        .position(x: player.x * size.width, y: player.y * size.height)
                }
                selfDot
                    .position(x: targetX * size.width, y: targetY * size.height)
                    .animation(.pulse(.pulseBouncy, reduceMotion: false), value: targetX)
                    .animation(.pulse(.pulseBouncy, reduceMotion: false), value: targetY)
            }
            .frame(width: size.width, height: size.height)
            .contentShape(Rectangle())
            .gesture(
                // Tap-to-move AND drag share one gesture: minimumDistance 0
                // makes the first touch fire onChanged (tap), continued
                // touches keep firing (drag). The 80 ms emit throttle lives
                // in SpaceModel.localMove — the optimistic target moves
                // EVERY event regardless of the throttle.
                DragGesture(minimumDistance: 0)
                    .onChanged { value in move(value.location, in: size) }
                    .onEnded { value in move(value.location, in: size) },
            )
        }
        .aspectRatio(4 / 3.4, contentMode: .fit)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(PulseTheme.glassFill))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Space map — tap or drag to move")
        .accessibilityAdjustableAction { direction in
            // Accessibility parity for the drag: vertical steps of 0.1
            // (adjustable actions expose increment/decrement only).
            let step = 0.1
            switch direction {
            case .increment: onMove(targetX, SpaceModel.clamp01(targetY + step))
            case .decrement: onMove(targetX, SpaceModel.clamp01(targetY - step))
            @unknown default: break
            }
        }
    }

    private var backdrop: some View {
        VStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { _ in
                Rectangle()
                    .fill(PulseTheme.hairlineSoft)
                    .frame(height: 0.5)
                Spacer()
            }
            Rectangle()
                .fill(PulseTheme.hairlineSoft)
                .frame(height: 0.5)
        }
        .overlay {
            HStack(spacing: 0) {
                ForEach(0..<5, id: \.self) { _ in
                    Rectangle()
                        .fill(PulseTheme.hairlineSoft)
                        .frame(width: 0.5)
                    Spacer()
                }
                Rectangle()
                    .fill(PulseTheme.hairlineSoft)
                    .frame(width: 0.5)
            }
        }
        .allowsHitTesting(false)
    }

    /// A peer dot — nearby peers get the emerald ring (SP-4).
    private func playerDot(_ player: SpaceModel.Player) -> some View {
        let nearby = nearbyIds.contains(player.id)
        return VStack(spacing: 3) {
            RowAvatar(name: player.name, colorName: player.color, photoPath: nil, size: 30)
                .overlay(
                    Circle()
                        .strokeBorder(PulseTheme.emerald400.opacity(nearby ? 0.9 : 0), lineWidth: 2)
                        .shadow(color: PulseTheme.emerald500.opacity(nearby ? 0.5 : 0), radius: 6),
                )
            Text(player.name)
                .font(.system(size: 9, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 4)
                .padding(.vertical, 1)
                .background(Capsule().fill(PulseTheme.chipFill.opacity(0.8)))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(player.name)\(nearby ? ", nearby" : "")")
    }

    /// The self dot — halo + optimistic target position (FIX #4 rendering).
    private var selfDot: some View {
        VStack(spacing: 3) {
            RowAvatar(name: "You", colorName: "emerald", photoPath: nil, size: 34)
                .overlay(
                    // The halo — a soft double ring only the self dot wears.
                    Circle()
                        .strokeBorder(PulseTheme.emerald400.opacity(0.55), lineWidth: 2.5)
                        .background(Circle().fill(PulseTheme.emerald500.opacity(0.14)))
                        .frame(width: 52, height: 52),
                )
                .overlay(
                    Circle()
                        .strokeBorder(PulseTheme.emerald500.opacity(0.9), lineWidth: 1.5)
                        .shadow(color: PulseTheme.emerald500.opacity(0.6), radius: 8),
                )
            Text("You")
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(PulseTheme.emerald600)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(Capsule().fill(PulseTheme.chipFill.opacity(0.8)))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("You")
    }

    private func move(_ location: CGPoint, in size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        // Normalize to 0..1 (SpaceModel.localMove clamps again — the view
        // pre-clamps so a drag BEYOND the border still targets the edge).
        let x = SpaceModel.clamp01(Double(location.x / size.width))
        let y = SpaceModel.clamp01(Double(location.y / size.height))
        onMove(x, y)
    }
}

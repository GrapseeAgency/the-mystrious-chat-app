import SwiftUI

/// Wave 6 - F-CP-07/08. The DM safety-number surface (safety-sheet.tsx
/// parity): the server-computed 60 digits in a 12×5 tile grid, the verbatim
/// comparison caption, and Mark-verified / Reset actions. NO optimistic
/// lies - both actions settle against the server, then refetch the truth
/// (web onSettled invalidateQueries parity).
@MainActor
final class SafetyBadgeModel: ObservableObject {
    @Published private(set) var state: WireSafetyState?
    @Published private(set) var loading = false
    @Published private(set) var pending = false
    @Published private(set) var errorText: String?
    private weak var session: PulseSession?
    private var peerId = ""
    private var epoch: UUID?
    private var generation = UUID()
    private var readTicket = UUID()
    private var loaded = false
    private var actionTask: Task<Void, Never>?

    func configure(session: PulseSession, peerId: String) {
        guard epoch != session.sessionGeneration || self.peerId != peerId else { return }
        stop()
        self.session = session; self.peerId = peerId; epoch = session.sessionGeneration
        state = nil; errorText = nil
    }

    func stop() {
        generation = UUID(); readTicket = UUID(); epoch = nil
        actionTask?.cancel(); actionTask = nil
        pending = false; loading = false; loaded = false
    }

    func isConfigured(epoch: UUID, peer: String) -> Bool { self.epoch == epoch && peerId == peer }

    private func owns(_ epoch: UUID, _ ticket: UUID) -> Bool {
        !Task.isCancelled && self.epoch == epoch && session?.sessionGeneration == epoch && generation == ticket
    }

    func load(force: Bool = false) async {
        guard let session, let epoch, owns(epoch, generation), !pending, force || !loaded else { return }
        let api = session.api, peer = peerId, ticket = generation, read = UUID()
        readTicket = read; loading = true
        defer { if owns(epoch, ticket), readTicket == read { loading = false } }
        do {
            let fresh = try await api.safetyState(peerId: peer)
            guard owns(epoch, ticket), readTicket == read else { return }
            state = fresh; loaded = true; errorText = nil
            PulseSafetyBadgeCache.shared.mark(peer, verified: fresh.verified, epoch: epoch)
        } catch {
            guard owns(epoch, ticket), readTicket == read else { return }
            loaded = false; errorText = ChatsViewModel.describe(error)
        }
    }

    func verify() { mutate(true) }
    func reset() { mutate(false) }

    private func mutate(_ desired: Bool) {
        guard let session, let epoch, owns(epoch, generation), !pending, !loading, state != nil else { return }
        let api = session.api, peer = peerId, ticket = generation
        pending = true; readTicket = UUID(); errorText = nil
        actionTask = Task { @MainActor in
            defer { if owns(epoch, ticket) { pending = false; actionTask = nil } }
            do {
                guard owns(epoch, ticket) else { return }
                if desired { _ = try await api.verifySafety(peerId: peer) }
                else { try await api.resetSafety(peerId: peer) }
                guard owns(epoch, ticket) else { return }
                let fresh = try await api.safetyState(peerId: peer)
                guard owns(epoch, ticket) else { return }
                state = fresh; loaded = true
                PulseSafetyBadgeCache.shared.mark(peer, verified: fresh.verified, epoch: epoch)
                guard fresh.verified == desired else {
                    errorText = "Comparison state changed. Review it before retrying."
                    return
                }
                PulseHaptics.success()
                session.toasts.show(desired ? "Account comparison marked" : "Comparison mark reset")
            } catch {
                guard owns(epoch, ticket) else { return }
                errorText = ChatsViewModel.describe(error)
            }
        }
    }
}

/// Only this owner's comparison mark can decorate a peer row. Neither the
/// other account's mark nor another login/gateway's cached mark is evidence.
@MainActor
final class PulseSafetyBadgeCache: ObservableObject {
    static let shared = PulseSafetyBadgeCache()
    @Published private(set) var verified: Set<String> = []
    private var ownerEpoch: UUID?

    func mark(_ userId: String, verified: Bool, epoch: UUID) {
        if ownerEpoch != epoch { ownerEpoch = epoch; self.verified = [] }
        if verified { self.verified.insert(userId) }
        else { self.verified.remove(userId) }
    }

    func isVerified(_ userId: String, epoch: UUID) -> Bool {
        ownerEpoch == epoch && verified.contains(userId)
    }
}

struct SafetySheetView: View {
    @ObservedObject var session: PulseSession
    let peer: WireConversationMember
    @StateObject private var model = SafetyBadgeModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    header
                    tileGrid
                    caption
                    actionButtons
                }
                .padding(16)
                .padding(.bottom, 28)
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Safety number")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task(id: "\(session.sessionGeneration):\(peer.id)") {
            model.configure(session: session, peerId: peer.id)
            await model.load()
        }
        .onDisappear { model.stop() }
    }

    private var currentState: WireSafetyState? {
        model.isConfigured(epoch: session.sessionGeneration, peer: peer.id) ? model.state : nil
    }

    private var verified: Bool { currentState?.verified == true }

    private var header: some View {
        VStack(spacing: 8) {
            RowAvatar(name: peer.name, colorName: peer.color, photoPath: peer.avatar, size: 56)
            Text(peer.name)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(PulseTheme.titleOnWash)
            if verified {
                Label("Contact verified", systemImage: "checkmark.shield.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(PulseTheme.emerald)
                if let verifiedAt = currentState?.verifiedAt {
                    Text("Since \(PulseFormat.listStamp(verifiedAt))")
                        .font(.system(size: 11))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    /// The 60 digits - 12 groups of 5, rendered 3 per row (web 3×4 glass
    /// tiles). Client-side split via PulseSafetyNumber.groups (pure, tested).
    private var tileGrid: some View {
        let groups = PulseSafetyNumber.groups(currentState?.safetyNumber ?? "")
        return VStack(spacing: 8) {
            ForEach(0..<4, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { column in
                        let group = currentState == nil ? "—" : groups[row * 3 + column]
                        Text(group)
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.glassFill))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
                            .accessibilityLabel("Digits \(group)")
                    }
                }
            }
        }
    }

    private var caption: some View {
        Text("Compare this account code with \(peer.name). It is derived from account IDs, not device encryption keys. A match or saved checkmark does not prove end-to-end encryption.")
            .font(.system(size: 12.5))
            .foregroundStyle(PulseTheme.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var actionButtons: some View {
        if model.loading && currentState == nil {
            ProgressView().padding(.vertical, 10)
        } else if currentState == nil {
            Button("Retry account comparison") { Task { await model.load(force: true) } }
        } else if verified {
            Button(role: .destructive) {
                model.reset()
            } label: {
                Text("Reset verification")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 46)
            }
            .buttonStyle(.bordered)
            .tint(PulseTheme.rose500)
            .disabled(model.pending || model.loading)
        } else {
            Button {
                model.verify()
            } label: {
                Text("Mark as verified")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.brandGradient))
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(model.pending || model.loading)
        }
        if model.isConfigured(epoch: session.sessionGeneration, peer: peer.id), let error = model.errorText {
            Text(error)
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.amber600)
                .multilineTextAlignment(.center)
            if currentState != nil {
                Button("Retry account comparison") { Task { await model.load(force: true) } }
                    .disabled(model.pending || model.loading)
            }
        }
    }
}

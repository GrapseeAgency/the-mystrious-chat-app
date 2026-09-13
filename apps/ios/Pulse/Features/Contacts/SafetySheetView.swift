import SwiftUI

/// Wave 6 — F-CP-07/08. The DM safety-number surface (safety-sheet.tsx
/// parity): the server-computed 60 digits in a 12×5 tile grid, the verbatim
/// comparison caption, and Mark-verified / Reset actions. NO optimistic
/// lies — both actions settle against the server, then refetch the truth
/// (web onSettled invalidateQueries parity).
@MainActor
final class SafetyBadgeModel: ObservableObject {
    @Published private(set) var state: WireSafetyState?
    @Published private(set) var loading = false
    @Published private(set) var errorText: String?

    private var session: PulseSession?
    private var peerId = ""
    private var loadedFor = ""

    /// Wire once per surface (onAppear) before load().
    func configure(session: PulseSession, peerId: String) {
        self.session = session
        self.peerId = peerId
    }

    func load(force: Bool = false) async {
        guard let session, !peerId.isEmpty else { return }
        guard force || loadedFor != peerId else { return }
        loadedFor = peerId
        loading = state == nil
        defer { loading = false }
        do {
            state = try await session.api.safetyState(peerId: peerId)
            errorText = nil
            PulseSafetyBadgeCache.shared.mark(peerId, verified: state?.verified == true)
        } catch {
            errorText = ChatsViewModel.describe(error)
        }
    }

    /// POST verify — settle-confirmed: the toast fires on success, the state
    /// refetches afterwards (never pre-flips the badge).
    func verify() async {
        guard let session else { return }
        do {
            _ = try await session.api.verifySafety(peerId: peerId)
            PulseHaptics.success()
            session.toasts.show("Safety number verified")
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
        await load(force: true)
    }

    /// DELETE unverify — same settle-then-refetch rule.
    func reset() async {
        guard let session else { return }
        do {
            try await session.api.resetSafety(peerId: peerId)
            PulseHaptics.tap()
            session.toasts.show("Verification reset")
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
        await load(force: true)
    }
}

/// Roster-level memory of verified peers — populated whenever any surface
/// fetches a pair's safety state (user page, room header, this sheet).
/// The contacts rows read it WITHOUT fetching (no N×GET amplification);
/// a badge appears after any of the two ends verified the pair.
@MainActor
final class PulseSafetyBadgeCache: ObservableObject {
    static let shared = PulseSafetyBadgeCache()
    @Published private(set) var verified: Set<String> = []

    func mark(_ userId: String, verified: Bool) {
        if verified {
            self.verified.insert(userId)
        } else {
            self.verified.remove(userId)
        }
    }

    func isVerified(_ userId: String) -> Bool {
        verified.contains(userId)
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
        .onAppear {
            model.configure(session: session, peerId: peer.id)
        }
        .task { await model.load() }
    }

    private var verified: Bool { model.state?.verified == true }

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
                if let verifiedAt = model.state?.verifiedAt {
                    Text("Since \(PulseFormat.listStamp(verifiedAt))")
                        .font(.system(size: 11))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    /// The 60 digits — 12 groups of 5, rendered 3 per row (web 3×4 glass
    /// tiles). Client-side split via PulseSafetyNumber.groups (pure, tested).
    private var tileGrid: some View {
        let groups = PulseSafetyNumber.groups(model.state?.safetyNumber ?? "")
        return VStack(spacing: 8) {
            ForEach(0..<4, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { column in
                        let group = groups[row * 3 + column]
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
        Text("Compare these 60 digits with \(peer.name) in person. If they match, mark this contact as verified.")
            .font(.system(size: 12.5))
            .foregroundStyle(PulseTheme.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var actionButtons: some View {
        if model.loading && model.state == nil {
            ProgressView().padding(.vertical, 10)
        } else if verified {
            Button(role: .destructive) {
                Task { await model.reset() }
            } label: {
                Text("Reset verification")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 46)
            }
            .buttonStyle(.bordered)
            .tint(PulseTheme.rose500)
        } else {
            Button {
                Task { await model.verify() }
            } label: {
                Text("Mark as verified")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.brandGradient))
            }
            .buttonStyle(PulseButtonStyle())
        }
        if let error = model.errorText {
            Text(error)
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.amber600)
                .multilineTextAlignment(.center)
        }
    }
}

import SwiftUI

/// Wave 6 - blocked accounts (F-CP-05, settings-screen.tsx parity): the REAL
/// GET /api/users/{self}/blocks feed, "Blocked {Mon d}" stamps, and the
/// unblock action with the verbatim "Account unblocked" toast. Unblock uses
/// the Wave-6-defect-fixed route (DELETE /api/users/{id}/block?userId=).
struct BlockedListView: View {
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss

    @State private var blocks: [WireBlockedAccount] = []
    @State private var loading = true
    @State private var busyId: String?
    @State private var errorText: String?
    @State private var surfaceEpoch: UUID?
    @State private var readTicket = UUID()
    @State private var actionTicket = UUID()
    @State private var readTask: Task<Void, Never>?
    @State private var actionTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if let errorText {
                            Text(errorText).font(.footnote).foregroundStyle(PulseTheme.textSecondary)
                            Button("Retry list") { reload() }.disabled(busyId != nil)
                        }
                        if loading && blocks.isEmpty {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(PulseTheme.glassFill)
                                .frame(height: 96)
                        } else if blocks.isEmpty && errorText == nil {
                            emptyCard
                        } else {
                            ForEach(blocks) { account in
                                row(account)
                            }
                        }
                        Color.clear.frame(height: 16)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                }
            }
            .navigationTitle("Blocked accounts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task(id: session.sessionGeneration) {
            retire()
            surfaceEpoch = session.sessionGeneration
            blocks = []; errorText = nil
            reload()
        }
        .onDisappear { retire() }
    }

    private var emptyCard: some View {
        VStack(spacing: 10) {
            Image(systemName: "hand.raised")
                .font(.system(size: 26, weight: .light))
                .foregroundStyle(PulseTheme.textTertiary)
                .frame(width: 64, height: 64)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
            Text("Nobody is blocked. Blocked accounts cannot message you in direct chats.")
                .font(.system(size: 12.5))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 280)
        }
        .padding(.vertical, 30)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
    }

    private func row(_ account: WireBlockedAccount) -> some View {
        HStack(spacing: 12) {
            RowAvatar(
                name: account.name ?? "Account",
                colorName: account.color,
                photoPath: account.avatar,
                size: 42,
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(account.name ?? "Account")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if let username = account.username, !username.isEmpty {
                        Text("@\(username)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                    if let stamp = blockedStamp(account.blockedAt) {
                        Text(stamp)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                }
            }
            Spacer()
            Button {
                unblock(account)
            } label: {
                if busyId == account.id {
                    ProgressView().controlSize(.small)
                } else {
                    Text("Unblock")
                        .font(.system(size: 12.5, weight: .bold))
                }
            }
            .buttonStyle(.bordered)
            .tint(PulseTheme.accent)
            .disabled(busyId != nil)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(account.name ?? "Account"), \(blockedStamp(account.blockedAt) ?? "blocked"), unblock button")
    }

    /// "Blocked {Mon d}" - month short + day numeric (en-US, web parity).
    private func blockedStamp(_ iso: String?) -> String? {
        guard let iso, !iso.isEmpty, let date = PulseFormat.date(iso) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d"
        return "Blocked \(formatter.string(from: date))"
    }

    private func owns(_ epoch: UUID) -> Bool {
        !Task.isCancelled && surfaceEpoch == epoch && session.sessionGeneration == epoch
    }

    private func retire() {
        surfaceEpoch = nil
        readTicket = UUID(); actionTicket = UUID()
        readTask?.cancel(); actionTask?.cancel()
        readTask = nil; actionTask = nil; busyId = nil
    }

    private func reload() {
        guard let epoch = surfaceEpoch, owns(epoch), busyId == nil else { return }
        let api = session.api, ticket = UUID()
        readTask?.cancel(); readTicket = ticket; loading = true
        readTask = Task { @MainActor in
            defer { if owns(epoch), readTicket == ticket { loading = false; readTask = nil } }
            do {
                let fresh = try await api.blockedAccounts()
                guard owns(epoch), readTicket == ticket else { return }
                blocks = fresh; errorText = nil
            } catch {
                guard owns(epoch), readTicket == ticket else { return }
                errorText = "Could not refresh blocked accounts. " + ChatsViewModel.describe(error)
            }
        }
    }

    private func unblock(_ account: WireBlockedAccount) {
        guard let epoch = surfaceEpoch, owns(epoch), busyId == nil else { return }
        let api = session.api, ticket = UUID()
        // Claim synchronously at the tap, and invalidate any pre-write list read.
        busyId = account.id; actionTicket = ticket
        readTicket = UUID(); readTask?.cancel(); loading = false
        actionTask = Task { @MainActor in
            defer { if owns(epoch), actionTicket == ticket { busyId = nil; actionTask = nil } }
            do {
                try await api.unblock(userId: account.id)
                guard owns(epoch), actionTicket == ticket else { return }
                blocks.removeAll { $0.id == account.id }
                errorText = nil
                PulseHaptics.success()
                session.toasts.show("Account unblocked")
            } catch {
                guard owns(epoch), actionTicket == ticket else { return }
                errorText = "Unblock was not confirmed. " + ChatsViewModel.describe(error)
            }
        }
    }
}

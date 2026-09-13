import SwiftUI

/// Wave 6 — blocked accounts (F-CP-05, settings-screen.tsx parity): the REAL
/// GET /api/users/{self}/blocks feed, "Blocked {Mon d}" stamps, and the
/// unblock action with the verbatim "Account unblocked" toast. Unblock uses
/// the Wave-6-defect-fixed route (DELETE /api/users/{id}/block?userId=).
struct BlockedListView: View {
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss

    @State private var blocks: [WireBlockedAccount] = []
    @State private var loading = true
    @State private var busyId: String?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if loading && blocks.isEmpty {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(PulseTheme.glassFill)
                                .frame(height: 96)
                        } else if blocks.isEmpty {
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
        .task { await reload() }
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
                Task { await unblock(account) }
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
            .disabled(busyId == account.id)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(account.name ?? "Account"), \(blockedStamp(account.blockedAt) ?? "blocked"), unblock button")
    }

    /// "Blocked {Mon d}" — month short + day numeric (en-US, web parity).
    private func blockedStamp(_ iso: String?) -> String? {
        guard let iso, !iso.isEmpty, let date = PulseFormat.date(iso) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MMM d"
        return "Blocked \(formatter.string(from: date))"
    }

    private func reload() async {
        blocks = (try? await session.api.blockedAccounts()) ?? []
        loading = false
    }

    private func unblock(_ account: WireBlockedAccount) async {
        guard busyId == nil else { return }
        busyId = account.id
        defer { busyId = nil }
        do {
            try await session.api.unblock(userId: account.id)
            PulseHaptics.success()
            session.toasts.show("Account unblocked")
            await reload()
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}

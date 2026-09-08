import SwiftUI

/// Hub — native recreation of the web hub surface at wave scope: the REAL
/// wallet (coins / gems / streak from GET /api/hub/wallet) in a glass hero,
/// plus staggered native tiles for surfaces that graduate in later waves.
struct HubView: View {
    @ObservedObject var session: PulseSession

    @State private var viewModel = HubViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    walletHero
                    tileGrid
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .navigationTitle("Hub")
            .navigationBarTitleDisplayMode(.large)
            .refreshable { await viewModel.load(api: session.api) }
            .task { await viewModel.load(api: session.api) }
            .overlay {
                if viewModel.loading && viewModel.wallet == nil {
                    ProgressView("Opening the hub…")
                }
            }
        }
        .onAppear { viewModel.observe(session: session) }
    }

    private var walletHero: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24)
                .fill(PulseTheme.gradient(named: "emerald"))
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    Image(systemName: "bolt.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(.white.opacity(0.9))
                    Text("Pulse Wallet")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.white)
                    Spacer()
                    if let checked = viewModel.wallet?.checkedInToday {
                        Label(checked ? "Checked in" : "Not yet",
                              systemImage: checked ? "checkmark.circle.fill" : "circle.dashed")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.9))
                    }
                }

                HStack(spacing: 0) {
                    walletStat("Coins", viewModel.wallet?.coins, icon: "circlebadge.2.fill")
                    walletStat("Gems", viewModel.wallet?.gems, icon: "diamond.fill")
                    walletStat("Streak", viewModel.wallet?.streak, icon: "flame.fill")
                }
            }
            .padding(20)
        }
    }

    private func walletStat(_ label: String, _ value: Int?, icon: String) -> some View {
        VStack(spacing: 3) {
            Label(value.map { "\($0)" } ?? "—", systemImage: icon)
                .font(.headline.weight(.bold))
                .foregroundStyle(.white)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.8))
        }
        .frame(maxWidth: .infinity)
    }

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    private var tileGrid: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            HubTile(label: "Stories", icon: "book.fill", tint: PulseTheme.color(named: "emerald"), delay: 0.0)
            HubTile(label: "Calls", icon: "phone.fill", tint: PulseTheme.color(named: "teal"), delay: 0.04)
            HubTile(label: "Games", icon: "gamecontroller.fill", tint: PulseTheme.color(named: "violet"), delay: 0.08)
            HubTile(label: "Leaderboard", icon: "trophy.fill", tint: PulseTheme.color(named: "amber"), delay: 0.12)
            HubTile(label: "Mini apps", icon: "square.grid.2x2.fill", tint: PulseTheme.color(named: "cyan"), delay: 0.16)
            HubTile(label: "Tournaments", icon: "flag.checkered", tint: PulseTheme.color(named: "rose"), delay: 0.20)
        }
    }
}

private struct HubTile: View {
    let label: String
    let icon: String
    let tint: Color
    let delay: Double

    @State private var entered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(RoundedRectangle(cornerRadius: 14).fill(tint))
            Text(label)
                .font(.subheadline.weight(.semibold))
            Text("Next native wave")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 128, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 22)
                .fill(.regularMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: 22)
                        .strokeBorder(Color.primary.opacity(0.06), lineWidth: 1),
                ),
        )
        .scaleEffect(entered || reduceMotion ? 1.0 : 0.92)
        .opacity(entered || reduceMotion ? 1.0 : 0.0)
        .onAppear {
            withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion).delay(reduceMotion ? 0 : delay)) {
                entered = true
            }
        }
    }
}

/// Hub state holder — real wallet numbers, no fake data.
@MainActor
final class HubViewModel: ObservableObject {
    @Published private(set) var wallet: WireWallet?
    @Published private(set) var loading = false
    @Published private(set) var errorText: String?

    private var observing = false

    func observe(session: PulseSession) {
        guard !observing else { return }
        observing = true
    }

    func load(api: PulseAPIClient) async {
        guard !api.userId.isEmpty else {
            errorText = "Pick an identity in Profile to open your wallet."
            return
        }
        loading = true
        defer { loading = false }
        do {
            wallet = try await api.wallet()
            errorText = nil
        } catch {
            if wallet == nil { errorText = ChatsViewModel.describe(error) }
        }
    }
}

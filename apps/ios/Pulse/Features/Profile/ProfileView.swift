import SwiftUI

/// Profile — identity management (native onboarding parity), appearance
/// (dark override + the ambient FX picker with LIVE shader preview strips),
/// and honest about-notes. All settings persist via PulsePrefs.
/// R2-B — the hub wallet chip rides the REAL GET /api/hub/wallet (web
/// profile-tab.tsx:149-153 parity: loading / honest "—" / coins amount).
struct ProfileView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @State private var identitySheet = false
    // Wave 6 — the full profile editor (F-CP-04/09) via the real PATCH.
    @State private var editProfileOpen = false
    // R2-B — hub wallet chip state (GET /api/hub/wallet?userId=).
    enum WalletPhase: Equatable { case loading, loaded, failed }
    @State private var walletPhase: WalletPhase = .loading
    @State private var walletCoins: Int = 0

    var body: some View {
        NavigationStack {
            List {
                identitySection
                walletSection
                statusSection
                appearanceSection
                motionSection
                aboutSection
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.large)
        }
        .sheet(isPresented: $identitySheet) {
            IdentityPickerSheet(mode: .switcher, session: session, prefs: prefs) {}
        }
        .sheet(isPresented: $editProfileOpen) {
            ProfileEditView(session: session, prefs: prefs)
        }
        .task { await loadWallet() }
    }

    // ── R2-B — hub wallet chip (real coins balance) ──

    private func loadWallet() async {
        // The route upserts a zero wallet and answers { wallet: { coins } }.
        guard session.viewer != nil else {
            walletPhase = .failed
            return
        }
        do {
            let wallet = try await session.api.wallet()
            walletCoins = wallet.coins ?? 0
            walletPhase = .loaded
        } catch {
            walletPhase = .failed
        }
    }

    private var walletSection: some View {
        Section("Wallet") {
            Button {
                // A failed fetch is honest — tap retries (web refetch parity).
                guard walletPhase == .failed else { return }
                walletPhase = .loading
                Task { await loadWallet() }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "coins")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(PulseTheme.amber)
                    Text("Coin balance")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Spacer()
                    switch walletPhase {
                    case .loading:
                        ProgressView().controlSize(.small)
                    case .failed:
                        Text("—")
                            .font(.subheadline.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.secondary)
                    case .loaded:
                        HStack(spacing: 4) {
                            Text("\(walletCoins)")
                                .font(.subheadline.weight(.bold).monospacedDigit())
                                .foregroundStyle(PulseTheme.titleOnPanel)
                            Text("coins")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(walletPhase == .loaded
                ? "Coin balance \(walletCoins)"
                : "Coin balance loading")
        } footer: {
            Text("Earn coins from check-ins and tasks — spend them in the Hub.")
        }
    }

    // ── sections ─────────────────────────────────────────────
    private var identitySection: some View {
        Section {
            HStack(spacing: 12) {
                PulseAvatar(
                    name: prefs.viewer?.name ?? "You",
                    color: PulseTheme.color(named: prefs.viewer?.color),
                    photoURL: PulseTheme.photoURL(prefs.viewer?.avatar),
                    size: 52,
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(prefs.viewer?.name ?? "No identity")
                        .font(.body.weight(.semibold))
                    Text(prefs.viewer?.username.map { "@\($0)" } ?? "Signed in on this device")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    editProfileOpen = true
                } label: {
                    Label("Edit", systemImage: "pencil")
                        .font(.footnote.weight(.semibold))
                }
                .tint(PulseTheme.emerald)
                .buttonStyle(.bordered)
                Button {
                    identitySheet = true
                } label: {
                    Label("Switch", systemImage: "arrow.left.arrow.right")
                        .font(.footnote.weight(.semibold))
                }
                .tint(PulseTheme.emerald)
                .buttonStyle(.bordered)
            }
            .padding(.vertical, 2)
        } footer: {
            Text("Edit opens name, bio, avatar, color, @handle and status — saved to the server instantly.")
        }
    }

    /// F-CP-09 — the viewer's status emoji + text, live from prefs.
    private var statusSection: some View {
        Section("Status") {
            if let emoji = prefs.viewerStatusEmoji, !emoji.isEmpty {
                HStack(spacing: 8) {
                    Text(pulseStatusGlyphDisplay(emoji))
                        .font(.system(size: 20))
                    Text(prefs.viewerStatusText ?? "")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } else if let text = prefs.viewerStatusText, !text.isEmpty {
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Text("No status set")
                    .font(.subheadline)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Mode", selection: Binding(
                get: { prefs.appearance },
                set: { prefs.setAppearance($0) },
            )) {
                Text("Auto").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.segmented)

            VStack(alignment: .leading, spacing: 10) {
                Text("Ambient field")
                    .font(.subheadline.weight(.semibold))
                Text("The web's WebGL modes, rebuilt with Metal + Canvas — same palette, same physics.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AmbientMode.allCases) { mode in
                            ambientCard(mode)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func ambientCard(_ mode: AmbientMode) -> some View {
        let selected = prefs.ambientMode == mode
        return Button {
            prefs.setAmbientMode(mode)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                AmbientFieldView(mode: mode, dark: true, preview: true)
                    .frame(width: 132, height: 74)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(selected ? PulseTheme.emerald : Color.primary.opacity(0.08), lineWidth: selected ? 2.5 : 1),
                    )
                Text(mode.label)
                    .font(.caption.weight(selected ? .bold : .medium))
                    .foregroundStyle(selected ? PulseTheme.emerald : .secondary)
            }
        }
        .buttonStyle(PulseButtonStyle())
    }

    private var motionSection: some View {
        Section {
            // Reduce Motion is honored system-wide by every FX surface.
            Label("Respects system Reduce Motion", systemImage: "figure.mind.and.body")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Motion")
        } footer: {
            Text("Particles, springs and the ambient loop all render a single static frame when Reduce Motion is on.")
        }
    }

    private var aboutSection: some View {
        Section("About") {
            LabeledContent("Version", value: "1.0.0-native")
            LabeledContent("Stack", value: "SwiftUI · GRDB · Socket.IO")
            VStack(alignment: .leading, spacing: 4) {
                Text("Rebuilt natively against the same live gateway as the web app — chats, rooms, reactions, typing and presence are real; stories, calls and games graduate in later waves.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

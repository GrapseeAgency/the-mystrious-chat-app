import SwiftUI
import UIKit

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
    // R7 bonus — the saved-messages library in the profile tab (web hosts it
    // here; iOS only exposed it through the dock-More menu).
    @State private var savedOpen = false
    // R2-B — hub wallet chip state (GET /api/hub/wallet?userId=).
    enum WalletPhase: Equatable { case loading, loaded, failed }
    @State private var walletPhase: WalletPhase = .loading
    @State private var walletCoins: Int = 0
    // R14 5-b — the activity stats row (GET /api/users/{id}/stats — the
    // same endpoint the Settings footprint + user pages call).
    @State private var stats: WireUserStats?
    @State private var statsFailed = false
    // R14 5-b — the @handle chip's copy confirmation (web handleCopied).
    @State private var handleCopied = false

    var body: some View {
        NavigationStack {
            List {
                heroSection
                identitySection
                statsSection
                walletSection
                savedSection
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
        .sheet(isPresented: $savedOpen) {
            // R7 bonus — the EXISTING SavedLibraryView route (RootView mounts
            // the same sheet from dock-More). "Open original" hands the room
            // + jump target to the session bridge; the Chats tab consumes it
            // (Published replay) on next activation.
            SavedLibraryView(session: session) { conversation, messageId in
                savedOpen = false
                session.requestOpenRoom(conversation, jumpMessageId: messageId)
            }
        }
        .task {
            await loadWallet()
            await loadStats()
        }
    }

    // ── R14 5-b — the hero cover + activity stats (web profile-tab.tsx
    // :307-528 parity) ────────────────────────────────────────

    /// The gradient cover: the identity gradient + 1-2 blurred orb overlays
    /// (web hero cover :313-337 — orbs breathe under reduced-motion off).
    private var heroSection: some View {
        Section {
            ZStack {
                LinearGradient(
                    colors: [PulseTheme.gradient(named: prefs.viewer?.color ?? "emerald"), PulseTheme.emeraldDeep],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing,
                )
                Circle()
                    .fill(Color.white.opacity(0.16))
                    .frame(width: 150, height: 150)
                    .blur(radius: 26)
                    .offset(x: 96, y: -46)
                Circle()
                    .fill(Color.black.opacity(0.10))
                    .frame(width: 120, height: 120)
                    .blur(radius: 24)
                    .offset(x: -104, y: 52)
            }
            .frame(height: 112)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .accessibilityHidden(true)
        }
        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 0, trailing: 0))
        .listRowBackground(Color.clear)
    }

    /// The stats row: Messages / Rooms / Coins / Member since — real data
    /// only (stats endpoint + the wallet balance already loaded; failures
    /// render the honest em dash).
    private var statsSection: some View {
        Section {
            HStack(spacing: 8) {
                profileStatTile(
                    value: stats.map { "\($0.messages ?? 0)" } ?? (statsFailed ? "—" : "…"),
                    label: "Messages",
                )
                profileStatTile(
                    value: stats.map { "\($0.chats ?? 0)" } ?? (statsFailed ? "—" : "…"),
                    label: "Rooms",
                )
                profileStatTile(
                    value: walletPhase == .loaded ? "\(walletCoins)" : (walletPhase == .failed ? "—" : "…"),
                    label: "Coins",
                    accent: true,
                )
                profileStatTile(value: memberSinceShort ?? "—", label: "Member since")
            }
            .padding(.vertical, 2)
        }
    }

    /// Web StatTile twin — value over the label in a soft tile.
    private func profileStatTile(value: String, label: String, accent: Bool = false) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.system(size: 16, weight: .black, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(accent ? PulseTheme.emerald : PulseTheme.titleOnPanel)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, minHeight: 56)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(.secondarySystemBackground).opacity(0.55)),
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value)")
    }

    /// The short member-since stamp (web memberSinceShort parity) — the
    /// stats joinedAt (server-derived createdAt), month-year format
    /// (web formatMemberSince, pulse-utils.ts :280-283); omitted when unknown.
    private var memberSinceShort: String? {
        guard let joined = stats?.joinedAt else { return nil }
        let text = PulseFormat.monthYear(joined)
        return text.isEmpty ? nil : text
    }

    private func loadStats() async {
        guard let viewerId = session.viewer?.id else {
            statsFailed = true
            return
        }
        do {
            stats = try await session.api.userStats(viewerId)
        } catch {
            statsFailed = true
        }
    }

    /// R14 5-b — the @handle chip tap → clipboard + haptic (web copyHandle
    /// :243-251). No handle → opens the editor (web opens the handle sheet).
    private func copyHandle() {
        guard let handle = prefs.viewer?.username, !handle.isEmpty else {
            editProfileOpen = true
            return
        }
        UIPasteboard.general.string = "@\(handle)"
        PulseHaptics.success()
        handleCopied = true
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            handleCopied = false
        }
    }

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
        Section {
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
        } header: {
            Text("Wallet")
        } footer: {
            Text("Earn coins from check-ins and tasks — spend them in the Hub.")
        }
    }

    /// R7 bonus — "Saved messages" row (web copy verbatim) → the library
    /// sheet. Same bookmark row language as the wallet row above.
    private var savedSection: some View {
        Section {
            Button {
                savedOpen = true
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "bookmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(PulseTheme.emerald)
                    Text("Saved messages")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Saved messages")
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
                    online: session.isOnline(prefs.viewer?.id ?? ""),
                    size: 52,
                )
                VStack(alignment: .leading, spacing: 2) {
                    // R14 5-b — name + the registered-member badge (web
                    // BadgeCheck :398-408).
                    HStack(spacing: 4) {
                        Text(prefs.viewer?.name ?? "No identity")
                            .font(.body.weight(.semibold))
                            .lineLimit(1)
                        Image(systemName: "checkmark.seal.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(PulseTheme.emerald)
                            .accessibilityLabel("Registered member")
                    }
                    // R14 5-b — the @handle chip is tap-to-copy (web
                    // copyHandle :243-251); no handle → opens the editor.
                    Button {
                        copyHandle()
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: handleCopied ? "checkmark" : "at")
                                .font(.system(size: 10, weight: .bold))
                            Text(handleCopied ? "Copied" : (prefs.viewer?.username.map { "@\($0)" } ?? "Set your handle"))
                                .font(.footnote.weight(.semibold))
                                .lineLimit(1)
                        }
                        .foregroundStyle(PulseTheme.emerald)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(PulseTheme.emerald.opacity(0.10)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(handleCopied ? "Handle copied" : "Copy handle")
                    // R14 5-b — the member-since caption (web :429-435).
                    if let joined = memberSinceShort {
                        Text("Member since \(joined)")
                            .font(.system(size: 10.5, weight: .medium))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
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
            // R14 5-b — the bio render (web bio block :415-423, fallback
            // "No bio yet").
            Text(prefs.viewerAbout?.isEmpty == false ? prefs.viewerAbout! : "No bio yet")
                .font(.footnote)
                .foregroundStyle(prefs.viewerAbout?.isEmpty == false ? Color.primary : Color.secondary)
                .fixedSize(horizontal: false, vertical: true)
            // R47 — profile share (web profile-tab.tsx:258-278 parity): the
            // OS share sheet via ShareLink (iOS 17 floor) with the verbatim
            // web copy plus the pulse://user deep link. No handle → honest
            // inert row (web toasts "Claim a handle first"; here the Edit
            // button in the row above is how you claim one).
            HStack(spacing: 10) {
                if let handle = prefs.viewer?.username, !handle.isEmpty {
                    ShareLink(item: Self.shareMessage(handle: handle, userId: prefs.viewer?.id)) {
                        Label("Share profile", systemImage: "square.and.arrow.up")
                            .font(.footnote.weight(.semibold))
                    }
                    .tint(PulseTheme.emerald)
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Share profile — Find me on Pulse at \(handle)")
                } else {
                    Label("Claim a @handle to share your profile", systemImage: "square.and.arrow.up")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.vertical, 2)
        } footer: {
            Text("Edit opens name, bio, avatar, color, @handle and status — saved to the server instantly.")
        }
    }

    /// R47 — the shared text: verbatim web copy (profile-tab.tsx:270) plus
    /// the pulse://user/{id} deep link when the viewer id exists (the app
    /// scheme registered in project.yml; PulseDeepLink routes it back in).
    static func shareMessage(handle: String, userId: String?) -> String {
        var text = "Find me on Pulse — @\(handle)"
        if let userId, !userId.isEmpty {
            text += "\n\(PulseDeepLink.scheme)://user/\(userId)"
        }
        return text
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

import SwiftUI
import UIKit

/// Profile — R17 Neo mirror of the web R35 profile-tab: a slim flat
/// identity cover with a scanline texture (zero carnival blobs), an
/// overlapping 84pt avatar on a 2.5pt identity ring, mono @handle chip,
/// one flat stats instrument row, and quiet hairline cards for Saved /
/// Copy account ID / Sign out. Everything below the account card is the
/// iOS-native appearance/motion/about surface, restyled onto the same
/// card language. All settings persist via PulsePrefs; all data stays
/// real (GET /api/hub/wallet, GET /api/users/{id}/stats).
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
    // R17 Neo — the account card: copy-ID confirmation + sign-out dialog.
    @State private var idCopied = false
    @State private var signOutArmed = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.neoPage
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 14) {
                        heroBlock
                        statsRow
                        savedCard
                        accountCard
                        walletCard
                        statusCard
                        appearanceCard
                        motionCard
                        aboutCard
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 28)
                }
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

    // ── hero (web profile-tab R35 hero: flat cover, no orbs) ────────────

    /// Flat identity cover (112pt) + overlapping avatar block. The cover is
    /// the member-color gradient with a cheap capped scanline texture and a
    /// 2pt signal line grounding its bottom edge; the avatar starts 44pt
    /// above the cover's bottom edge (web -mt-11).
    private var heroBlock: some View {
        ZStack(alignment: .top) {
            heroCover
                .accessibilityHidden(true)

            VStack(spacing: 10) {
                identityRing
                nameRow
                handleChip
                statusBioBlock
                actionCapsules
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 68)
        }
    }

    private var heroCover: some View {
        ZStack {
            LinearGradient(
                colors: [PulseTheme.color(named: prefs.viewer?.color ?? "emerald"), PulseTheme.emeraldDeep],
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )
            scanlines
            LinearGradient(
                colors: [Color.white.opacity(0.16), Color.clear],
                startPoint: .top,
                endPoint: .bottom,
            )
            LinearGradient(
                colors: [Color.clear, Color.white.opacity(0.8), Color.clear],
                startPoint: .leading,
                endPoint: .trailing,
            )
            .frame(height: 2)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(height: 112)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// Scanline texture — capped rows of 1pt rects at low opacity (cheap,
    /// static; the web equivalent is the .scan-fx overlay).
    private var scanlines: some View {
        VStack(spacing: 7) {
            ForEach(0..<14, id: \.self) { _ in
                Rectangle()
                    .fill(Color.white.opacity(0.05))
                    .frame(height: 1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    /// 84pt avatar on a 2.5pt member-color ring over a surface bezel
    /// (web: p-[2.5px] gradient ring + p-[2.5px] #0d1211 inner ring).
    private var identityRing: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(
                    colors: [PulseTheme.color(named: prefs.viewer?.color ?? "emerald"), PulseTheme.emeraldDeep],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing,
                ))
            Circle()
                .fill(PulseTheme.neoCard)
                .padding(2.5)
            PulseAvatar(
                name: prefs.viewer?.name ?? "You",
                color: PulseTheme.color(named: prefs.viewer?.color),
                photoURL: PulseTheme.photoURL(prefs.viewer?.avatar),
                online: session.isOnline(prefs.viewer?.id ?? ""),
                size: 84,
            )
            .padding(5)
        }
        .frame(width: 94, height: 94)
        .shadow(color: Color.black.opacity(0.22), radius: 10, y: 5)
    }

    private var nameRow: some View {
        HStack(spacing: 6) {
            Text(prefs.viewer?.name ?? "No identity")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(PulseTheme.titleOnPanel)
                .lineLimit(1)
            // R17 — quiet trust mark (web PulseSeal tinted accent).
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 15))
                .foregroundStyle(PulseTheme.accent)
                .accessibilityLabel("Registered member")
        }
    }

    /// Mono @handle chip on ultraThinMaterial — tap to copy (web copyHandle
    /// :243-251); no handle → opens the editor.
    private var handleChip: some View {
        Button {
            copyHandle()
        } label: {
            HStack(spacing: 5) {
                Image(systemName: handleCopied ? "checkmark" : "at")
                    .font(.system(size: 11, weight: .bold))
                Text(handleCopied ? "Copied" : (prefs.viewer?.username.map { "@\($0)" } ?? "Set your handle"))
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .lineLimit(1)
            }
            .foregroundStyle(PulseTheme.accent)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(.ultraThinMaterial))
            .overlay(Capsule().strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(handleCopied ? "Handle copied" : "Copy handle")
    }

    /// Status + bio at 13pt (web status line + bio block).
    private var statusBioBlock: some View {
        VStack(spacing: 6) {
            if let emoji = prefs.viewerStatusEmoji, !emoji.isEmpty {
                HStack(spacing: 6) {
                    Text(pulseStatusGlyphDisplay(emoji))
                        .font(.system(size: 14))
                    if let text = prefs.viewerStatusText, !text.isEmpty {
                        Text(text)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(PulseTheme.textPrimary)
                            .lineLimit(1)
                    }
                }
            } else if let text = prefs.viewerStatusText, !text.isEmpty {
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(PulseTheme.textPrimary)
                    .lineLimit(1)
            }
            Text(prefs.viewerAbout?.isEmpty == false ? prefs.viewerAbout! : "No bio yet")
                .font(.system(size: 13))
                .foregroundStyle(prefs.viewerAbout?.isEmpty == false ? PulseTheme.textSecondary : PulseTheme.neoMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
    }

    /// Quick actions — one signal, one ghost (web: filled accent "Edit
    /// profile" capsule + glass "Share" capsule). Share rides the existing
    /// ShareLink deep-link message; no handle → honest inert ghost.
    private var actionCapsules: some View {
        HStack(spacing: 10) {
            Button {
                editProfileOpen = true
            } label: {
                Label("Edit profile", systemImage: "pencil")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(PulseTheme.onAccent)
                    .padding(.horizontal, 16)
                    .frame(height: 38)
                    .background(Capsule().fill(PulseTheme.accent))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Edit profile")

            if let handle = prefs.viewer?.username, !handle.isEmpty {
                ShareLink(item: Self.shareMessage(handle: handle, userId: prefs.viewer?.id)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                        .padding(.horizontal, 16)
                        .frame(height: 38)
                        .background(Capsule().fill(.ultraThinMaterial))
                        .overlay(Capsule().strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
                }
                .accessibilityLabel("Share profile, find me on Pulse at \(handle)")
            } else {
                Label("Claim a @handle to share", systemImage: "square.and.arrow.up")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(PulseTheme.neoMuted)
                    .padding(.horizontal, 16)
                    .frame(height: 38)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .overlay(Capsule().strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
            }
        }
    }

    // ── stats (web R35: one flat instrument row, mono numerals) ─────────

    /// Messages / Rooms / Coins / Since — real data only; failures render
    /// the honest em dash, loads render the quiet interpunct.
    private var statsRow: some View {
        HStack(spacing: 0) {
            statCell(value: statsValueText, label: "Messages")
            statDivider
            statCell(value: roomsValueText, label: "Rooms")
            statDivider
            statCell(value: coinsValueText, label: "Coins", accent: true)
            statDivider
            statCell(value: memberSinceShort ?? "—", label: "Since", small: true)
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    private var statDivider: some View {
        Rectangle()
            .fill(PulseTheme.neoHairline)
            .frame(width: 1, height: 26)
    }

    private func statCell(value: String, label: String, small: Bool = false, accent: Bool = false) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.system(size: small ? 11 : 15, weight: .semibold, design: .monospaced))
                .foregroundStyle(accent ? PulseTheme.accent : PulseTheme.titleOnPanel)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(PulseTheme.neoMuted)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value)")
    }

    private var statsValueText: String {
        guard let stats else { return statsFailed ? "—" : "·" }
        return (stats.messages ?? 0).formatted()
    }

    private var roomsValueText: String {
        guard let stats else { return statsFailed ? "—" : "·" }
        return (stats.chats ?? 0).formatted()
    }

    private var coinsValueText: String {
        switch walletPhase {
        case .loading: return "·"
        case .failed: return "—"
        case .loaded: return walletCoins.formatted()
        }
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

    // ── data loads (unchanged behavior) ──────────────────────

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

    /// R17 — account ID tap → clipboard + haptic (web copyId :510-529).
    private func copyAccountId() {
        guard let id = prefs.viewer?.id, !id.isEmpty else { return }
        UIPasteboard.general.string = id
        PulseHaptics.success()
        idCopied = true
        Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            idCopied = false
        }
    }

    /// R17 — sign out, mirroring the IdentityPickerSheet forget flow:
    /// session teardown first, then setViewer(nil) which wipes the stored
    /// viewer + identity-bound token; RootView flips to onboarding.
    private func signOut() {
        PulseHaptics.tap()
        session.stop()
        prefs.setViewer(nil)
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

    // ── cards ────────────────────────────────────────────────

    /// R17 — Saved messages in a quiet hairline card (web ProfileSection
    /// "Saved" → ChevronRow).
    private var savedCard: some View {
        VStack(spacing: 0) {
            Button {
                savedOpen = true
            } label: {
                HStack(spacing: 12) {
                    neoRowIcon("star.fill")
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Saved messages")
                            .font(.system(size: 14.5, weight: .medium))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                        Text("Long-press any message in a chat, then Save")
                            .font(.system(size: 11.5))
                            .foregroundStyle(PulseTheme.neoMuted)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(PulseTheme.neoMuted)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Saved messages")
        }
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    /// R17 — the account card (web ProfileSection "Account"): Copy account
    /// ID, Switch identity (the iOS-native switcher), Sign out. Rows split
    /// by hairlines, no heavy chrome.
    private var accountCard: some View {
        VStack(spacing: 0) {
            Button {
                copyAccountId()
            } label: {
                HStack(spacing: 12) {
                    neoRowIcon("touchid")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(idCopied ? "Account ID copied" : "Copy account ID")
                            .font(.system(size: 14.5, weight: .medium))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                        Text(prefs.viewer?.id ?? "")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(PulseTheme.neoMuted)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                    Image(systemName: idCopied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(idCopied ? PulseTheme.accent : PulseTheme.neoMuted)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(idCopied ? "Account ID copied" : "Copy account ID")

            cardSeparator

            Button {
                identitySheet = true
            } label: {
                HStack(spacing: 12) {
                    neoRowIcon("person.2.fill")
                    Text("Switch identity")
                        .font(.system(size: 14.5, weight: .medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(PulseTheme.neoMuted)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Switch identity")

            cardSeparator

            Button {
                signOutArmed = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(PulseTheme.neoDestructive)
                        .frame(width: 30, height: 30)
                        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(PulseTheme.neoDestructive.opacity(0.12)))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Sign out")
                            .font(.system(size: 14.5, weight: .medium))
                            .foregroundStyle(PulseTheme.neoDestructive)
                        Text("Return to the welcome screen. Nothing is deleted.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(PulseTheme.neoMuted)
                    }
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Sign out")
        }
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
        .confirmationDialog(
            "Sign out?",
            isPresented: $signOutArmed,
            titleVisibility: .visible,
        ) {
            Button("Sign out", role: .destructive) {
                signOut()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll return to onboarding and can pick or create another identity.")
        }
    }

    /// Wallet row (unchanged behavior: honest phases, tap-to-retry on fail).
    private var walletCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            neoCardHeader("Wallet")
            Button {
                // A failed fetch is honest — tap retries (web refetch parity).
                guard walletPhase == .failed else { return }
                walletPhase = .loading
                Task { await loadWallet() }
            } label: {
                HStack(spacing: 12) {
                    neoRowIcon("bitcoinsign.circle.fill")
                    Text("Coin balance")
                        .font(.system(size: 14.5, weight: .medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Spacer()
                    switch walletPhase {
                    case .loading:
                        ProgressView().controlSize(.small)
                    case .failed:
                        Text("—")
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(PulseTheme.neoMuted)
                    case .loaded:
                        Text("\(walletCoins)")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(walletPhase == .loaded
                ? "Coin balance \(walletCoins)"
                : "Coin balance loading")
            Text("Earn coins from check-ins and tasks, spend them in the Hub.")
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.neoMuted)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    /// F-CP-09 — the viewer's status emoji + text, live from prefs.
    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            neoCardHeader("Status")
            if let emoji = prefs.viewerStatusEmoji, !emoji.isEmpty {
                HStack(spacing: 8) {
                    Text(pulseStatusGlyphDisplay(emoji))
                        .font(.system(size: 18))
                    Text(prefs.viewerStatusText ?? "")
                        .font(.system(size: 13))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .lineLimit(1)
                }
            } else if let text = prefs.viewerStatusText, !text.isEmpty {
                Text(text)
                    .font(.system(size: 13))
                    .foregroundStyle(PulseTheme.textSecondary)
            } else {
                Text("No status set")
                    .font(.system(size: 13))
                    .foregroundStyle(PulseTheme.neoMuted)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    /// Appearance — mode picker + the ambient FX strip (unchanged behavior;
    /// the previews are the real Metal/Canvas modes).
    private var appearanceCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            neoCardHeader("Appearance")
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
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text("The web's WebGL modes, rebuilt with Metal + Canvas — same palette, same physics.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(PulseTheme.neoMuted)
                    .fixedSize(horizontal: false, vertical: true)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AmbientMode.allCases) { mode in
                            ambientCard(mode)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
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
                            .strokeBorder(selected ? PulseTheme.accent : PulseTheme.neoHairline, lineWidth: selected ? 2.5 : 1),
                    )
                Text(mode.label)
                    .font(.caption.weight(selected ? .bold : .medium))
                    .foregroundStyle(selected ? PulseTheme.accent : PulseTheme.neoMuted)
            }
        }
        .buttonStyle(PulseButtonStyle())
    }

    /// Motion note (unchanged copy — Reduce Motion is honored system-wide).
    private var motionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            neoCardHeader("Motion")
            Label("Respects system Reduce Motion", systemImage: "figure.mind.and.body")
                .font(.system(size: 12.5))
                .foregroundStyle(PulseTheme.textSecondary)
            Text("Particles, springs and the ambient loop all render a single static frame when Reduce Motion is on.")
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.neoMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            neoCardHeader("About")
            LabeledContent("Version", value: "1.0.0-native")
                .font(.system(size: 13))
            LabeledContent("Stack", value: "SwiftUI · GRDB · Socket.IO")
                .font(.system(size: 13))
            Text("Rebuilt natively against the same live gateway as the web app — chats, rooms, reactions, typing and presence are real.")
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.neoMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(PulseTheme.neoCard))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(PulseTheme.neoHairline, lineWidth: 1))
    }

    // ── Neo card primitives ──────────────────────────────────

    /// Small uppercase card header (web ProfileSection title).
    private func neoCardHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 11.5, weight: .bold))
            .foregroundStyle(PulseTheme.neoMuted)
            .tracking(0.8)
    }

    /// Accent-tinted icon tile (web IconTile: accent 12% bg, accent glyph).
    private func neoRowIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(PulseTheme.accent)
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(PulseTheme.accent.opacity(0.12)))
    }

    private var cardSeparator: some View {
        Rectangle()
            .fill(PulseTheme.neoHairline)
            .frame(height: 1)
            .padding(.leading, 56)
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
}

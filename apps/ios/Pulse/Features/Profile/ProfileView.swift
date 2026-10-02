import SwiftUI
import UIKit

/// Profile - PULSE EMBER (EMB-I) mirror of the reference profile screen:
/// the sunset ground with glass chrome, a centered 96pt avatar with the
/// amber presence dot, one row of three stat cards (existing stats +
/// wallet loaders only), and a quiet account card (Saved / Copy ID /
/// Switch identity / Sign out). Everything below the account card is the
/// iOS-native appearance/motion/about surface, restyled onto the same
/// ember card language. All settings persist via PulsePrefs; all data
/// stays real (GET /api/hub/wallet, GET /api/users/{id}/stats). Every
/// action, sheet and endpoint from the previous build is byte-preserved.
struct ProfileView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @State private var identitySheet = false
    // Wave 6 - the full profile editor (F-CP-04/09) via the real PATCH.
    @State private var editProfileOpen = false
    // R7 bonus - the saved-messages library in the profile tab (web hosts it
    // here; iOS only exposed it through the dock-More menu).
    @State private var savedOpen = false
    // R2-B - hub wallet chip state (GET /api/hub/wallet?userId=).
    enum WalletPhase: Equatable { case loading, loaded, failed }
    @State private var walletPhase: WalletPhase = .loading
    @State private var walletCoins: Int = 0
    // R14 5-b - the activity stats row (GET /api/users/{id}/stats - the
    // same endpoint the Settings footprint + user pages call).
    @State private var stats: WireUserStats?
    @State private var statsFailed = false
    // The @handle chip's copy confirmation (web handleCopied).
    @State private var handleCopied = false
    // The account card: copy-ID confirmation + sign-out dialog.
    @State private var idCopied = false
    @State private var signOutArmed = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                // PULSE EMBER (EMB-I): sunset ground; the content subtree
                // pins dark so adaptive tokens resolve onto it.
                Rectangle()
                    .fill(PulseTheme.emberBackdrop)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 14) {
                        profileTopChrome
                        identityBlock
                        statusBioBlock
                        statsRow
                        actionCapsules
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
                .environment(\.colorScheme, .dark)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(isPresented: $identitySheet) {
            IdentityPickerSheet(mode: .switcher, session: session, prefs: prefs) {}
        }
        .sheet(isPresented: $editProfileOpen) {
            ProfileEditView(session: session, prefs: prefs)
        }
        .sheet(isPresented: $savedOpen) {
            // R7 bonus - the EXISTING SavedLibraryView route (RootView mounts
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

    // ── top chrome (EMB-I) ───────────────────────────────────

    /// Glass ellipsis hosting the screen's existing actions (Edit profile,
    /// Saved messages, Switch identity). The reference's back chevron is a
    /// pushed-page affordance; this tab is a root with no back stack, so
    /// only the honest controls render.
    private var profileTopChrome: some View {
        HStack(spacing: 10) {
            Spacer()
            Menu {
                Button {
                    PulseHaptics.tap()
                    editProfileOpen = true
                } label: {
                    Label("Edit profile", systemImage: "pencil")
                }
                Button {
                    PulseHaptics.tap()
                    savedOpen = true
                } label: {
                    Label("Saved messages", systemImage: "bookmark")
                }
                Button {
                    PulseHaptics.tap()
                    identitySheet = true
                } label: {
                    Label("Switch identity", systemImage: "person.2")
                }
            } label: {
                EmberGlassCircleIcon(systemImage: "ellipsis")
            }
            .accessibilityLabel("More options")
        }
    }

    // ── identity (web reference profile: centered column) ────

    /// Centered 96pt avatar with the amber presence dot, bold name, and
    /// the handle line at 13pt white 55%.
    private var identityBlock: some View {
        VStack(spacing: 8) {
            avatarBadge
            HStack(spacing: 6) {
                Text(prefs.viewer?.name ?? "No identity")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                // Quiet trust mark (web PulseSeal), ember tint.
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(PulseTheme.emberGlowTop)
                    .accessibilityLabel("Registered member")
            }
            handleLine
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }

    /// 96pt avatar; online renders the #22C55E dot ringed in the ground.
    private var avatarBadge: some View {
        PulseAvatar(
            name: prefs.viewer?.name ?? "You",
            color: PulseTheme.color(named: prefs.viewer?.color),
            photoURL: PulseTheme.photoURL(prefs.viewer?.avatar),
            online: session.isOnline(prefs.viewer?.id ?? ""),
            size: 96,
        )
        .overlay(alignment: .bottomTrailing) {
            if session.isOnline(prefs.viewer?.id ?? "") {
                Circle()
                    .fill(PulseTheme.emberOnline)
                    .frame(width: 18, height: 18)
                    .overlay(Circle().strokeBorder(PulseTheme.emberDotRing, lineWidth: 3))
                    .offset(x: 2, y: 2)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityLabel(session.isOnline(prefs.viewer?.id ?? "") ? "Online" : "Offline")
    }

    /// @handle when it exists (tap to copy - the chip behavior moved into
    /// the label itself), otherwise the honest placeholder.
    private var handleLine: some View {
        Button {
            copyHandle()
        } label: {
            HStack(spacing: 5) {
                Image(systemName: handleCopied ? "checkmark" : "at")
                    .font(.system(size: 11, weight: .bold))
                Text(handleCopied ? "Copied" : (prefs.viewer?.username.map { "@\($0)" } ?? "Set your handle"))
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .lineLimit(1)
            }
            .foregroundStyle(Color.white.opacity(0.55))
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(Capsule().fill(Color.white.opacity(0.06)))
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(handleCopied ? "Handle copied" : "Copy handle")
    }

    /// Status + bio at 13pt (existing prefs values verbatim).
    private var statusBioBlock: some View {
        VStack(spacing: 6) {
            // R18-b - stored value is an icon ID (unknown -> registry default).
            if let emoji = prefs.viewerStatusEmoji, !emoji.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: (PulseStatusIconId.normalize(emoji) ?? PulseStatusIconId.fallback).symbolName)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(PulseTheme.emberGlowTop)
                        .accessibilityLabel((PulseStatusIconId.normalize(emoji) ?? PulseStatusIconId.fallback).label)
                    if let text = prefs.viewerStatusText, !text.isEmpty {
                        Text(text)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color.white.opacity(0.85))
                            .lineLimit(1)
                    }
                }
            } else if let text = prefs.viewerStatusText, !text.isEmpty {
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .lineLimit(1)
            }
            Text(prefs.viewerAbout?.isEmpty == false ? prefs.viewerAbout! : "No bio yet")
                .font(.system(size: 13))
                .foregroundStyle(prefs.viewerAbout?.isEmpty == false ? Color.white.opacity(0.55) : Color.white.opacity(0.40))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
    }

    // ── stats (one row of three ember cards) ─────────────────

    /// Messages / Chats / Coins - real data only, the SAME loaders as
    /// before (GET /api/users/{id}/stats + GET /api/hub/wallet); failures
    /// render the honest dash, loads render the quiet interpunct.
    private var statsRow: some View {
        HStack(spacing: 10) {
            statCard(label: "Messages", value: statsValueText)
            statCard(label: "Chats", value: roomsValueText)
            statCard(label: "Coins", value: coinsValueText)
        }
    }

    private func statCard(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.50))
                .lineLimit(1)
            Text(value)
                .font(.system(size: 16, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.05)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value)")
    }

    private var statsValueText: String {
        guard let stats else { return statsFailed ? "-" : "·" }
        return (stats.messages ?? 0).formatted()
    }

    private var roomsValueText: String {
        guard let stats else { return statsFailed ? "-" : "·" }
        return (stats.chats ?? 0).formatted()
    }

    private var coinsValueText: String {
        switch walletPhase {
        case .loading: return "·"
        case .failed: return "-"
        case .loaded: return walletCoins.formatted()
        }
    }

    /// Quick actions - one signal, one ghost. Share rides the existing
    /// ShareLink deep-link message; no handle → honest inert ghost.
    private var actionCapsules: some View {
        HStack(spacing: 10) {
            Button {
                editProfileOpen = true
            } label: {
                Label("Edit profile", systemImage: "pencil")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .frame(height: 38)
                    .background(Capsule().fill(PulseTheme.emberSignalGradient))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Edit profile")

            if let handle = prefs.viewer?.username, !handle.isEmpty {
                ShareLink(item: Self.shareMessage(handle: handle, userId: prefs.viewer?.id)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .frame(height: 38)
                        .background(Capsule().fill(Color.white.opacity(0.08)))
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
                }
                .accessibilityLabel("Share profile, find me on Pulse at \(handle)")
            } else {
                Label("Claim a @handle to share", systemImage: "square.and.arrow.up")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.40))
                    .padding(.horizontal, 16)
                    .frame(height: 38)
                    .background(Capsule().fill(Color.white.opacity(0.08)))
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
            }
        }
    }

    // ── data loads (unchanged behavior) ──────────────────────

    /// The @handle tap → clipboard + haptic (web copyHandle). No handle →
    /// opens the editor (web opens the handle sheet).
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

    /// Account ID tap → clipboard + haptic (web copyId parity).
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

    /// Sign out, mirroring the IdentityPickerSheet forget flow: session
    /// teardown first, then setViewer(nil) which wipes the stored viewer +
    /// identity-bound token; RootView flips to onboarding.
    private func signOut() {
        PulseHaptics.tap()
        session.stop()
        prefs.setViewer(nil)
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

    // ── account card ─────────────────────────────────────────

    /// Saved messages in a quiet ember card (web ProfileSection "Saved").
    private var savedCard: some View {
        Button {
            savedOpen = true
        } label: {
            HStack(spacing: 12) {
                emberRowIcon("star.fill")
                Text("Saved messages")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.30))
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Saved messages")
        .emberCard()
    }

    /// Copy account ID / Switch identity / Sign out - 52pt rows, white 80%
    /// symbols, existing actions byte-preserved.
    private var accountCard: some View {
        VStack(spacing: 0) {
            Button {
                copyAccountId()
            } label: {
                HStack(spacing: 12) {
                    emberRowIcon("touchid")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(idCopied ? "Account ID copied" : "Copy account ID")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.white)
                        Text(prefs.viewer?.id ?? "")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(Color.white.opacity(0.40))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                    Image(systemName: idCopied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(idCopied ? PulseTheme.emberGlowTop : Color.white.opacity(0.30))
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(idCopied ? "Account ID copied" : "Copy account ID")

            cardSeparator

            Button {
                identitySheet = true
            } label: {
                HStack(spacing: 12) {
                    emberRowIcon("person.2")
                    Text("Switch identity")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.30))
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 52)
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
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(PulseTheme.emberRed)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Sign out")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(PulseTheme.emberRed)
                        Text("Return to the welcome screen. Nothing is deleted.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(Color.white.opacity(0.40))
                    }
                    Spacer()
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Sign out")
        }
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.05)))
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
            emberCardHeader("Wallet")
            Button {
                // A failed fetch is honest - tap retries (web refetch parity).
                guard walletPhase == .failed else { return }
                walletPhase = .loading
                Task { await loadWallet() }
            } label: {
                HStack(spacing: 12) {
                    emberRowIcon("bitcoinsign.circle")
                    Text("Coin balance")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                    Spacer()
                    switch walletPhase {
                    case .loading:
                        ProgressView().controlSize(.small).tint(.white)
                    case .failed:
                        Text("-")
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(Color.white.opacity(0.40))
                    case .loaded:
                        Text("\(walletCoins)")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white)
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
                .foregroundStyle(Color.white.opacity(0.40))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .emberCard()
    }

    /// F-CP-09 - the viewer's status emoji + text, live from prefs.
    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            emberCardHeader("Status")
            // R18-b - stored value is an icon ID (unknown -> registry default).
            if let emoji = prefs.viewerStatusEmoji, !emoji.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: (PulseStatusIconId.normalize(emoji) ?? PulseStatusIconId.fallback).symbolName)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(PulseTheme.emberGlowTop)
                        .accessibilityLabel((PulseStatusIconId.normalize(emoji) ?? PulseStatusIconId.fallback).label)
                    Text(prefs.viewerStatusText ?? "")
                        .font(.system(size: 13))
                        .foregroundStyle(Color.white.opacity(0.55))
                        .lineLimit(1)
                }
            } else if let text = prefs.viewerStatusText, !text.isEmpty {
                Text(text)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white.opacity(0.55))
            } else {
                Text("No status set")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white.opacity(0.40))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .emberCard()
    }

    /// Appearance - mode picker + the ambient FX strip (unchanged behavior;
    /// the previews are the real Metal/Canvas modes).
    private var appearanceCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            emberCardHeader("Appearance")
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
                    .foregroundStyle(.white)
                Text("The web's WebGL modes, rebuilt with Metal + Canvas - same palette, same physics.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color.white.opacity(0.40))
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
        .emberCard()
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
                            .strokeBorder(selected ? PulseTheme.emberGlowTop : Color.white.opacity(0.10), lineWidth: selected ? 2.5 : 1),
                    )
                Text(mode.label)
                    .font(.caption.weight(selected ? .bold : .medium))
                    .foregroundStyle(selected ? PulseTheme.emberGlowTop : Color.white.opacity(0.40))
            }
        }
        .buttonStyle(PulseButtonStyle())
    }

    /// Motion note (unchanged copy - Reduce Motion is honored system-wide).
    private var motionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            emberCardHeader("Motion")
            Label("Respects system Reduce Motion", systemImage: "figure.mind.and.body")
                .font(.system(size: 12.5))
                .foregroundStyle(Color.white.opacity(0.55))
            Text("Particles, springs and the ambient loop all render a single static frame when Reduce Motion is on.")
                .font(.system(size: 11.5))
                .foregroundStyle(Color.white.opacity(0.40))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .emberCard()
    }

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            emberCardHeader("About")
            aboutLine(label: "Version", value: "1.0.0-native")
            aboutLine(label: "Stack", value: "SwiftUI · GRDB · Socket.IO")
            Text("Rebuilt natively against the same live gateway as the web app - chats, rooms, reactions, typing and presence are real.")
                .font(.system(size: 11.5))
                .foregroundStyle(Color.white.opacity(0.40))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .emberCard()
    }

    private func aboutLine(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.80))
            Spacer()
            Text(value)
                .font(.system(size: 13))
                .foregroundStyle(Color.white.opacity(0.55))
        }
    }

    // ── ember card primitives (EMB-I) ────────────────────────

    /// Small uppercase card header (web ProfileSection title).
    private func emberCardHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 11.5, weight: .bold))
            .foregroundStyle(Color.white.opacity(0.45))
            .tracking(0.8)
    }

    /// Quiet row glyph - white at 80%, no accent tile (ember language).
    private func emberRowIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 20, weight: .medium))
            .foregroundStyle(Color.white.opacity(0.80))
            .frame(width: 24)
    }

    private var cardSeparator: some View {
        Rectangle()
            .fill(Color.white.opacity(0.08))
            .frame(height: 1)
            .padding(.leading, 50)
    }

    /// R47 - the shared text: verbatim web copy (profile-tab.tsx:270) plus
    /// the pulse://user/{id} deep link when the viewer id exists (the app
    /// scheme registered in project.yml; PulseDeepLink routes it back in).
    static func shareMessage(handle: String, userId: String?) -> String {
        var text = "Find me on Pulse - @\(handle)"
        if let userId, !userId.isEmpty {
            text += "\n\(PulseDeepLink.scheme)://user/\(userId)"
        }
        return text
    }
}

import SwiftUI

/// More → Settings — the real app-level surface (replaces the honest toast).
/// Deliberately does NOT duplicate ProfileView's identity/appearance/ambient
/// sections: this screen owns app + connection + chats defaults. Every row is
/// real — the gateway probe actually hits the API, the version reads the
/// bundle, the filter picker writes PulsePrefs.
struct SettingsView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme

    @State private var probing = false
    @State private var probeResult: ProbeResult?

    private enum ProbeResult: Equatable {
        case ok(String)
        case fail(String)
    }

    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    private var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }

    private var gatewayHost: String {
        PulseEndpoints.gatewayURL.host ?? PulseEndpoints.gatewayURL.absoluteString
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 14) {
                        aboutCard
                        connectionCard
                        chatsCard
                        updatesCard
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 28)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Settings").font(.headline)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    // ── cards ────────────────────────────────────────────────

    private var aboutCard: some View {
        settingsCard(title: "App", icon: "app.badge.fill") {
            settingsRow(icon: "number", label: "Version", value: "\(version) (\(build))")
            settingsRow(icon: "ship", label: "Platform", value: "iOS · native Swift")
            settingsRow(icon: "gearshape.2", label: "Bundle", value: "app.pulse.chat")
        }
    }

    private var connectionCard: some View {
        settingsCard(title: "Connection", icon: "antenna.radiowaves.left.and.right") {
            settingsRow(icon: "network", label: "Gateway", value: gatewayHost)
            settingsRow(
                icon: "bolt",
                label: "Realtime",
                value: PulseEndpoints.socketURL == nil ? "Offline-first build" : "Socket.IO live",
            )
            Button {
                probe()
            } label: {
                HStack {
                    if probing {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(PulseTheme.accent)
                    }
                    Text(probing ? "Probing gateway…" : "Check gateway now")
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.accent)
                    Spacer()
                    if let probeResult {
                        switch probeResult {
                        case .ok:
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(PulseTheme.emerald)
                        case .fail:
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(probing)

            if let probeResult {
                Text(probeText)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            }
        }
    }

    private var probeText: String {
        switch probeResult {
        case .ok(let detail): return detail
        case .fail(let detail): return detail
        case nil: return ""
        }
    }

    private var chatsCard: some View {
        settingsCard(title: "Chats", icon: "bubble.left.and.bubble.right.fill") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Default list filter")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Picker("Default filter", selection: Binding(
                    get: { prefs.chatsFilter },
                    set: { prefs.setChatsFilter($0) },
                )) {
                    ForEach(PulsePrefs.ChatsFilter.allCases, id: \.self) { filter in
                        Text(filterLabel(filter)).tag(filter)
                    }
                }
                .pickerStyle(.segmented)
                .tint(PulseTheme.emerald)
            }
            .padding(.vertical, 4)
            settingsRow(icon: "bookmark", label: "Note to Self", value: "More menu → Saved")
        }
    }

    private var updatesCard: some View {
        settingsCard(title: "Updates", icon: "arrow.down.circle.fill") {
            settingsRow(icon: "arrow.triangle.2.circlepath", label: "Channel", value: "TestFlight / App Store")
            Text("iOS updates ship through the App Store pipeline — in-place live updates are the Android build's superpower. This screen always shows the exact installed version above.")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .padding(.top, 2)
        }
    }

    // ── primitives ───────────────────────────────────────────

    private func filterLabel(_ filter: PulsePrefs.ChatsFilter) -> String {
        switch filter {
        case .all: return "All"
        case .unread: return "Unread"
        case .groups: return "Groups"
        }
    }

    private func settingsCard<Content: View>(
        title: String,
        icon: String,
        @ViewBuilder content: () -> Content,
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: icon)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(PulseTheme.accent)
                .textCase(.uppercase)
            VStack(alignment: .leading, spacing: 4) {
                content()
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cardPanel)
        .accessibilityElement(children: .contain)
    }

    private var cardPanel: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(systemScheme == .dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.7), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.08), radius: 12, y: 5)
    }

    private func settingsRow(icon: String, label: String, value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 22)
            Text(label)
                .font(.system(size: 14.5, weight: .medium))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Spacer()
            Text(value)
                .font(.system(size: 13.5, weight: .semibold, design: .rounded))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .frame(minHeight: 40)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(value)")
    }

    // ── actions ──────────────────────────────────────────────

    private func probe() {
        guard !probing else { return }
        probing = true
        probeResult = nil
        Task {
            let started = Date()
            do {
                _ = try await session.api.users()
                let ms = Int(Date().timeIntervalSince(started) * 1000)
                withAnimation { probeResult = .ok("Reachable — identities endpoint answered in \(ms) ms.") }
            } catch {
                withAnimation {
                    probeResult = .fail("No answer — the offline-first store keeps your chats readable meanwhile.")
                }
            }
            probing = false
        }
    }
}

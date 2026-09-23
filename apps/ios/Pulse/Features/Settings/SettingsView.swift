import SwiftUI

/// More → Settings — the real app-level surface. Wave 8 extends it to the
/// web's section registry (settings-screen.tsx SECTION_MAP, labels verbatim):
/// Account · Appearance · Chat · Notifications · Privacy & Security ·
/// Real-time & Voice · Accessibility · Data & Storage · About. Every control
/// is real: the appearance/chat/privacy toggles optimistic-apply locally and
/// PATCH /api/settings, quiet hours gate incoming pings + haptics locally,
/// the gateway probe hits the API, drafts/outbox read the GRDB tables, the
/// footprint measures the real SQLite file.
struct SettingsView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    @State private var probing = false
    @State private var probeResult: ProbeResult?
    @State private var serverField = PulseEndpoints.configuredBase ?? ""
    // Wave 6 — blocked accounts (F-CP-05, P-SE-05).
    @State private var blockedOpen = false
    // Wave 8 — new section surfaces + local UI state.
    @State private var draftsOutboxOpen = false
    @State private var editProfileOpen = false
    @State private var draftCount = 0
    @State private var queuedCount = 0
    @State private var copiedUserId = false
    @State private var footprintBytes: Int64?

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
        PulseEndpoints.configuredBase
            ?? PulseEndpoints.gatewayURL.host
            ?? PulseEndpoints.gatewayURL.absoluteString
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 14) {
                        accountCard
                        appearanceCard
                        chatCard
                        notificationsCard
                        privacyCard
                        realtimeCard
                        accessibilityCard
                        dataCard
                        aboutCard
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
        .sheet(isPresented: $blockedOpen) {
            BlockedListView(session: session)
        }
        .sheet(isPresented: $draftsOutboxOpen) {
            DraftsOutboxManagerView(session: session)
        }
        .sheet(isPresented: $editProfileOpen) {
            ProfileEditView(session: session, prefs: prefs)
        }
        .onAppear { refreshLocalCounts() }
        .onChange(of: draftsOutboxOpen) { _, open in
            if !open { refreshLocalCounts() }
        }
    }

    // ── Account ──────────────────────────────────────────────

    private var accountCard: some View {
        settingsCard(title: "Account", icon: "person.crop.circle.fill") {
            HStack(spacing: 12) {
                PulseAvatar(
                    name: prefs.viewer?.name ?? "You",
                    color: PulseTheme.color(named: prefs.viewer?.color),
                    photoURL: PulseTheme.photoURL(prefs.viewer?.avatar),
                    size: 52,
                )
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(prefs.viewer?.name ?? "Signed out")
                            .font(.system(size: 15.5, weight: .bold))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                            .lineLimit(1)
                        if prefs.viewer != nil {
                            Text("You")
                                .font(.system(size: 9.5, weight: .bold))
                                .tracking(0.5)
                                .foregroundStyle(PulseTheme.accent)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(PulseTheme.accent.opacity(0.12)))
                        }
                    }
                    Text(prefs.viewer?.username.map { "@\($0)" } ?? "No handle yet")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.secondary)
                    if let statusLine, !statusLine.isEmpty {
                        Text(statusLine)
                            .font(.system(size: 12))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }
            .accessibilityElement(children: .combine)

            Button {
                PulseHaptics.tap()
                editProfileOpen = true
            } label: {
                settingsLinkRow(icon: "pencil", label: "Edit profile", caption: "Name, handle, status and avatar — in the Profile tab", value: "Open")
            }
            .buttonStyle(.plain)

            // User ID — UIPasteboard copy with an inline confirmation
            // (the app toast lives under the sheet, so it shows here).
            HStack(spacing: 10) {
                Image(systemName: "number")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.secondary)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 1) {
                    Text("User ID")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Text(prefs.viewer?.id ?? "—")
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
                Button {
                    copyUserId()
                } label: {
                    Label(copiedUserId ? "Copied" : "Copy", systemImage: copiedUserId ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(copiedUserId ? PulseTheme.emerald : PulseTheme.accent)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 32)
                        .background(Capsule().fill(PulseTheme.accent.opacity(0.10)))
                }
                .buttonStyle(.plain)
                .disabled(prefs.viewer == nil)
                .accessibilityLabel("Copy user ID")
            }
            .frame(minHeight: 44)

            settingsRow(
                icon: "iphone",
                label: "Session scope",
                value: prefs.viewer == nil ? "None" : "Active on this device",
            )
        }
    }

    private var statusLine: String? {
        let emoji = prefs.viewerStatusEmoji ?? ""
        let text = prefs.viewerStatusText ?? ""
        let combined = "\(emoji) \(text)".trimmingCharacters(in: .whitespaces)
        if !combined.isEmpty { return combined }
        return nil
    }

    private func copyUserId() {
        guard let id = prefs.viewer?.id else { return }
        UIPasteboard.general.string = id
        PulseHaptics.success()
        copiedUserId = true
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            copiedUserId = false
        }
    }

    // ── Appearance ───────────────────────────────────────────

    private var appearanceCard: some View {
        settingsCard(title: "Appearance", icon: "paintpalette.fill") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Light / dark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Picker("Color mode", selection: Binding(
                    get: { prefs.appearance },
                    set: { prefs.setAppearance($0) },
                )) {
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                    Text("System").tag("system")
                }
                .pickerStyle(.segmented)
                .tint(PulseTheme.emerald)
            }
            .padding(.vertical, 4)

            // R2-D — the five locked design languages (web ui-theme.ts R25
            // parity; the selection persists under the web's exact
            // `pulse.uiTheme.v2` key and re-skins every PulseTheme-fed token).
            VStack(alignment: .leading, spacing: 8) {
                Text("Design language")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("One of five locked looks — \(PulseUiTheme.meta(for: prefs.uiTheme).detail)")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                    ForEach(PulseUiTheme.allMeta(), id: \.id) { themeMeta in
                        uiThemeSwatch(themeMeta)
                    }
                }
            }
            .padding(.vertical, 4)

            VStack(alignment: .leading, spacing: 8) {
                Text("Chat wallpaper")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Background behind every chat room — currently \(prefs.wallpaper.label).")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    ForEach(PulseWallpaper.allCases, id: \.self) { token in
                        wallpaperSwatch(token)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func wallpaperSwatch(_ token: PulseWallpaper) -> some View {
        let selected = prefs.wallpaper == token
        return Button {
            PulseHaptics.tap()
            prefs.setWallpaper(token)
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(token.wash(dark: systemScheme == .dark) ?? LinearGradient(colors: [PulseTheme.zinc(100), PulseTheme.zinc(100)], startPoint: .top, endPoint: .bottom))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(
                                    selected ? PulseTheme.emerald : (systemScheme == .dark ? Color.white.opacity(0.14) : PulseTheme.zinc(200)),
                                    lineWidth: selected ? 2 : 1,
                                ),
                        )
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(PulseTheme.emerald600)
                    }
                }
                .frame(height: 44)
                .frame(maxWidth: .infinity)
                Text(token.label)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(selected ? PulseTheme.emerald : .secondary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(token.label) wallpaper")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// R2-D — one design-language card: diagonal accent-pair swatch
    /// (web swatch order preserved) + label, selected ring like the
    /// wallpaper swatches.
    private func uiThemeSwatch(_ themeMeta: PulseUiThemeMeta) -> some View {
        let selected = prefs.uiTheme == themeMeta.id
        let first = PulseUiThemeColor(hex: themeMeta.swatch.first ?? "#10b981").color
        let second = PulseUiThemeColor(hex: themeMeta.swatch.count > 1 ? themeMeta.swatch[1] : themeMeta.swatch.first ?? "#0ea5e9").color
        return Button {
            PulseHaptics.tap()
            prefs.setUiTheme(themeMeta.id)
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [first, second],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing,
                            ),
                        )
                        .frame(height: 44)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(
                                    selected ? PulseTheme.emerald : (systemScheme == .dark ? Color.white.opacity(0.14) : PulseTheme.zinc(200)),
                                    lineWidth: selected ? 2 : 1,
                                ),
                        )
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                            .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
                    }
                }
                Text(themeMeta.label)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(selected ? PulseTheme.emerald : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(themeMeta.label) design language — \(themeMeta.tagline)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // ── Chat ─────────────────────────────────────────────────

    private var chatCard: some View {
        settingsCard(title: "Chat", icon: "bubble.left.and.bubble.right.fill") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Bubble corners")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Picker("Bubble corner radius", selection: Binding(
                    get: { prefs.bubbleRadius },
                    set: { prefs.setBubbleRadius($0) },
                )) {
                    ForEach(PulseBubbleRadius.allCases, id: \.self) { token in
                        Text(token.label).tag(token)
                    }
                }
                .pickerStyle(.segmented)
                .tint(PulseTheme.emerald)
            }
            .padding(.vertical, 4)

            VStack(alignment: .leading, spacing: 8) {
                Text("Message density")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Picker("Message density", selection: Binding(
                    get: { prefs.density },
                    set: { prefs.setDensity($0) },
                )) {
                    ForEach(PulseDensity.allCases, id: \.self) { token in
                        Text(token.label).tag(token)
                    }
                }
                .pickerStyle(.segmented)
                .tint(PulseTheme.emerald)
            }
            .padding(.vertical, 4)

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

            Button {
                PulseHaptics.tap()
                draftsOutboxOpen = true
            } label: {
                settingsLinkRow(
                    icon: "doc.text",
                    label: "Drafts & outbox",
                    caption: "Per-conversation drafts + the offline send queue",
                    value: draftCount + queuedCount > 0 ? "\(draftCount + queuedCount)" : "Empty",
                )
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Drafts and outbox manager")
        }
    }

    // ── Notifications ────────────────────────────────────────

    private var notificationsCard: some View {
        settingsCard(title: "Notifications", icon: "bell.fill") {
            toggleRow(icon: "eye", title: "Message previews", description: "Show message text in in-app alert toasts.", isOn: prefs.notifPreviews) {
                prefs.setNotifPreviews($0)
            }
            toggleRow(icon: "bell", title: "Message pop", description: "Soft pop for incoming messages.", isOn: prefs.notifSound) {
                prefs.setNotifSound($0)
            }
            toggleRow(icon: "iphone.radiowaves.left.and.right", title: "Vibration", description: "Buzz on incoming messages.", isOn: prefs.notifVibrate) {
                prefs.setNotifVibrate($0)
            }

            Divider().padding(.vertical, 4)

            // Wave 8 — LOCAL quiet hours (pulse.settings.v1 parity):
            // silences incoming sounds/vibration/preview toasts in-window.
            toggleRow(icon: "moon.stars.fill", title: "Quiet hours", description: "Silence sounds and vibration inside the window.", isOn: prefs.quietHoursOn) {
                prefs.setQuietHoursOn($0)
            }
            if prefs.quietHoursOn {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("From")
                            .font(.system(size: 10.5, weight: .bold))
                            .tracking(0.8)
                            .foregroundStyle(.tertiary)
                        DatePicker("Quiet hours start", selection: quietStartBinding, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Until")
                            .font(.system(size: 10.5, weight: .bold))
                            .tracking(0.8)
                            .foregroundStyle(.tertiary)
                        DatePicker("Quiet hours end", selection: quietEndBinding, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                }
                settingsRow(
                    icon: "moon.stars",
                    label: "Window status",
                    value: prefs.isQuietHoursNow ? "Active now" : "Idle",
                )
                Text("\(prefs.quietStart) → \(prefs.quietEnd) — overnight windows are supported.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }

            Divider().padding(.vertical, 4)

            Button {
                // Preview alert — honors the toggles above exactly like the
                // incoming path (quiet hours included).
                if !prefs.isQuietHoursNow {
                    if prefs.notifSound { PulseSounds.incoming() }
                    if prefs.notifVibrate { PulseHaptics.incoming() }
                }
            } label: {
                Label("Preview alert", systemImage: "play")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    .frame(maxWidth: .infinity, minHeight: 42)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PulseTheme.accent.opacity(0.10)))
            }
            .buttonStyle(.plain)
            Text("Plays the incoming pop and fires a buzz, honoring the toggles above and quiet hours.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
        }
    }

    private var quietStartBinding: Binding<Date> {
        Binding(
            get: { Self.date(fromHHmm: prefs.quietStart) },
            set: { prefs.setQuietStart(Self.hhmm(from: $0)) },
        )
    }

    private var quietEndBinding: Binding<Date> {
        Binding(
            get: { Self.date(fromHHmm: prefs.quietEnd) },
            set: { prefs.setQuietEnd(Self.hhmm(from: $0)) },
        )
    }

    /// "HH:mm" ↔ Date (today, local calendar) for the hourAndMinute pickers.
    static func date(fromHHmm value: String) -> Date {
        let minutes = PulseWave8Logic.minutesOf(value)
        var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        components.hour = minutes / 60
        components.minute = minutes % 60
        return Calendar.current.date(from: components) ?? Date()
    }

    static func hhmm(from date: Date) -> String {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        let hour = components.hour ?? 0
        let minute = components.minute ?? 0
        return String(format: "%02d:%02d", hour, minute)
    }

    // ── Privacy & Security ───────────────────────────────────

    private var privacyCard: some View {
        settingsCard(title: "Privacy & Security", icon: "shield.checkerboard") {
            toggleRow(icon: "eye", title: "Last seen & online", description: "Let people see when you were last active or online.", isOn: prefs.lastSeenVisible) {
                prefs.setLastSeenVisible($0)
            }
            toggleRow(icon: "checkmark.circle", title: "Read receipts", description: "Show others when you've read their messages.", isOn: prefs.readReceipts) {
                prefs.setReadReceipts($0)
            }
            toggleRow(icon: "pencil.line", title: "Typing indicator", description: "Show others when you're typing.", isOn: prefs.typingVisible) {
                prefs.setTypingVisible($0)
            }

            Divider().padding(.vertical, 4)

            Button {
                PulseHaptics.tap()
                blockedOpen = true
            } label: {
                settingsLinkRow(icon: "person.badge.minus", label: "Blocked accounts", caption: "Blocked accounts cannot message you in direct chats — the server enforces the boundary.", value: "Open")
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Blocked accounts — open the blocked list")

            Text("These sync to your Pulse account and are enforced server-side — hidden last-seen also hides your online status, hidden typing ends the relay before it reaches anyone, and blocked accounts cannot DM you.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
        }
    }

    // ── Real-time & Voice ────────────────────────────────────

    private var realtimeCard: some View {
        settingsCard(title: "Real-time & Voice", icon: "dot.radiowaves.left.and.right") {
            settingsRow(
                icon: "network",
                label: "Gateway",
                value: PulseEndpoints.configuredBase == nil ? "Not set — offline-first" : gatewayHost,
            )
            settingsRow(
                icon: "bolt",
                label: "Realtime socket",
                value: session.connected ? "Live" : (PulseEndpoints.socketURL == nil ? "Offline" : "Reconnecting"),
            )
            settingsRow(
                icon: "person.2",
                label: "People online now",
                value: "\(session.onlineUserIds.count)",
            )
            VStack(alignment: .leading, spacing: 6) {
                Text("Server address")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                TextField("https://your-pulse-server", text: $serverField)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .font(.system(size: 14, design: .monospaced))
                Text("Paste the origin of your Pulse web server. Applied on next launch; Test probes it right away.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                HStack(spacing: 18) {
                    Button("Test") { probe(hostOverride: serverField) }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(PulseTheme.accent)
                        .disabled(probing)
                    Button("Save") {
                        PulseEndpoints.configuredBase = serverField.isEmpty ? nil : serverField
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    if PulseEndpoints.configuredBase != nil {
                        Button("Go offline", role: .destructive) {
                            PulseEndpoints.configuredBase = nil
                            serverField = ""
                        }
                        .font(.system(size: 14, weight: .semibold))
                    }
                    Spacer()
                }
            }
            .padding(.vertical, 4)
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

            settingsRow(
                icon: "mic",
                label: "Voice rooms",
                value: session.voiceRooms == nil ? "Idle" : "Ready",
            )
            Text("Studio capture with echo cancellation, noise suppression and auto gain. Quality presets are not configurable yet.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
        }
    }

    private var probeText: String {
        switch probeResult {
        case .ok(let detail): return detail
        case .fail(let detail): return detail
        case nil: return ""
        }
    }

    // ── Accessibility ────────────────────────────────────────

    private var accessibilityCard: some View {
        settingsCard(title: "Accessibility", icon: "accessibility") {
            toggleRow(icon: "accessibility", title: "Reduced motion", description: "Calm the interface — instant transitions, no ambient particles or message effects.", isOn: prefs.reducedMotion) {
                prefs.setReducedMotion($0)
            }
            settingsRow(
                icon: "desktopcomputer",
                label: "System preference",
                value: systemReduceMotion ? "Reduce" : "Full",
            )
            Text(systemReduceMotion
                ? "Your OS asks apps to reduce motion — motion is reduced regardless of the toggle."
                : "When the OS Reduce Motion setting is on, motion is reduced regardless of the toggle above.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
            toggleRow(icon: "iphone.radiowaves.left.and.right", title: "Haptics", description: "Vibration on taps, sends and incoming alerts.", isOn: prefs.hapticsOn) {
                prefs.setHapticsOn($0)
            }
        }
    }

    // ── Data & Storage ───────────────────────────────────────

    private var dataCard: some View {
        settingsCard(title: "Data & Storage", icon: "externaldrive.fill") {
            settingsRow(
                icon: "cylinder",
                label: "Local database",
                value: footprintText,
            )
            Text("The offline store (GRDB/SQLite) keeps conversations, drafts, the outbox, call history and stories readable without a network.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)

            Divider().padding(.vertical, 4)

            Button {
                PulseHaptics.tap()
                clearAllDrafts()
            } label: {
                actionRow(icon: "doc.text", title: "Clear drafts", caption: draftCount > 0 ? "\(draftCount) saved on this device — clearing frees their storage." : "Empty — nothing to clear.", actionLabel: "Clear", disabled: draftCount == 0)
            }
            .buttonStyle(.plain)
            .disabled(draftCount == 0)

            Button {
                PulseHaptics.tap()
                clearOutbox()
            } label: {
                actionRow(icon: "cloud.slash", title: "Discard offline queue", caption: queuedCount > 0 ? "\(queuedCount) waiting — discarding drops them without sending." : "Empty — nothing queued right now.", actionLabel: "Discard", disabled: queuedCount == 0)
            }
            .buttonStyle(.plain)
            .disabled(queuedCount == 0)

            settingsRow(icon: "arrow.triangle.2.circlepath", label: "Updates", value: "App Store pipeline")
            Text("iOS updates ship through the App Store pipeline — in-place live updates are the Android build's superpower. This screen always shows the exact installed version below.")
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
        }
    }

    private var footprintText: String {
        guard let bytes = footprintBytes else { return "—" }
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    // ── About ────────────────────────────────────────────────

    private var aboutCard: some View {
        settingsCard(title: "About", icon: "app.badge.fill") {
            settingsRow(icon: "number", label: "Version", value: "\(version) (\(build))")
            settingsRow(icon: "ship", label: "Platform", value: "iOS · native Swift")
            settingsRow(icon: "gearshape.2", label: "Bundle", value: "app.pulse.chat")
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
            // Wave 8 — the honest sync hint: optimistic toggles stay local
            // when the PATCH can't reach the server (server value wins on
            // the next successful fetch instead).
            if let note = prefs.lastSyncNote {
                Label(note, systemImage: "icloud.slash")
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(PulseTheme.amber)
                    .padding(.top, 2)
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

    /// Row with caption under the label (web ToggleRow/PickerRow shape) —
    /// used inside buttons so the whole row stays tappable.
    private func settingsLinkRow(icon: String, label: String, caption: String, value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text(caption)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            Text(value)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label) — \(value)")
    }

    private func toggleRow(
        icon: String,
        title: String,
        description: String,
        isOn: Bool,
        onChange: @escaping (Bool) -> Void,
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text(description)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { isOn }, set: { newValue in
                PulseHaptics.tap()
                onChange(newValue)
            }))
            .labelsHidden()
            .tint(PulseTheme.emerald)
            .fixedSize()
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title). \(description).")
        .accessibilityValue(isOn ? "On" : "Off")
    }

    private func actionRow(icon: String, title: String, caption: String, actionLabel: String, disabled: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text(caption)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            Text(actionLabel)
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(disabled ? Color.secondary : PulseTheme.accent)
                .padding(.horizontal, 12)
                .frame(minHeight: 32)
                .background(Capsule().fill(PulseTheme.accent.opacity(disabled ? 0.04 : 0.10)))
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) — \(actionLabel)")
    }

    // ── actions ──────────────────────────────────────────────

    private func refreshLocalCounts() {
        guard let store = session.store else { return }
        draftCount = (try? store.allDraftRows())?.count ?? 0
        queuedCount = store.countOutbox()
        footprintBytes = Self.databaseFootprint()
    }

    /// Wave 8 — the REAL GRDB footprint: pulse.sqlite plus its -wal/-shm
    /// siblings, straight from FileManager.
    static func databaseFootprint() -> Int64? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        var total: Int64 = 0
        var found = false
        for suffix in ["", "-wal", "-shm"] {
            let path = docs.appendingPathComponent("pulse.sqlite\(suffix)").path
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: path),
                  let size = attrs[.size] as? Int64 else { continue }
            found = true
            total += size
        }
        return found ? total : nil
    }

    private func clearAllDrafts() {
        guard let store = session.store else { return }
        let rows = (try? store.allDraftRows()) ?? []
        guard !rows.isEmpty else { return }
        PulseHaptics.warning()
        for row in rows {
            try? store.deleteDraft(conversationId: row.conversationId)
        }
        refreshLocalCounts()
    }

    private func clearOutbox() {
        guard let store = session.store else { return }
        let rows = (try? store.outboxAll()) ?? []
        guard !rows.isEmpty else { return }
        PulseHaptics.warning()
        for row in rows {
            try? store.deleteOutbox(clientId: row.clientId)
        }
        refreshLocalCounts()
    }

    private func probe(hostOverride: String? = nil) {
        guard !probing else { return }
        probing = true
        probeResult = nil
        Task {
            let started = Date()
            // The probe must honor the FIELD, not the frozen session base —
            // build a throwaway client against the candidate origin.
            let target: String
            if let hostOverride, !hostOverride.isEmpty {
                target = hostOverride.trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                target = PulseEndpoints.gatewayURL.absoluteString
            }
            let candidate = PulseAPIClient(baseURL: URL(string: target) ?? PulseEndpoints.gatewayURL)
            do {
                _ = try await candidate.users()
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

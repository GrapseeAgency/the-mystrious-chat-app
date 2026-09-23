import SwiftUI

// ─────────────────────────────────────────────────────────────
// R2-D ITEM 1 — the DM info surface (web header-menu parity,
// chat-room.tsx:4108-4287: "Manage chat" covers DMs with the SAME privacy
// entries groups get). Compact sheet presented from the dmPartner toolbar
// branch of ChatRoomView:
//   • disappearing-message TTL presets (0 · 24h · 7d · 30d, wire :2153-2171)
//   • screen security — personal veil + room-wide switch (R2-B paths reused)
//   • notification mute presets (8h · 1w · always + unmute, wire :2421-2455)
//   • per-chat theme entry (ConvThemeSheet, R1-W2B F-FX-05)
//   • safety-number entry (SafetySheetView, Wave 6 F-CP-07)
// GROUP behavior is untouched — GroupInfoView keeps serving group rooms.
// ─────────────────────────────────────────────────────────────

struct RoomInfoSheet: View {
    let conversation: WireConversationSummary
    let partner: WireConversationMember
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    /// R2-B parity — pushes the fresh room-wide veil flag back into the open
    /// room view model so the river re-veils without a refetch.
    var onDetailUpdated: ((WireConversationSummary) -> Void)? = nil

    @Environment(\.dismiss) private var dismiss

    enum Phase: Equatable { case loading, loaded, failed(String) }
    @State private var phase: Phase = .loading
    @State private var detail: WireConversationSummary?
    @State private var themeOpen = false
    @State private var safetyOpen = false

    private var viewerId: String { session.viewer?.id ?? "" }

    /// The DM display name — partner name, "Saved messages" on self-chats
    /// (the summary name is nil for DMs on the wire).
    private var displayName: String {
        if conversation.isSelf == true { return "Saved messages" }
        return partner.name
    }

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .loading:
                    ProgressView("Loading chat info…").frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    VStack(spacing: 10) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 26, weight: .light))
                            .foregroundStyle(.secondary)
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                        Button("Retry") { Task { await load() } }
                            .font(.footnote.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .loaded:
                    if let detail {
                        content(detail)
                    }
                }
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Chat info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task { await load() }
    }

    // ── data ─────────────────────────────────────────────────

    /// One quiet detail GET (GroupInfoView pattern): TTL, mute state and the
    /// room-wide veil flag all read from the fresh server truth.
    private func load() async {
        phase = detail == nil ? .loading : phase
        do {
            detail = try await session.api.conversationDetail(id: conversation.id, userId: viewerId)
            phase = .loaded
        } catch {
            if detail == nil { phase = .failed(RoomViewModel.describe(error)) }
        }
    }

    // ── content ──────────────────────────────────────────────

    private func content(_ detail: WireConversationSummary) -> some View {
        List {
            Section {
                HStack(spacing: 12) {
                    RowAvatar(
                        name: partner.name,
                        colorName: partner.color,
                        photoPath: partner.avatar,
                        size: 56,
                    )
                    VStack(alignment: .leading, spacing: 3) {
                        Text(displayName)
                            .font(.headline)
                            .lineLimit(1)
                        if partner.id == viewerId {
                            Text("You")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else if let handle = partner.username {
                            Text("@\(handle)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 2)
            }

            // ── disappearing messages (web header-menu TTL presets, chat-room
            // tsx:4223-4261 — Off · 24h · 7d · 30d) — the same ladder groups use.
            Section {
                ForEach(GroupInfoView.ttlPresets, id: \.seconds) { preset in
                    Button {
                        PulseHaptics.tap()
                        if preset.seconds != (detail.ttlSeconds ?? 0) {
                            setTtl(preset.seconds)
                        }
                    } label: {
                        HStack {
                            Label(preset.label, systemImage: "timer")
                            Spacer()
                            if (detail.ttlSeconds ?? 0) == preset.seconds {
                                Image(systemName: "checkmark")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(PulseTheme.emerald)
                            }
                        }
                    }
                    .disabled((detail.ttlSeconds ?? 0) == preset.seconds)
                }
            } header: {
                Text("Disappearing messages")
            } footer: {
                Text("New messages vanish after the selected time.")
            }

            // ── R2-D — notification mute presets (web :2419-2455 parity:
            // 8h · 1w · always, unmute while active). The optimistic summary
            // helper rides the SAME setMuted route as the chats-list sheet.
            Section {
                if detail.isMutedNow {
                    Button {
                        setMuted(nil)
                    } label: {
                        Label("Unmute notifications", systemImage: "bell")
                    }
                    Text("Muted until \(PulseFormat.listStamp(detail.mutedUntil)).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    mutePresetRow("Mute for 8 hours", preset: "8h")
                    mutePresetRow("Mute for 1 week", preset: "1w")
                    mutePresetRow("Mute always", preset: "always")
                }
            } header: {
                Text("Notifications")
            }

            // ── R2-D — screen security (the R2-B veil paths, reused verbatim
            // for DMs): the personal veil frosts MY view; the room-wide
            // switch frosts every member's view. Both OR inside the room.
            Section {
                Toggle(isOn: Binding(
                    get: { prefs.screenPrivacy[conversation.id] ?? false },
                    set: { setMyScreenPrivacy($0) },
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Screen security", systemImage: "eye.slash")
                            .font(.subheadline.weight(.medium))
                        Text("Blur messages when Pulse loses focus — just for you")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityLabel("Screen security for you")
                Toggle(isOn: Binding(
                    get: { detail.screenPrivacy ?? false },
                    set: { setRoomScreenPrivacy($0) },
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Screen security for everyone", systemImage: "shield.lefthalf.filled")
                            .font(.subheadline.weight(.medium))
                        Text("Applies to every member of this chat")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityLabel("Screen security for everyone")
            }

            // ── R2-D — theme + safety-number entries (web "Manage chat"
            // parity: the DM header menu reaches both).
            Section {
                Button {
                    PulseHaptics.tap()
                    themeOpen = true
                } label: {
                    Label("Chat theme", systemImage: "paintpalette")
                }
                Button {
                    PulseHaptics.tap()
                    safetyOpen = true
                } label: {
                    HStack {
                        Label("Safety number", systemImage: "shield.lefthalf.filled")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .sheet(isPresented: $themeOpen) {
            ConvThemeSheet(conversationId: conversation.id, prefs: prefs)
        }
        .sheet(isPresented: $safetyOpen) {
            SafetySheetView(session: session, peer: partner)
        }
    }

    private func mutePresetRow(_ label: String, preset: String) -> some View {
        Button {
            setMuted(preset)
        } label: {
            Label(label, systemImage: "bell.slash")
        }
    }

    // ── actions ──────────────────────────────────────────────

    /// PATCH disappearing { userId, ttlSeconds } — the exact call
    /// GroupInfoView makes; toast copy follows the web setTtl onSuccess.
    private func setTtl(_ seconds: Int) {
        Task {
            do {
                let fresh = try await session.api.setDisappearingTtl(conversation.id, userId: viewerId, ttlSeconds: seconds)
                detail = fresh
                session.toasts.show(
                    seconds == 0
                        ? "Disappearing messages off"
                        : "New messages vanish after \(GroupInfoView.ttlLabel(seconds).lowercased())",
                )
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// R2-D — mute presets through the real route (ChatsViewModel.setMuted
    /// parity: optimistic watermark, honest failure restores the old value).
    private func setMuted(_ preset: String?) {
        guard let current = detail else { return }
        let previous = current
        let offsets: [String: TimeInterval] = [
            "8h": 8 * 3600,
            "1w": 7 * 86_400,
            "always": 50 * 365 * 86_400,
        ]
        let optimistic: String?
        if let preset {
            let offset = offsets[preset] ?? 8 * 3600
            optimistic = ISO8601DateFormatter().string(from: Date().addingTimeInterval(offset))
        } else {
            optimistic = nil
        }
        detail = Self.withMuted(current, until: optimistic)
        Task {
            do {
                try await session.api.setMuted(conversationId: conversation.id, until: preset)
                if preset == nil {
                    session.toasts.show("Notifications unmuted")
                } else if preset == "always" {
                    session.toasts.show("Muted — always")
                } else {
                    let stamp = Date().addingTimeInterval(preset == "1w" ? 7 * 86_400 : 8 * 3_600)
                    session.toasts.show("Muted until \(PulseFormat.listStamp(stamp))")
                }
                await load()
            } catch {
                detail = previous
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// withMutedUntil resets the defaulted detail vars (they sit behind the
    /// memberwise init) — restore them so the veil/throttle state survives
    /// the optimistic mute swap.
    private static func withMuted(_ summary: WireConversationSummary, until: String?) -> WireConversationSummary {
        var fresh = summary.withMutedUntil(until)
        fresh.inviteCode = summary.inviteCode
        fresh.slowModeSeconds = summary.slowModeSeconds
        fresh.screenPrivacy = summary.screenPrivacy
        fresh.myScreenPrivacy = summary.myScreenPrivacy
        return fresh
    }

    /// R42 — MY veil (prefs write-through + per-viewer route mirror), the
    /// GroupInfoView path verbatim.
    private func setMyScreenPrivacy(_ on: Bool) {
        prefs.setScreenPrivacy(conversationId: conversation.id, on: on)
        Task {
            do {
                let confirmed = try await session.api.setMyScreenPrivacy(conversation.id, on: on)
                prefs.adoptServerScreenPrivacy(conversationId: conversation.id, on: confirmed)
                session.toasts.show(confirmed ? "Screen security on for you" : "Screen security off for you")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// R38 — the room-wide switch: PATCH conversation { screenPrivacy }, then
    /// push the fresh detail into the open room (onDetailUpdated) and the
    /// sheet's own state.
    private func setRoomScreenPrivacy(_ on: Bool) {
        Task {
            do {
                let fresh = try await session.api.patchConversation(
                    conversation.id,
                    requesterId: viewerId,
                    screenPrivacy: on,
                )
                detail = fresh
                onDetailUpdated?(fresh)
                session.toasts.show(on ? "Screen security on" : "Screen security off")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}

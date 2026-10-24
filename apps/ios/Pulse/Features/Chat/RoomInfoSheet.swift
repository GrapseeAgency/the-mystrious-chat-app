import SwiftUI

// R2-D ITEM 1 - the DM info surface (web header-menu parity,
// chat-room.tsx:4108-4287: "Manage chat" covers DMs with the SAME privacy
// entries groups get). Compact sheet presented from the dmPartner toolbar
// branch of ChatRoomView:
//   • disappearing-message TTL presets (0 · 24h · 7d · 30d, wire :2153-2171)
//   • screen security - personal veil + room-wide switch (R2-B paths reused)
//   • notification mute presets (8h · 1w · always + unmute, wire :2421-2455)
//   • per-chat theme entry (ConvThemeSheet, R1-W2B F-FX-05)
//   • safety-number entry (SafetySheetView, Wave 6 F-CP-07)
// GROUP behavior is untouched - GroupInfoView keeps serving group rooms.

struct RoomInfoSheet: View {
    let conversation: WireConversationSummary
    let partner: WireConversationMember
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    /// R2-B parity - pushes the fresh room-wide veil flag back into the open
    /// room view model so the river re-veils without a refetch.
    var onDetailUpdated: ((WireConversationSummary) -> Void)? = nil

    @Environment(\.dismiss) private var dismiss

    enum Phase: Equatable { case loading, loaded, failed(String) }
    @State private var phase: Phase = .loading
    @State private var detail: WireConversationSummary?
    @State private var themeOpen = false
    @State private var safetyOpen = false
    @State private var surfaceEpoch: UUID?
    @State private var loadTicket = UUID()
    @State private var loadTask: Task<Void, Never>?
    @State private var mutationGate = PulseMutationGate()
    @State private var mutationTask: Task<Void, Never>?
    private var busy: Bool { mutationGate.isBusy }

    private var viewerId: String { session.viewer?.id ?? "" }

    /// The DM display name - partner name, "Saved messages" on self-chats
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
                        Button("Retry") { reloadDetail() }
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
        .onAppear { surfaceEpoch = session.sessionGeneration; reloadDetail() }
        .onDisappear { retireSurfaceWork() }
        .onChange(of: session.sessionGeneration) { _, _ in retireSurfaceWork(); dismiss() }
    }

    // data

    /// One quiet detail GET (GroupInfoView pattern): TTL, mute state and the
    /// room-wide veil flag all read from the fresh server truth.
    private func owns(_ epoch: UUID) -> Bool {
        !Task.isCancelled && surfaceEpoch == epoch && session.sessionGeneration == epoch
    }
    private func reloadDetail() {
        let epoch = session.sessionGeneration
        guard owns(epoch) else { return }
        loadTask?.cancel(); loadTicket = UUID()
        loadTask = Task { @MainActor in
            guard owns(epoch) else { return }
            await load()
        }
    }
    private func load() async {
        let epoch = session.sessionGeneration, client = session.api, owner = viewerId, ticket = UUID()
        guard owns(epoch), !owner.isEmpty, prefs.viewer?.id == owner, client.userId == owner,
              PulseWalletOperations.gatewayKey(client.baseURL) == PulseWalletOperations.gatewayKey(PulseEndpoints.gatewayURL) else { return }
        loadTicket = ticket
        phase = detail == nil ? .loading : phase
        do {
            let fresh = try await client.conversationDetail(id: conversation.id, userId: owner)
            guard owns(epoch), prefs.viewer?.id == owner, loadTicket == ticket else { return }
            try acceptDetail(fresh)
            phase = .loaded
        } catch {
            guard owns(epoch), prefs.viewer?.id == owner, loadTicket == ticket else { return }
            if detail == nil { phase = .failed(RoomViewModel.describe(error)) }
        }
    }
    private func acceptDetail(_ fresh: WireConversationSummary) throws {
        guard fresh.id == conversation.id else {
            throw PulseAPIClient.Failure(kind: .server, message: "The response did not match this chat. Refresh chat info.")
        }
        detail = fresh
        onDetailUpdated?(fresh)
    }
    private func retireSurfaceWork() {
        surfaceEpoch = nil; loadTicket = UUID()
        loadTask?.cancel(); loadTask = nil
        mutationGate.cancel(); mutationTask?.cancel(); mutationTask = nil
    }

    // content

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

            // disappearing messages (web header-menu TTL presets, chat-room
            // tsx:4223-4261 - Off · 24h · 7d · 30d) - the same ladder groups use.
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

            // R2-D - notification mute presets (web :2419-2455 parity:
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

            // R2-D - screen security (the R2-B veil paths, reused verbatim
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
                        Text("Blur messages when Pulse loses focus - just for you")
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

            // R2-D - theme + safety-number entries (web "Manage chat"
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
        .disabled(busy)
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

    // actions — claim synchronously and capture the original client/owner.
    private func mutate<Value: Sendable>(optimistic: () -> Void = {},
        request: @escaping @MainActor (PulseAPIClient, String) async throws -> Value,
        apply: @escaping @MainActor (Value) throws -> Void) {
        let epoch = session.sessionGeneration, client = session.api, owner = viewerId
        guard owns(epoch), !owner.isEmpty, prefs.viewer?.id == owner, client.userId == owner,
              PulseWalletOperations.gatewayKey(client.baseURL) == PulseWalletOperations.gatewayKey(PulseEndpoints.gatewayURL),
              let ticket = mutationGate.begin(owner: epoch, resource: conversation.id) else { return }
        loadTicket = UUID(); loadTask?.cancel()
        optimistic()
        mutationTask = Task { @MainActor in
            defer {
                if mutationGate.finish(ticket) { mutationTask = nil }
                // A cancelled/lost reply may still follow a committed write.
                if session.sessionGeneration == epoch { session.noteInboxChanged() }
            }
            do {
                guard owns(epoch), mutationGate.isCurrent(ticket, owner: epoch, resource: conversation.id) else { return }
                let result = try await request(client, owner)
                guard owns(epoch), prefs.viewer?.id == owner,
                      mutationGate.isCurrent(ticket, owner: epoch, resource: conversation.id) else { return }
                loadTicket = UUID()
                try apply(result)
            } catch {
                guard owns(epoch), mutationGate.isCurrent(ticket, owner: epoch, resource: conversation.id) else { return }
                session.toasts.show(RoomViewModel.describe(error))
                // Do not roll back to a possibly obsolete pre-request snapshot.
                detail = nil; phase = .loading
                reloadDetail()
            }
        }
    }

    private func setTtl(_ seconds: Int) {
        mutate(request: { api, owner in
            try await api.setDisappearingTtl(conversation.id, userId: owner, ttlSeconds: seconds)
        }, apply: { fresh in
            guard let applied = fresh.ttlSeconds else {
                throw PulseAPIClient.Failure(kind: .server, message: "Disappearing messages were not confirmed.")
            }
            try acceptDetail(fresh)
            session.toasts.show(applied == 0 ? "Disappearing messages off" :
                "New messages vanish after \(GroupInfoView.ttlLabel(applied).lowercased())")
        })
    }

    private func setMuted(_ preset: String?) {
        mutate(request: { api, owner in
            try await api.setMuted(conversationId: conversation.id, until: preset)
            return try await api.conversationDetail(id: conversation.id, userId: owner)
        }, apply: { fresh in
            try acceptDetail(fresh)
            session.toasts.show(fresh.isMutedNow ? "Notifications muted" : "Notifications unmuted")
        })
    }

    private func setMyScreenPrivacy(_ on: Bool) {
        mutate(optimistic: { prefs.setScreenPrivacy(conversationId: conversation.id, on: on) }, request: { api, _ in
            try await api.setMyScreenPrivacy(conversation.id, on: on)
        }, apply: { confirmed in
            prefs.adoptServerScreenPrivacy(conversationId: conversation.id, on: confirmed)
            session.toasts.show(confirmed ? "Screen security on for you" : "Screen security off for you")
        })
    }

    private func setRoomScreenPrivacy(_ on: Bool) {
        mutate(request: { api, owner in
            try await api.patchConversation(conversation.id, requesterId: owner, screenPrivacy: on)
        }, apply: { fresh in
            try acceptDetail(fresh)
            session.toasts.show(fresh.screenPrivacy == true ? "Screen security on" : "Screen security off")
        })
    }
}

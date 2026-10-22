import SwiftUI
import Combine

/// Wave 2 saved library (spec §1 row 14) - GET /api/users/{id}/saved (newest
/// first, cap 100, NO server pagination/search) mirrored into the GRDB cache
/// via one atomic snapshot replacement, then observed with a
/// LOCAL search over sender/content/conversation, an honest loading/error/
/// empty state, row Unsave via explicit repeat-safe removal, and "open original"
/// = resolve the full conversation → Chats stack pushes the room with a
/// jump-to-message target (the same path global search uses).
struct SavedLibraryView: View {
    @ObservedObject var session: PulseSession
    /// (conversation, messageId) - RootView dismisses + switches to Chats +
    /// requestOpenRoom(jumpMessageId:).
    var onOpenOriginal: (WireConversationSummary, String) -> Void

    enum Phase: Equatable { case loading, loaded, failed(String) }

    @State private var phase: Phase = .loading
    @State private var items: [WireSavedItem] = []
    @State private var query = ""
    @State private var openingId: String?
    @State private var removingIds: Set<String> = []
    @State private var loadGeneration = UUID()
    @State private var libraryObservation: AnyCancellable?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
                Divider()
                content
            }
            .background(
                // PULSE EMBER (EMB-I): sunset ground, subtree pinned dark.
                Rectangle().fill(PulseTheme.emberBackdrop).ignoresSafeArea()
            )
            .environment(\.colorScheme, .dark)
            .tint(.white)
            .navigationTitle("Saved")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .task {
            let epoch = session.sessionGeneration
            if let store = session.store {
                libraryObservation = store.observeSavedItems()
                    .receive(on: DispatchQueue.main)
                    .sink(receiveCompletion: { completion in
                        if case .failure = completion { session.toasts.show("Saved messages could not be read from storage") }
                    }, receiveValue: { rows in if session.sessionGeneration == epoch { items = visibleItems(rows) } })
            }
            await load()
        }
        .onChange(of: session.sessionGeneration) { _, _ in
            libraryObservation?.cancel(); libraryObservation = nil
            loadGeneration = UUID(); items = []; phase = .failed("Reopen Saved messages for the current account")
        }
        .onDisappear { libraryObservation?.cancel(); libraryObservation = nil; loadGeneration = UUID() }
        .onReceive(session.messageVisibilityClock) { now in
            let visible = visibleItems(items, now: now)
            if visible.count != items.count { items = visible }
        }
    }

    private func visibleItems(_ rows: [WireSavedItem], now: Date = Date()) -> [WireSavedItem] {
        rows.filter { item in
            item.message.deletedAt == nil && item.message.viewOnce != true &&
                PulseRemediationLogic.isAlive(item.message.expiresAt, now: now) &&
                !(item.message.parentId.map(session.deniedThreadRoots.contains) ?? false)
        }
    }

    // ── data ─────────────────────────────────────────────────

    private func load() async {
        guard let viewer = session.viewer else {
            phase = .failed("You need an identity to open the saved library")
            return
        }
        let ticket = UUID()
        loadGeneration = ticket
        let epoch = session.sessionGeneration
        let api = session.api
        guard let store = session.store else {
            items = []; phase = .failed("Saved-message storage is unavailable"); return
        }
        let watermark = session.messageRevision
        let savedWatermark = session.savedRevision
        if items.isEmpty, let cached = try? store.cachedSavedItems() { items = visibleItems(cached) }
        phase = items.isEmpty ? .loading : .loaded
        do {
            let fresh = try await api.savedLibrary(userId: viewer.id)
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            guard savedWatermark == session.savedRevision else { phase = .loaded; return }
            let changed = Set(fresh.filter { !session.acceptsSyncMessage($0.message.id, startedAt: watermark) }.map { $0.message.id })
            try store.upsert(savedItems: fresh, replacingSnapshot: true, preservingMessageIds: changed)
            items = visibleItems(try store.cachedSavedItems())
            phase = .loaded
        } catch {
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            // Never call an empty rendered list "loaded" merely because marker IDs exist.
            if !items.isEmpty {
                phase = .loaded
            } else {
                phase = .failed(RoomViewModel.describe(error))
            }
        }
    }

    private func unsave(_ item: WireSavedItem) {
        guard session.viewer != nil, !removingIds.contains(item.message.id) else { return }
        removingIds.insert(item.message.id)
        loadGeneration = UUID()
        let epoch = session.sessionGeneration
        Task {
            defer { removingIds.remove(item.message.id) }
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                try await session.setMessageSaved(item.message, saved: false)
                guard session.sessionGeneration == epoch else { return }
                loadGeneration = UUID()
                items.removeAll { $0.message.id == item.message.id }
                await load()
            } catch is CancellationError {
            } catch {
                guard session.sessionGeneration == epoch else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// Resolves the FULL conversation (members drive room colors/title) then
    /// hands it to the Chats stack with the jump target.
    private func openOriginal(_ item: WireSavedItem) {
        guard openingId == nil, let viewer = session.viewer,
              item.message.deletedAt == nil, PulseRemediationLogic.isAlive(item.message.expiresAt),
              items.contains(where: { $0.message.id == item.message.id }) else { return }
        openingId = item.message.id
        let epoch = session.sessionGeneration
        let api = session.api
        Task {
            defer { openingId = nil }
            do {
                let conversation = try await api.conversationDetail(
                    id: item.conversation.id,
                    userId: viewer.id,
                )
                guard session.sessionGeneration == epoch,
                      items.contains(where: { $0.message.id == item.message.id }),
                      PulseRemediationLogic.isAlive(item.message.expiresAt) else { return }
                onOpenOriginal(conversation, item.message.id)
            } catch {
                session.toasts.show("Couldn't open the original chat")
            }
        }
    }

    // ── views ────────────────────────────────────────────────

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search saved messages", text: $query)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView("Loading your library…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            VStack(spacing: 10) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Retry") { Task { await load() } }
                    .font(.footnote.weight(.semibold))
            }
            .padding(.top, 48)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            if filtered.isEmpty {
                ContentUnavailableCompat(
                    title: query.isEmpty ? "Nothing saved yet" : "No matches",
                    systemImage: "bookmark",
                    note: query.isEmpty
                        ? "Long-press any message and choose Save to keep it here"
                        : "Try a different search - the library keeps the 100 most recent saves",
                )
            } else {
                List(filtered, id: \.message.id) { item in
                    row(item)
                }
                .listStyle(.plain)
            }
        }
    }

    private func row(_ item: WireSavedItem) -> some View {
        Button {
            openOriginal(item)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(rowTitle(item))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(PulseTheme.emberOnline)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(PulseFormat.listStamp(item.savedAt))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    if openingId == item.message.id {
                        ProgressView().controlSize(.mini)
                    }
                }
                Text(snippet(item))
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            .padding(.vertical, 3)
        }
        .buttonStyle(PulseButtonStyle())
        .contextMenu {
            Button(role: .destructive) {
                unsave(item)
            } label: {
                Label("Unsave", systemImage: "bookmark.slash")
            }
        }
    }

    // ── row text helpers ─────────────────────────────────────

    private var filtered: [WireSavedItem] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return items }
        return items.filter { item in
            if rowTitle(item).lowercased().contains(needle) { return true }
            if snippet(item).lowercased().contains(needle) { return true }
            if (item.conversation.name ?? "").lowercased().contains(needle) { return true }
            return false
        }
    }

    private func rowTitle(_ item: WireSavedItem) -> String {
        let sender = item.message.senderId == session.viewer?.id
            ? "You"
            : (item.message.sender?.name ?? "Someone")
        let place = item.conversation.name ?? "chat"
        return "\(sender) in \(place)"
    }

    /// Bracketed kind tag + body excerpt ≤64 (spec §1 row 14) - plain text
    /// tags instead of emoji prefixes (R17 Neo de-emoji pass).
    private func snippet(_ item: WireSavedItem) -> String {
        let message = item.message
        let prefix: String
        let fallback: String
        switch message.kind {
        case "image":
            prefix = "[Photo] "
            fallback = "Photo"
        case "audio":
            prefix = "[Voice] "
            fallback = "Voice note"
        case "file":
            prefix = "[File] "
            fallback = message.fileName ?? "File"
        case "poll":
            prefix = "[Poll] "
            fallback = "Poll"
        default:
            prefix = ""
            fallback = "Message"
        }
        var body = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.isEmpty { body = fallback }
        if body.count > 64 { body = String(body.prefix(64)) + "…" }
        return prefix + body
    }
}

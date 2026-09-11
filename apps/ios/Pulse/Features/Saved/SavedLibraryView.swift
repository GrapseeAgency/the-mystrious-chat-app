import SwiftUI

/// Wave 2 saved library (spec §1 row 14) — GET /api/users/{id}/saved (newest
/// first, cap 100, NO server pagination/search) mirrored into the GRDB cache
/// via upsert(savedItems:) + replaceSaved(messageIds:), then rendered with a
/// LOCAL search over sender/content/conversation, an honest loading/error/
/// empty state, row Unsave via the existing save toggle, and "open original"
/// = resolve the full conversation → Chats stack pushes the room with a
/// jump-to-message target (the same path global search uses).
struct SavedLibraryView: View {
    @ObservedObject var session: PulseSession
    /// (conversation, messageId) — RootView dismisses + switches to Chats +
    /// requestOpenRoom(jumpMessageId:).
    var onOpenOriginal: (WireConversationSummary, String) -> Void

    enum Phase: Equatable { case loading, loaded, failed(String) }

    @State private var phase: Phase = .loading
    @State private var items: [WireSavedItem] = []
    @State private var query = ""
    @State private var openingId: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
                Divider()
                content
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Saved")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .task { await load() }
    }

    // ── data ─────────────────────────────────────────────────

    private func load() async {
        guard let viewer = session.viewer else {
            phase = .failed("You need an identity to open the saved library")
            return
        }
        phase = items.isEmpty ? .loading : .loaded
        do {
            let fresh = try await session.api.savedLibrary(userId: viewer.id)
            try? session.store?.upsert(savedItems: fresh)
            try? session.store?.replaceSaved(messageIds: fresh.map { $0.message.id })
            items = fresh
            phase = .loaded
        } catch {
            // Offline fallback — the cache mirror stays usable.
            if let store = session.store, let ids = try? store.savedIds(), !ids.isEmpty {
                phase = .loaded
            } else {
                phase = .failed(RoomViewModel.describe(error))
            }
        }
    }

    private func unsave(_ item: WireSavedItem) {
        guard let viewer = session.viewer else { return }
        Task {
            do {
                _ = try await session.api.toggleMessageSave(id: item.message.id, userId: viewer.id)
                try? session.store?.deleteSaved(messageId: item.message.id)
                await load()
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// Resolves the FULL conversation (members drive room colors/title) then
    /// hands it to the Chats stack with the jump target.
    private func openOriginal(_ item: WireSavedItem) {
        guard openingId == nil, let viewer = session.viewer else { return }
        openingId = item.message.id
        Task {
            defer { openingId = nil }
            do {
                let conversation = try await session.api.conversationDetail(
                    id: item.conversation.id,
                    userId: viewer.id,
                )
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
                        : "Try a different search — the library keeps the 100 most recent saves",
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
                        .foregroundStyle(PulseTheme.emerald)
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

    /// 🖼 / 🎤 / 📎 / 📊 kind prefix + body excerpt ≤64 (spec §1 row 14).
    private func snippet(_ item: WireSavedItem) -> String {
        let message = item.message
        let prefix: String
        let fallback: String
        switch message.kind {
        case "image":
            prefix = "🖼 "
            fallback = "Photo"
        case "audio":
            prefix = "🎤 "
            fallback = "Voice note"
        case "file":
            prefix = "📎 "
            fallback = message.fileName ?? "File"
        case "poll":
            prefix = "📊 "
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

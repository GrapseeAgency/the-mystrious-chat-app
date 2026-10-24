import SwiftUI

/// Owned multi-target forwarding. Local acceptance means queued, not delivered;
/// the durable outbox sends the complete content/media/payload envelope.
struct ForwardSheet: View {
    let source: WireChatMessage
    @ObservedObject var session: PulseSession
    var onFinished: () -> Void = {}

    enum Phase: Equatable { case loading, loaded, failed(String) }

    @State private var conversations: [WireConversationSummary] = []
    @State private var selected: Set<String> = []
    @State private var query = ""
    @State private var phase: Phase = .loading
    @State private var sending = false
    @State private var errorText: String?
    @State private var ownerEpoch: UUID?
    @State private var loadRevision = UUID()
    @State private var reloadToken = UUID()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
                if let errorText { Text(errorText).font(.caption).foregroundStyle(.red).padding(.horizontal, 14) }
                content
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Forward")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        send()
                        PulseHaptics.tap()
                    } label: {
                        if sending {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(selected.isEmpty ? "Send" : "Send (\(selected.count))")
                                .font(.subheadline.weight(.semibold))
                        }
                    }
                    .disabled(selected.isEmpty || sending)
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: reloadToken) {
            if ownerEpoch == nil { ownerEpoch = session.sessionGeneration }
            await load()
        }
        .onChange(of: session.sessionGeneration) { _ in dismiss() }
    }

    // content

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.footnote)
                .foregroundStyle(PulseTheme.textTertiary)
            TextField("Search chats", text: $query)
                .font(.subheadline)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Capsule().fill(Color(.secondarySystemBackground)))
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView("Loading chats…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            VStack(spacing: 10) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Retry") { reloadToken = UUID() }
                    .font(.footnote.weight(.semibold))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(filtered, id: \.id) { conversation in
                        row(conversation)
                    }
                    if filtered.isEmpty {
                        VStack(spacing: 6) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 24, weight: .light))
                                .foregroundStyle(PulseTheme.zinc(300))
                            Text("No chats match")
                                .font(.footnote)
                                .foregroundStyle(PulseTheme.textSecondary)
                        }
                        .padding(.top, 32)
                    }
                }
            }
        }
    }

    private func row(_ conversation: WireConversationSummary) -> some View {
        let isSelected = selected.contains(conversation.id)
        return Button {
            PulseHaptics.tap()
            if isSelected {
                selected.remove(conversation.id)
            } else {
                selected.insert(conversation.id)
            }
        } label: {
            HStack(spacing: 12) {
                RowAvatar(
                    name: title(conversation),
                    colorName: conversation.members.first(where: { $0.id != session.viewer?.id })?.color,
                    photoPath: conversation.photo,
                    size: 42,
                    groupID: conversation.isGroup ? conversation.id : nil,
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(conversation))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                        .lineLimit(1)
                    Text(subtitle(conversation))
                        .font(.system(size: 12))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(isSelected ? PulseTheme.accent : PulseTheme.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(sending)
        .accessibilityLabel("\(isSelected ? "Deselect" : "Select") \(title(conversation))")
    }

    // helpers

    private var filtered: [WireConversationSummary] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return conversations }
        return conversations.filter { conversation in
            if let name = conversation.name, name.lowercased().contains(needle) { return true }
            return conversation.members.contains { $0.name.lowercased().contains(needle) }
        }
    }

    private func title(_ conversation: WireConversationSummary) -> String {
        conversation.name
            ?? conversation.members.first(where: { $0.id != session.viewer?.id })?.name
            ?? conversation.members.first?.name
            ?? "Conversation"
    }

    private func subtitle(_ conversation: WireConversationSummary) -> String {
        if conversation.isGroup {
            return "\(conversation.members.count) members"
        }
        return "Direct message"
    }

    @MainActor private func load() async {
        guard !Task.isCancelled, let owner = session.viewer?.id, ownerEpoch == session.sessionGeneration else { return }
        let epoch = session.sessionGeneration, api = session.api, ticket = UUID()
        loadRevision = ticket
        if conversations.isEmpty, let cached = try? session.store?.cachedConversations() {
            conversations = cached.compactMap { row in
                guard let raw = row.wireJson?.data(using: .utf8), let wire = try? JSONDecoder().decode(WireConversationSummary.self, from: raw),
                      wire.members.contains(where: { $0.id == owner }) else { return nil }
                return wire
            }
        }
        phase = conversations.isEmpty ? .loading : .loaded
        do {
            let rows = try await api.conversations()
            try Task.checkCancellation()
            guard !Task.isCancelled, session.sessionGeneration == epoch, loadRevision == ticket else { return }
            conversations = rows.filter { $0.members.contains(where: { $0.id == owner }) }
            selected.formIntersection(Set(conversations.map(\.id)))
            phase = .loaded
        } catch is CancellationError { return } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, loadRevision == ticket else { return }
            if conversations.isEmpty { phase = .failed(RoomViewModel.describe(error)) }
            else { phase = .loaded; errorText = "Showing saved chats. Forwards will remain queued until delivery is confirmed." }
        }
    }

    /// Commit each independent target before reporting it queued. Failed targets
    /// remain selected; retries never resend targets already accepted into storage.
    @MainActor private func send() {
        guard !sending, !selected.isEmpty, ownerEpoch == session.sessionGeneration,
              session.ready, let viewer = session.viewer, let store = session.store else { return }
        if let rootId = source.parentId, session.threadAccessDenied(rootId: rootId) {
            errorText = "This thread is unavailable. Nothing was forwarded."
            return
        }
        let targets = conversations.filter { selected.contains($0.id) && $0.members.contains(where: { $0.id == viewer.id }) }
        guard !targets.isEmpty else { errorText = "Reload the destination chats before forwarding."; return }
        sending = true; errorText = nil
        defer { sending = false }
        var queued = 0
        var failure: String?
        // No suspension between owner validation and these local commits. Only the
        // account-owned outbox performs HTTP and it keeps each operation's identity.
        for target in targets {
            do {
                _ = try PulseForwardDelivery.enqueue(source: source, targetId: target.id, viewer: viewer, store: store)
                selected.remove(target.id)
                queued += 1
            } catch { failure = RoomViewModel.describe(error) }
        }
        if queued > 0 {
            session.outbox?.reloadPending()
            session.flushOutbox()
            session.toasts.show("Forward queued for \(queued) chat\(queued == 1 ? "" : "s")")
        }
        if selected.isEmpty { dismiss(); onFinished() }
        else { errorText = "\(selected.count) weren't queued. \(failure ?? "Reload the destination chats and retry.")" }
    }
}

import SwiftUI

/// Wave 1 forward — the web ForwardSheet outcome: no forward endpoint exists,
/// so the client re-POSTs the source body (content + stored media paths,
/// kind included) to each selected conversation (spec §1.1 "Forward").
/// Multi-select with a local filter; the toast reports the honest count.
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
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
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
        .task { await load() }
    }

    // ── content ──────────────────────────────────────────────

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
                Button("Retry") { Task { await load() } }
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

    // ── helpers ──────────────────────────────────────────────

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

    private func load() async {
        phase = conversations.isEmpty ? .loading : phase
        do {
            conversations = try await session.api.conversations()
            phase = .loaded
        } catch {
            if conversations.isEmpty {
                phase = .failed(RoomViewModel.describe(error))
            } else {
                phase = .loaded
            }
        }
    }

    /// Re-POST the source body per target (client-side forward — there is NO
    /// forward endpoint). Media rides the STORED paths (no re-upload); each
    /// target is independent — one failure never blocks the rest.
    private func send() {
        guard !sending, !selected.isEmpty else { return }
        sending = true
        let targets = conversations.filter { selected.contains($0.id) }
        Task {
            defer { sending = false }
            var delivered = 0
            for target in targets {
                do {
                    _ = try await session.api.sendMessage(
                        conversationId: target.id,
                        content: source.content,
                        imagePath: source.imagePath,
                        filePath: source.filePath,
                        fileName: source.fileName,
                        fileSize: source.fileSize,
                        kind: source.kind,
                    )
                    delivered += 1
                } catch {
                    // Independent per target — keep going, count honestly.
                }
            }
            if delivered > 0 {
                session.toasts.show("Forwarded to \(delivered) chat\(delivered == 1 ? "" : "s")")
                dismiss()
                onFinished()
            } else {
                session.toasts.show("Forward failed — check your connection")
            }
        }
    }
}

import SwiftUI

/// R42 - the share-in picker sheet: lists conversations (the same inbox
/// snapshot the Chats tab uses) and queues the prepared share through the
/// SAME durable outbox as in-app sends. A durable queue success removes the
/// inbox entry (consumption); "Remove" is the user's explicit discard;
/// "Later" keeps the entry for the next scan.
struct ShareInSheet: View {
    let entry: ShareInInbox.Entry
    let conversations: [WireConversationSummary]
    let viewerId: String?
    /// The parent owns the durable enqueue (session + store); the callback
    /// receives an error text on failure, nil on durable success.
    let deliver: (WireConversationSummary, @escaping (String?) -> Void) -> Void
    let onConsumed: (String) -> Void
    let onRemoved: (String) -> Void
    let onDismiss: () -> Void

    @State private var sendingTo: String?
    @State private var failureText: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(shareSummary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                    if let failureText {
                        Text(failureText)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                Section {
                    if conversations.isEmpty {
                        Text("No conversations yet - create one from the Chats tab.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(conversations, id: \.id) { conversation in
                        Button {
                            deliverTo(conversation)
                        } label: {
                            HStack(spacing: 12) {
                                RowAvatar(
                                    name: conversation.isGroup ? conversation.name : dmName(conversation),
                                    colorName: conversation.isGroup ? nil : dmColor(conversation),
                                    photoPath: conversation.photo,
                                    size: 40,
                                    groupID: conversation.isGroup ? conversation.id : nil,
                                    avatarShape: conversation.isGroup ? .squircle : .circle,
                                    showPresence: false,
                                    online: false,
                                )
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(conversation.isGroup ? (conversation.name ?? "Group") : dmName(conversation))
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(.primary)
                                    if let last = conversation.lastMessage, !last.content.isEmpty {
                                        Text(last.content)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                }
                                Spacer()
                                if sendingTo == conversation.id {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(sendingTo != nil)
                    }
                } header: {
                    Text("Share to")
                }
                Section {
                    Button(role: .destructive) {
                        onRemoved(entry.id)
                    } label: {
                        Text("Remove this share")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(sendingTo != nil)
                }
            }
            .navigationTitle("Share to Pulse")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Later") { onDismiss() }
                        .disabled(sendingTo != nil)
                }
            }
        }
    }

    private var shareSummary: String {
        switch entry.kind {
        case "image": return "Photo - prepared and ready to send"
        default: return entry.text ?? ""
        }
    }

    private func dmName(_ conversation: WireConversationSummary) -> String {
        if let member = conversation.members.first(where: { $0.id != viewerId }) {
            return member.name
        }
        return conversation.name ?? "Chat"
    }

    private func dmColor(_ conversation: WireConversationSummary) -> String? {
        conversation.members.first(where: { $0.id != viewerId })?.color
    }

    private func deliverTo(_ conversation: WireConversationSummary) {
        guard sendingTo == nil else { return }
        sendingTo = conversation.id
        failureText = nil
        deliver(conversation) { error in
            sendingTo = nil
            if let error {
                failureText = error
            } else {
                onConsumed(entry.id)
            }
        }
    }
}

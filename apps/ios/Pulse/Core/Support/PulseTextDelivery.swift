import Foundation

/// Both presentations use the same durable envelope. Nothing leaves the composer
/// until its text, metadata and optimistic bubble have committed to local storage.
@MainActor
enum PulseTextDelivery {
    static func enqueue(store: PulseStore, viewer: PulseViewer, conversationId: String,
                        content: String, effect: String? = nil, replyTo: WireChatMessage? = nil,
                        topicId: String? = nil, anon: Bool = false) throws -> WireChatMessage {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !conversationId.isEmpty else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Type a message first; effects ride a text message.")
        }
        var payload: String?
        if let effect {
            guard ["confetti", "lasers", "echo", "sparkles"].contains(effect) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "This effect is not supported.")
            }
            payload = String(data: try JSONSerialization.data(withJSONObject: ["effect": effect]), encoding: .utf8)
        }
        let id = UUID().uuidString
        let alias = anon ? PulseRemediationLogic.anonAlias(viewerId: viewer.id, conversationId: conversationId) : nil
        var message = TempMessages.make(conversationId: conversationId, viewer: viewer, clientId: id,
                                       content: text, parentId: nil, replyTo: replyTo, anon: anon, anonAlias: alias, topicId: topicId)
        message.payload = payload
        try store.enqueueMessage(message, clientId: id, envelope: PulseOutboxForward(
            kind: "text", replyToId: replyTo?.id, topicId: topicId, payload: payload, anon: anon))
        return message
    }

    static func pendingMessages(store: PulseStore, conversationId: String) throws -> [WireChatMessage] {
        try store.outboxPending().filter { $0.conversationId == conversationId }.compactMap { entry in
            return try store.message(id: PulseOutboxEngine.tempMessageId(clientId: entry.clientId))
        }
    }

    /// Merge confirmations and persisted sends in chronological order without duplicate IDs.
    static func timeline(_ remote: [WireChatMessage], pending: [WireChatMessage]) -> [WireChatMessage] {
        var rows: [String: WireChatMessage] = [:]
        for message in remote + pending where PulseRemediationLogic.isAlive(message.expiresAt) { rows[message.id] = message }
        return rows.values.sorted {
            let a = PulseFormat.date($0.createdAt) ?? .distantPast
            let b = PulseFormat.date($1.createdAt) ?? .distantPast
            return a == b ? $0.id < $1.id : a < b
        }
    }
}

/// Optimistic temp-row factory shared by the river AND thread sends -
/// id `local_<clientId>` (Wave 0 convention), viewer sender, quote block,
/// optional thread parent. Real rows replace it by id + content dedupe.
/// @MainActor: builds on PulseOutboxEngine.tempMessageId (MainActor-static).
@MainActor
enum TempMessages {
    static func make(
        conversationId: String,
        viewer: PulseViewer,
        clientId: String,
        content: String,
        parentId: String?,
        replyTo: WireChatMessage? = nil,
        anon: Bool = false,
        anonAlias: String? = nil,
        topicId: String? = nil,
        attachment: PulseOutboxForward? = nil,
    ) -> WireChatMessage {
        let sender = WireSender(
            id: viewer.id,
            name: viewer.name,
            username: viewer.username,
            color: viewer.color,
            avatar: viewer.avatar,
        )
        let quoted = replyTo.map { source in
            WireReplySnippet(
                id: source.id,
                content: source.content,
                senderName: source.sender?.name ?? "",
                deleted: source.deletedAt != nil,
            )
        }
        return WireChatMessage(
            id: PulseOutboxEngine.tempMessageId(clientId: clientId),
            conversationId: conversationId,
            senderId: viewer.id,
            content: content,
            kind: attachment?.kind ?? "text",
            createdAt: PulseOutboxClock.now(),
            editedAt: nil,
            deletedAt: nil,
            sender: sender,
            reactions: nil,
            replyTo: quoted,
            parentId: parentId,
            imagePath: attachment?.imagePath,
            audioPath: attachment?.audioPath,
            durationMs: attachment?.durationMs,
            filePath: attachment?.filePath,
            fileName: attachment?.fileName,
            fileSize: attachment?.fileSize,
            pinnedAt: nil,
            viewOnce: attachment?.viewOnce,
            anon: anon ? true : nil,
            anonAlias: anonAlias,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: topicId,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }
}

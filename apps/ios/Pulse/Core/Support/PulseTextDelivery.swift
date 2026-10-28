import Foundation

/// Both presentations use the same durable envelope. Nothing leaves the composer
/// until its text, metadata and optimistic bubble have committed to local storage.
@MainActor
enum PulseTextDelivery {
    static func enqueue(store: PulseStore, viewer: PulseViewer, conversationId: String,
                        content: String, effect: String? = nil, replyTo: WireChatMessage? = nil,
                        topicId: String? = nil, anon: Bool = false, parentId: String? = nil, draftJournal: PulseDraftJournal? = nil) throws -> WireChatMessage {
        let text = try validatedContent(content, allowEmpty: false)
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
                                       content: text, parentId: parentId, replyTo: replyTo, anon: anon, anonAlias: alias, topicId: parentId == nil ? topicId : nil)
        message.payload = payload
        let consumption = try draftJournal?.prepareSend()
        let consumed = try store.enqueueMessage(message, clientId: id, envelope: PulseOutboxForward(
            kind: "text", replyToId: replyTo?.id, parentId: parentId, topicId: parentId == nil ? topicId : nil, payload: payload, anon: anon), consumingDraft: consumption)
        if consumed { draftJournal?.didConsumeDraft() }
        return message
    }

    /// Structured small sends use the same journal and dispatcher as text/media.
    static func enqueueRich(store: PulseStore, viewer: PulseViewer, conversationId: String,
                            kind: String, content: String = "", payload: [String: Any],
                            replyTo: WireChatMessage? = nil, topicId: String? = nil, anon: Bool = false) throws -> WireChatMessage {
        guard !conversationId.isEmpty, ["sticker", "location", "poll"].contains(kind) else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Unsupported message type.")
        }
        let text = try validatedContent(content, allowEmpty: true)
        if kind == "poll" {
            guard let question = payload["question"] as? String, question == text,
                  !question.isEmpty, question.utf16.count <= 140,
                  let options = payload["options"] as? [String], (2...6).contains(options.count),
                  options.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf16.count <= 80 }) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "A poll needs a question of 1–140 characters and 2–6 options of 1–80 characters.")
            }
        } else if kind == "location" {
            guard let lat = payload["lat"] as? Double, let lng = payload["lng"] as? Double,
                  lat.isFinite, lng.isFinite, (-90...90).contains(lat), (-180...180).contains(lng) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Location is invalid.")
            }
        } else {
            guard let emoji = payload["emoji"] as? String, !emoji.isEmpty else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Select a sticker first.")
            }
        }
        guard JSONSerialization.isValidJSONObject(payload) else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Message metadata is invalid.")
        }
        let raw = String(decoding: try JSONSerialization.data(withJSONObject: payload), as: UTF8.self)
        let envelope = PulseOutboxForward(kind: kind, replyToId: replyTo?.id, topicId: topicId, payload: raw, anon: anon)
        let id = UUID().uuidString
        var message = TempMessages.make(conversationId: conversationId, viewer: viewer, clientId: id,
            content: text, parentId: nil, replyTo: replyTo, anon: anon,
            anonAlias: anon ? PulseRemediationLogic.anonAlias(viewerId: viewer.id, conversationId: conversationId) : nil,
            topicId: topicId, attachment: envelope)
        message.payload = raw
        try store.enqueueMessage(message, clientId: id, envelope: envelope)
        return message
    }

    static func validatedContent(_ content: String, allowEmpty: Bool) throws -> String {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard allowEmpty || !text.isEmpty else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Type a message first.")
        }
        // JavaScript's server limit counts UTF-16 units, not Swift graphemes.
        guard text.utf16.count <= 2000 else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Message or caption exceeds the 2000-character limit.")
        }
        return text
    }

    static func pendingMessages(store: PulseStore, conversationId: String) throws -> [WireChatMessage] {
        try store.outboxPending().filter { $0.conversationId == conversationId }.compactMap { entry in
            return try store.message(id: PulseOutboxEngine.tempMessageId(clientId: entry.clientId))
        }
    }

    /// Merge confirmations and persisted sends in chronological order without duplicate IDs.
    static func timeline(_ remote: [WireChatMessage], pending: [WireChatMessage]) -> [WireChatMessage] {
        var rows: [String: WireChatMessage] = [:]
        for message in remote + pending {
            rows[message.id] = PulseMessageMerge.merge(rows[message.id], incoming: message)
        }
        return rows.values.filter { PulseRemediationLogic.isAlive($0.expiresAt) }.sorted {
            let a = PulseFormat.date($0.createdAt) ?? .distantPast
            let b = PulseFormat.date($1.createdAt) ?? .distantPast
            return a == b ? $0.id < $1.id : a < b
        }
    }
}

/// Optimistic temp-row factory shared by the river AND thread sends -
/// id `local_<clientId>` (Wave 0 convention), viewer sender, quote block,
/// optional thread parent. Real rows replace it by operation ID, never by matching content.
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
                senderName: source.displayAuthorName,
                deleted: source.deletedAt != nil,
                // A thread quote also needs its root's lifetime; wait for the
                // server snippet rather than inventing an unbounded local witness.
                quoteVersion: source.conversationId == conversationId && source.parentId == nil &&
                    !source.id.hasPrefix("local_") && source.viewOnce != nil && source.anon != nil ? 1 : nil,
                expiresAt: source.expiresAt, viewOnce: source.viewOnce == true,
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

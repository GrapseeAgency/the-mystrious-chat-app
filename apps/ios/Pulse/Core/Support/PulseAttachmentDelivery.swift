import Foundation

/// Bounded upload bytes are journaled in the account's SQLite outbox, not a
/// temporary recorder/picker URL. No network call precedes this transaction.
@MainActor
enum PulseAttachmentDelivery {
    static func enqueue(store: PulseStore, viewer: PulseViewer, conversationId: String,
                        content: String, dataUrl: String, envelope: PulseOutboxForward,
                        replyTo: WireChatMessage? = nil) throws -> WireChatMessage {
        let content = try PulseTextDelivery.validatedContent(content, allowEmpty: true)
        let document = envelope.kind == "file"
        let limit = document ? ((10 * 1024 * 1024 + 2) / 3) * 4 + 128 : 4_500_000
        guard dataUrl.utf8.count <= limit else {
            throw PulseAPIClient.Failure(kind: .validation, message: document ? "Documents are limited to 10 MB." : "This photo or voice note is too large. Choose a smaller photo or shorter recording.")
        }
        guard ["image", "audio", "file"].contains(envelope.kind ?? ""),
              let comma = dataUrl.firstIndex(of: ","), dataUrl[..<comma].hasSuffix(";base64"),
              let bytes = Data(base64Encoded: String(dataUrl[dataUrl.index(after: comma)...])), !bytes.isEmpty else {
            throw PulseAPIClient.Failure(kind: .validation, message: "This attachment could not be read.")
        }
        let prefix = String(dataUrl[..<comma])
        let allowed: [String]
        switch envelope.kind {
        case "image": allowed = ["image/jpeg", "image/png", "image/webp"]
        case "audio": allowed = ["audio/mp4", "audio/aac", "audio/webm", "audio/mpeg", "audio/ogg", "audio/wav"]
        default: allowed = ["application/pdf", "application/zip", "text/plain", "text/csv"]
        }
        guard allowed.contains(where: { prefix == "data:\($0);base64" }),
              !document || bytes.count <= 10 * 1024 * 1024 else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Unsupported attachment type or size.")
        }
        if envelope.kind == "audio" {
            guard let duration = envelope.durationMs, duration.isFinite, duration > 0, duration <= 600_000 else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Voice notes must be no longer than ten minutes.")
            }
        }
        var pending = envelope
        pending.uploadDataUrl = dataUrl
        pending.imagePath = nil; pending.filePath = nil; pending.audioPath = nil
        pending.replyToId = replyTo?.id ?? pending.replyToId
        if document {
            let name = pending.fileName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !name.isEmpty, name.utf16.count <= 120 else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Document names must contain 1–120 characters.")
            }
            pending.fileName = name
            pending.fileSize = bytes.count
        } else {
            // The gateway explicitly rejects document metadata on photos/audio.
            pending.fileName = nil; pending.fileSize = nil
        }
        let id = UUID().uuidString
        let alias = pending.anon == true ? PulseRemediationLogic.anonAlias(viewerId: viewer.id, conversationId: conversationId) : nil
        let message = TempMessages.make(conversationId: conversationId, viewer: viewer, clientId: id,
            content: content, parentId: pending.parentId, replyTo: replyTo, anon: pending.anon == true,
            anonAlias: alias, topicId: pending.topicId, attachment: pending)
        try store.enqueueMessage(message, clientId: id, envelope: pending)
        return message
    }

    static func pendingLabel(_ message: WireChatMessage) -> String? {
        guard message.id.hasPrefix("local_") else { return nil }
        switch message.kind {
        case "image" where message.imagePath == nil: return "Photo queued"
        case "audio" where message.audioPath == nil: return "Voice note queued"
        case "file" where message.filePath == nil: return message.fileName ?? "Document queued"
        default: return nil
        }
    }
}

import Foundation
import CoreFoundation

/// Copy supported content into a fresh durable operation, never another room's
/// parent/topic/reactions/anonymous identity. No network request precedes this commit.
@MainActor
enum PulseForwardDelivery {
    static func envelope(for source: WireChatMessage, now: Date = Date()) throws -> PulseOutboxForward {
        func refuse(_ message: String) -> PulseAPIClient.Failure { .init(kind: .validation, message: message) }
        guard !source.id.hasPrefix("local_"), source.viewOnce != true, source.deletedAt == nil,
              PulseRemediationLogic.isAlive(source.expiresAt, now: now) else { throw refuse("This message can't be forwarded.") }
        guard ["text", "image", "audio", "file", "sticker", "location"].contains(source.kind) else {
            throw refuse("This message type can't be forwarded. Open its original conversation instead.")
        }
        _ = try PulseTextDelivery.validatedContent(source.content, allowEmpty: source.kind != "text")
        if source.kind == "image", source.imagePath?.isEmpty != false { throw refuse("The image is unavailable.") }
        if source.kind == "audio", source.audioPath?.isEmpty != false { throw refuse("The audio is unavailable.") }
        if source.kind == "file", source.filePath?.isEmpty != false || source.fileName?.isEmpty != false { throw refuse("The file is unavailable.") }
        var payload: [String: Any]?
        if let raw = source.payload {
            guard raw.utf16.count <= 2048, let bytes = raw.data(using: .utf8),
                  let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw refuse("Forward metadata is invalid.") }
            payload = object
        }
        if source.kind == "sticker" {
            guard let emoji = payload?["emoji"] as? String, !emoji.isEmpty, emoji.utf16.count <= 12 else { throw refuse("The sticker is unavailable.") }
        }
        if source.kind == "location" {
            guard let latitude = payload?["lat"] as? NSNumber, let longitude = payload?["lng"] as? NSNumber,
                  CFGetTypeID(latitude) != CFBooleanGetTypeID(), CFGetTypeID(longitude) != CFBooleanGetTypeID() else { throw refuse("The location is invalid.") }
            let lat = latitude.doubleValue, lng = longitude.doubleValue
            guard lat.isFinite, lng.isFinite, (-90...90).contains(lat), (-180...180).contains(lng) else { throw refuse("The location is invalid.") }
        }
        return PulseOutboxForward(kind: source.kind, imagePath: source.imagePath, audioPath: source.audioPath,
            durationMs: source.durationMs, filePath: source.filePath, fileName: source.fileName, fileSize: source.fileSize,
            payload: source.payload)
    }

    static func enqueue(source: WireChatMessage, targetId: String, viewer: PulseViewer, store: PulseStore) throws -> WireChatMessage {
        try Task.checkCancellation()
        guard !targetId.isEmpty, let current = try store.message(id: source.id), current.conversationId == source.conversationId else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Refresh the original message before forwarding it.")
        }
        let forward = try envelope(for: current)
        let id = UUID().uuidString
        var message = TempMessages.make(conversationId: targetId, viewer: viewer, clientId: id,
            content: current.content, parentId: nil, attachment: forward)
        message.payload = forward.payload
        try store.enqueueMessage(message, clientId: id, envelope: forward, forwarding: current)
        return message
    }
}

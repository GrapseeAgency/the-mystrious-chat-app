import Foundation

enum PulseViewOnceAccess {
    static func canRequest(_ message: WireChatMessage, viewerId: String, now: Date = Date()) -> Bool {
        !viewerId.isEmpty && message.viewOnce == true && message.senderId != viewerId && message.deletedAt == nil &&
            message.viewedAt == nil && !message.id.hasPrefix("local_") && message.imagePath?.isEmpty == false &&
            PulseRemediationLogic.isAlive(message.expiresAt, now: now)
    }

    static func granted(_ consumed: Bool?, requested: WireChatMessage, returned: WireChatMessage,
                        viewerId: String, now: Date = Date()) -> Bool {
        consumed == true && requested.id == returned.id && requested.conversationId == returned.conversationId &&
            returned.senderId == requested.senderId && returned.parentId == requested.parentId &&
            returned.senderId != viewerId && returned.viewOnce == true && returned.viewedAt != nil &&
            returned.viewedBy == viewerId && returned.deletedAt == nil && returned.imagePath?.isEmpty == false &&
            PulseRemediationLogic.isAlive(returned.expiresAt, now: now)
    }
}

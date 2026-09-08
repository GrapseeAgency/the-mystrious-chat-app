import Foundation

/// Domain models — mirror of Android `domain/model/Models.kt` and web `types.ts`.
/// These three types are the protocol core; every client renders the same shapes.
public struct PulseUser: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let handle: String
    public let avatar: String?
    public let bio: String?
    public let lastSeen: String?
    public let verified: Bool

    public init(id: String, name: String, handle: String, avatar: String? = nil,
                bio: String? = nil, lastSeen: String? = nil, verified: Bool = false) {
        self.id = id; self.name = name; self.handle = handle
        self.avatar = avatar; self.bio = bio; self.lastSeen = lastSeen; self.verified = verified
    }
}

public struct PulseConversation: Codable, Hashable, Sendable {
    public enum Kind: String, Codable, Sendable { case DM, GROUP, CHANNEL, SPACE, VOICE, STAGE }

    public let id: String
    public let kind: Kind
    public let title: String
    public let avatar: String?
    public let lastMessagePreview: String?
    public let lastActivityAt: String?
    public let unreadCount: Int
    public let isPinned: Bool
    public let isMuted: Bool
    public let isArchived: Bool

    public init(id: String, kind: Kind, title: String, avatar: String? = nil,
                lastMessagePreview: String? = nil, lastActivityAt: String? = nil,
                unreadCount: Int = 0, isPinned: Bool = false, isMuted: Bool = false, isArchived: Bool = false) {
        self.id = id; self.kind = kind; self.title = title; self.avatar = avatar
        self.lastMessagePreview = lastMessagePreview; self.lastActivityAt = lastActivityAt
        self.unreadCount = unreadCount; self.isPinned = isPinned
        self.isMuted = isMuted; self.isArchived = isArchived
    }
}

public struct PulseMessage: Codable, Hashable, Sendable {
    public enum Kind: String, Codable, Sendable { case TEXT, IMAGE, VOICE, VIDEO, FILE, POLL, RED_PACKET, SYSTEM }

    public let id: String
    public let conversationId: String
    public let authorId: String
    public let authorName: String
    public let kind: Kind
    public let body: String
    public let createdAt: String
    public let editedAt: String?
    public let deletedAt: String?
    public let replyToId: String?
    public let threadRootId: String?
    public let pinnedAt: String?
    public let viewedOnce: Bool

    public init(id: String, conversationId: String, authorId: String, authorName: String,
                kind: Kind, body: String, createdAt: String, editedAt: String? = nil,
                deletedAt: String? = nil, replyToId: String? = nil, threadRootId: String? = nil,
                pinnedAt: String? = nil, viewedOnce: Bool = false) {
        self.id = id; self.conversationId = conversationId; self.authorId = authorId
        self.authorName = authorName; self.kind = kind; self.body = body; self.createdAt = createdAt
        self.editedAt = editedAt; self.deletedAt = deletedAt; self.replyToId = replyToId
        self.threadRootId = threadRootId; self.pinnedAt = pinnedAt; self.viewedOnce = viewedOnce
    }
}

import Foundation

/// REAL wire DTOs — mirror of the live gateway JSON (identical to Android
/// `apps/android/protocol/WireDtos.kt` and `packages/protocol/contracts.ts`).
/// Codable + Sendable; unknown keys ignored for forward compatibility.

public struct WireSender: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let username: String?
    public let color: String?
    public let avatar: String?
}

public struct WireReaction: Codable, Hashable, Sendable {
    public let id: String?
    public let userId: String?
    public let emoji: String?
    public let createdAt: String?
}

/// N3-b — reactions as the wire actually carries them: grouped per emoji
/// (see serializers.groupReactions: { emoji, userIds, count }).
public struct WireReactionGroup: Codable, Hashable, Sendable {
    public let emoji: String
    public let userIds: [String]
    public let count: Int
}

/// N3-b — the quoted-parent snippet embedded on replies. The wire shape is
/// { id, content, senderName, deleted } (NO conversationId/senderId/kind),
/// so it decodes into its own type — decoding it as WireChatMessage (N2
/// placeholder) failed for every message that actually had a reply.
public struct WireReplySnippet: Codable, Hashable, Sendable {
    public let id: String
    public let content: String
    public let senderName: String
    public let deleted: Bool?
}

/// N3-b — AppUser mirror (GET/POST /api/users), tolerant decode: unknown
/// keys ignored, every field optional except id/name (live shapes verified).
public struct WireUser: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let username: String?
    public let about: String?
    public let color: String?
    public let avatar: String?
    public let statusEmoji: String?
    public let statusText: String?
    public let createdAt: String?
    public let lastSeenAt: String?
    public let verified: Bool?
}

public struct WireUsersPage: Codable, Sendable {
    public let users: [WireUser]
}

public struct WireUserEnvelope: Codable, Sendable {
    public let user: WireUser
}

/// N3-b — Hub wallet (GET /api/hub/wallet?userId=). Real economy numbers.
public struct WireWallet: Codable, Hashable, Sendable {
    public let userId: String?
    public let coins: Int?
    public let gems: Int?
    public let streak: Int?
    public let lastCheckIn: String?
    public let checkedInToday: Bool?
}

public struct WireWalletPage: Codable, Sendable {
    public let wallet: WireWallet
}

/// N3-b — error body with the username-taken contract
/// (409 { error, code: "username_taken", suggestion }).
public struct WireErrorBody: Codable, Sendable {
    public let error: String?
    public let code: String?
    public let suggestion: String?
}

/// GET /api/users/check-username — live @handle availability (onboarding picker).
public struct WireUsernameCheck: Codable, Sendable {
    public let available: Bool
    public let suggestion: String?
}

/// GET registry/handles.json — static-CDN availability source
/// (offline-first fallback when no live gateway answers).
public struct WireHandleRegistry: Codable, Sendable {
    public let reserved: [String]?
    public let taken: [String]?
}

/// Tolerant wrapper: responses arrive as { "conversation": ... } (200 deduped
/// / 201 created) — and defensively as the bare object on older relays.
public struct WireConversationEnvelope: Codable, Sendable {
    public let conversation: WireConversationSummary?

    public static func extract(from data: Data) throws -> WireConversationSummary {
        if let wrapped = try? JSONDecoder().decode(WireConversationEnvelope.self, from: data),
           let conversation = wrapped.conversation {
            return conversation
        }
        return try JSONDecoder().decode(WireConversationSummary.self, from: data)
    }
}

/// Tolerant wrapper: { "message": ... } (POST messages / POST react add a
/// streak + xpAwarded sibling) — falls back to the bare message object.
public struct WireMessageEnvelope: Codable, Sendable {
    public let message: WireChatMessage?

    public static func extract(from data: Data) throws -> WireChatMessage {
        if let wrapped = try? JSONDecoder().decode(WireMessageEnvelope.self, from: data),
           let message = wrapped.message {
            return message
        }
        return try JSONDecoder().decode(WireChatMessage.self, from: data)
    }
}

public struct WireChatMessage: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String
    public let senderId: String
    public let content: String
    public let kind: String
    public let createdAt: String
    public let editedAt: String?
    public let deletedAt: String?
    public let sender: WireSender?
    public let reactions: [WireReactionGroup]?
    public let replyTo: WireReplySnippet?
    public let parentId: String?
    public let imagePath: String?
    public let audioPath: String?
    public let durationMs: Double?
    public let filePath: String?
    public let fileName: String?
    public let pinnedAt: String?
    public let viewOnce: Bool?
    public let anon: Bool?
    public let anonAlias: String?
}

public struct WireMessagesPage: Codable, Sendable {
    public let messages: [WireChatMessage]
    public let hasMore: Bool
    public let total: Int
}

public struct WireConversationMember: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let username: String?
    public let color: String?
    public let avatar: String?
    public let statusEmoji: String?
    public let statusText: String?
    public let lastReadAt: String?
    public let role: String?
}

/// Streak fields arrive as `{"count": n}` objects on the live wire (sometimes
/// enriched with labels); decode flexibly so int/object forms both parse.
public struct WireStreak: Codable, Hashable, Sendable {
    public let count: Int?

    public init(from decoder: Decoder) throws {
        if let obj = try? decoder.container(keyedBy: CodingKeys.self) {
            count = try obj.decodeIfPresent(Int.self, forKey: .count)
        } else if let single = try? decoder.singleValueContainer(),
                  let value = try? single.decode(Int.self) {
            count = value
        } else {
            count = nil
        }
    }

    enum CodingKeys: String, CodingKey { case count }
}

public struct WireConversationSummary: Codable, Hashable, Sendable {
    public let id: String
    public let isGroup: Bool
    public let name: String?
    public let photo: String?
    public let createdAt: String?
    public let updatedAt: String?
    public let members: [WireConversationMember]
    public let lastMessage: WireChatMessage?
    public let unreadCount: Int?
    public let pinnedAt: String?
    public let mutedUntil: String?
    public let archivedAt: String?
    public let ttlSeconds: Int?
    public let broadcastMode: Bool?
    public let isSelf: Bool?
    public let myDraft: String?
    public let myStreak: WireStreak?
    public let deadStreak: WireStreak?
    public let lostStreak: WireStreak?
    /// N10-b — viewer flagged this row mark-as-unread (nil on older relays).
    public let myManualUnread: Bool?

    /// Pin/mute state derived from the wire timestamps (web parity helpers —
    /// ChatsView swipe + context menus read these).
    public var isPinned: Bool { !(pinnedAt ?? "").isEmpty }
    public var isMuted: Bool { !(mutedUntil ?? "").isEmpty }

    /// N10-b — nil-coalescing helper: the mark-as-unread dot flag.
    public var manualUnread: Bool { myManualUnread ?? false }

    /// N10-b — server-truth mute: mutedUntil in the FUTURE, not merely non-null
    /// (expired mutes must not mute — web mutedUntil > Date.now() check).
    public var isMutedNow: Bool {
        guard let mutedUntil, !mutedUntil.isEmpty, let date = PulseFormat.date(mutedUntil) else { return false }
        return date > Date()
    }

    /// N10-b — optimistic helper: replaces one field in the summary.
    public func withMutedUntil(_ value: String?) -> WireConversationSummary {
        WireConversationSummary(
            id: id, isGroup: isGroup, name: name, photo: photo, createdAt: createdAt,
            updatedAt: updatedAt, members: members, lastMessage: lastMessage,
            unreadCount: unreadCount, pinnedAt: pinnedAt, mutedUntil: value,
            archivedAt: archivedAt, ttlSeconds: ttlSeconds, broadcastMode: broadcastMode,
            isSelf: isSelf, myDraft: myDraft, myStreak: myStreak, deadStreak: deadStreak,
            lostStreak: lostStreak, myManualUnread: myManualUnread,
        )
    }

    /// N10-b — optimistic helper: flips the manual-unread flag.
    public func withManualUnread(_ value: Bool) -> WireConversationSummary {
        WireConversationSummary(
            id: id, isGroup: isGroup, name: name, photo: photo, createdAt: createdAt,
            updatedAt: updatedAt, members: members, lastMessage: lastMessage,
            unreadCount: unreadCount, pinnedAt: pinnedAt, mutedUntil: mutedUntil,
            archivedAt: archivedAt, ttlSeconds: ttlSeconds, broadcastMode: broadcastMode,
            isSelf: isSelf, myDraft: myDraft, myStreak: myStreak, deadStreak: deadStreak,
            lostStreak: lostStreak, myManualUnread: value,
        )
    }

    /// N10-b — optimistic helper: sets/clears the archived watermark.
    public func withArchived(_ value: String?) -> WireConversationSummary {
        WireConversationSummary(
            id: id, isGroup: isGroup, name: name, photo: photo, createdAt: createdAt,
            updatedAt: updatedAt, members: members, lastMessage: lastMessage,
            unreadCount: unreadCount, pinnedAt: pinnedAt, mutedUntil: mutedUntil,
            archivedAt: value, ttlSeconds: ttlSeconds, broadcastMode: broadcastMode,
            isSelf: isSelf, myDraft: myDraft, myStreak: myStreak, deadStreak: deadStreak,
            lostStreak: lostStreak, myManualUnread: myManualUnread,
        )
    }

    /// N10-b — optimistic helper: zeroes the unread count.
    public func withUnreadCount(_ value: Int) -> WireConversationSummary {
        WireConversationSummary(
            id: id, isGroup: isGroup, name: name, photo: photo, createdAt: createdAt,
            updatedAt: updatedAt, members: members, lastMessage: lastMessage,
            unreadCount: value, pinnedAt: pinnedAt, mutedUntil: mutedUntil,
            archivedAt: archivedAt, ttlSeconds: ttlSeconds, broadcastMode: broadcastMode,
            isSelf: isSelf, myDraft: myDraft, myStreak: myStreak, deadStreak: deadStreak,
            lostStreak: lostStreak, myManualUnread: myManualUnread,
        )
    }

    /// N10-b — optimistic helper: sets/clears the pinned watermark.
    public func withPinnedAt(_ value: String?) -> WireConversationSummary {
        WireConversationSummary(
            id: id, isGroup: isGroup, name: name, photo: photo, createdAt: createdAt,
            updatedAt: updatedAt, members: members, lastMessage: lastMessage,
            unreadCount: unreadCount, pinnedAt: value, mutedUntil: mutedUntil,
            archivedAt: archivedAt, ttlSeconds: ttlSeconds, broadcastMode: broadcastMode,
            isSelf: isSelf, myDraft: myDraft, myStreak: myStreak, deadStreak: deadStreak,
            lostStreak: lostStreak, myManualUnread: myManualUnread,
        )
    }
}

public struct WireConversationsPage: Codable, Sendable {
    public let conversations: [WireConversationSummary]
}

// ── N10-b home-page wire shapes (tolerant: unreachable features degrade) ──

/// GET /api/stories?requesterId= — 24h status groups. The static CDN gateway
/// has no such route today → decode failures surface as nil, and the UI
/// degrades to the honest My-status-only row.
public struct WireStoryGroup: Codable, Hashable, Sendable {
    public let user: WireSender?
    public let mine: Bool?
    public let allSeen: Bool?
    public let stories: [WireStoryItem]?
}

public struct WireStoryItem: Codable, Hashable, Sendable {
    public let id: String?
    public let createdAt: String?
}

public struct WireStoriesPage: Codable, Sendable {
    public let groups: [WireStoryGroup]?
}

/// GET /api/folders?userId= — Signal/Beeper chat folders.
public struct WireFolder: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let emoji: String?
    public let position: Int?
    public let conversationIds: [String]?
}

public struct WireFoldersPage: Codable, Sendable {
    public let folders: [WireFolder]?
}

/// GET /api/mentions?userId= — only the item count feeds the entry pill.
public struct WireMentionsPage: Codable, Sendable {
    public let items: [WireMentionItem]?
}

public struct WireMentionItem: Codable, Hashable, Sendable {
    public let id: String?
}

/// GET /api/search?userId=&q= — global message search hits.
public struct WireSearchMessage: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String
    public let conversationName: String?
    public let isGroup: Bool?
    public let content: String?
    public let kind: String?
    public let createdAt: String?
    public let deletedAt: String?
    public let imagePath: String?
    public let filePath: String?
    public let fileName: String?
    public let sender: WireSender?
}

public struct WireSearchPage: Codable, Sendable {
    public let messages: [WireSearchMessage]?
    public let total: Int?
}

// ── wire → domain mappers (mirror Android data/repository mappers) ──

extension WireConversationSummary {
    public func toDomain() -> PulseConversation {
        let title = name
            ?? members.first(where: { $0.id != lastMessage?.senderId })?.name
            ?? members.first?.name
            ?? "Conversation"
        let kind: PulseConversation.Kind = broadcastMode == true
            ? .CHANNEL
            : (isGroup ? .GROUP : .DM)
        return PulseConversation(
            id: id,
            kind: kind,
            title: title,
            avatar: photo ?? members.first?.avatar,
            lastMessagePreview: lastMessage.map(Self.preview(of:)),
            lastActivityAt: updatedAt ?? lastMessage?.createdAt,
            unreadCount: unreadCount ?? 0,
            isPinned: !(pinnedAt ?? "").isEmpty,
            isMuted: !(mutedUntil ?? "").isEmpty,
            isArchived: !(archivedAt ?? "").isEmpty,
        )
    }

    static func preview(of m: WireChatMessage) -> String {
        switch m.kind {
        case "image": return "Photo"
        case "voice": return "Voice message"
        case "video": return "Video"
        case "file": return m.fileName ?? "File"
        case "poll": return "Poll"
        default: return m.content
        }
    }
}

extension WireChatMessage {
    /// Tombstone copy for message:deleted socket events. The memberwise
    /// initializer is internal, which is fine — every caller is in-module.
    public func deletedCopy() -> WireChatMessage {
        WireChatMessage(
            id: id, conversationId: conversationId, senderId: senderId,
            content: content, kind: kind, createdAt: createdAt,
            editedAt: editedAt, deletedAt: deletedAt ?? createdAt,
            sender: sender, reactions: reactions, replyTo: replyTo,
            parentId: parentId, imagePath: imagePath, audioPath: audioPath,
            durationMs: durationMs, filePath: filePath, fileName: fileName,
            pinnedAt: pinnedAt, viewOnce: viewOnce, anon: anon, anonAlias: anonAlias
        )
    }

    public func toDomain() -> PulseMessage {
        PulseMessage(
            id: id,
            conversationId: conversationId,
            authorId: senderId,
            authorName: sender?.name ?? "Unknown",
            kind: Self.kindOf(kind),
            body: content,
            createdAt: createdAt,
            editedAt: editedAt,
            deletedAt: deletedAt,
            replyToId: parentId ?? replyTo?.id,
            threadRootId: nil,
            pinnedAt: pinnedAt,
            viewedOnce: viewOnce == true,
        )
    }

    static func kindOf(_ wire: String) -> PulseMessage.Kind {
        switch wire {
        case "text": return .TEXT
        case "image": return .IMAGE
        case "voice": return .VOICE
        case "video": return .VIDEO
        case "file": return .FILE
        case "poll": return .POLL
        default: return .SYSTEM
        }
    }
}

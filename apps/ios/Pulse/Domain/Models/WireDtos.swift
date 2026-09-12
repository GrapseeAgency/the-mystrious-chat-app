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

    /// Lenient variant for POST /api/messages/{id}/unfurl — the route answers
    /// `{ message: null }` when nothing was unfurled (nothing link-ish in the
    /// content / unreachable host). The strict extract above would fall
    /// through to the bare-object decode and THROW on that body; this variant
    /// tolerates "nothing happened" by returning nil. Never throws.
    public static func extractOptional(from data: Data) -> WireChatMessage? {
        // Fall through when the message is MISSING (bare-object bodies decode
        // into an all-optional envelope with message = nil): the bare decode
        // then succeeds for bare rows, fails for true `{message: null}` → nil.
        if let wrapped = try? JSONDecoder().decode(WireMessageEnvelope.self, from: data),
           let message = wrapped.message {
            return message
        }
        return try? JSONDecoder().decode(WireChatMessage.self, from: data)
    }
}

public struct WireChatMessage: Codable, Hashable, Sendable, Identifiable {
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
    public let fileSize: Int?
    public let pinnedAt: String?
    public let viewOnce: Bool?
    public let anon: Bool?
    public let anonAlias: String?
    // W2-DATA-B — Wave 2 additive fields (all optional: old relays never
    // send them and the decoder must stay tolerant both ways).
    /// View-once burn stamp — the FIRST non-sender open, wire ISO string.
    public let viewedAt: String?
    /// Who consumed the view-once attachment. Server column is a SINGLE
    /// userId string (Message.viewedBy String? in prisma/schema.prisma) —
    /// NOT an array, despite what stale fixtures once guessed.
    public let viewedBy: String?
    /// Cached voice-note transcription (real ASR, stored on the row).
    public let transcript: String?
    public let transcribedAt: String?
    /// Zulip-style topic this row is filed under (null = General).
    public let topicId: String?
    /// First link inside the content once the unfurl pipeline consumed it.
    public let linkUrl: String?
    public let linkPreview: WireLinkPreview?
    public let poll: WirePoll?
}

public struct WireMessagesPage: Codable, Sendable {
    public let messages: [WireChatMessage]
    public let hasMore: Bool
    public let total: Int
}

/// GET /api/messages/{id}/thread?userId= — thread-root message + its replies
/// (replies asc). Wave 1 thread sheet page (spec §1.1 "Thread read").
public struct WireThreadPage: Codable, Sendable {
    public let parent: WireChatMessage
    public let replies: [WireChatMessage]
}

/// GET /api/conversations/{id}/pinned?userId= — pinned list (pinnedAt asc).
public struct WirePinnedPage: Codable, Sendable {
    public let messages: [WireChatMessage]
}

/// POST /api/messages/{id}/save {userId} — save/star toggle verdict.
public struct WireSavedToggle: Codable, Sendable {
    public let saved: Bool
}

/// POST /api/uploads {dataUrl} — media upload verdict (JSON, NOT multipart).
public struct WireUploadResult: Codable, Sendable {
    public let filePath: String?
    public let imagePath: String?
}

// ── W2-DATA-B — Wave 2 wire shapes (polls, previews, saved, topics) ──

/// One poll choice inside message.poll. `votedBy` is the vote source of
/// truth (userIds); `myOptionId` on the parent poll is actor-relative, so
/// UI derivation rides votedBy (see WirePoll.pickFor).
public struct WirePollOption: Codable, Hashable, Sendable {
    public let id: String
    public let text: String
    public let position: Int?
    public let voteCount: Int?
    public let votedBy: [String]?
}

/// Live poll card attached to exactly one message. Backend contract
/// (spec §0): NO `multiple`, NO `closesAt` — single-choice, manual close.
/// `myOptionId` is ACTOR-RELATIVE on relayed rows (poll:voted envelopes map
/// the row with the ACTOR as viewer) and null on history GETs — UI must
/// derive the own pick from votedBy ONLY.
public struct WirePoll: Codable, Hashable, Sendable {
    public let id: String
    public let question: String
    public let closed: Bool?
    public let options: [WirePollOption]?
    public let totalVotes: Int?
    public let myOptionId: String?

    /// Own poll pick — derived from votedBy ONLY (web parity, spec §1 row 2).
    /// Rendering myOptionId would show another member's vote as "mine" on
    /// vote/close relays. First option whose votedBy contains the viewer;
    /// nil when the viewer has not voted (or no viewer).
    public func pickFor(_ viewerId: String?) -> String? {
        guard let viewerId else { return nil }
        return (options ?? []).first { $0.votedBy?.contains(viewerId) == true }?.id
    }
}

/// Open-Graph preview inside message.linkPreview (all string|null on the
/// wire — the unfurl route fills og:title/description/image/site_name).
public struct WireLinkPreview: Codable, Hashable, Sendable {
    public let url: String?
    public let title: String?
    public let description: String?
    public let imageUrl: String?
    public let siteName: String?
}

/// Conversation display info on a saved-library item (DM names are resolved
/// to the partner server-side; groups carry the room name).
public struct WireSavedConversation: Codable, Hashable, Sendable {
    public let id: String
    public let isGroup: Bool?
    public let name: String?
}

/// GET /api/users/{id}/saved item — newest-first, cap 100, NO server
/// pagination/search (local filter/search is the native capability).
public struct WireSavedItem: Codable, Hashable, Sendable {
    public let savedAt: String
    public let conversation: WireSavedConversation
    public let message: WireChatMessage
}

public struct WireSavedPage: Codable, Hashable, Sendable {
    public let items: [WireSavedItem]?
}

/// Zulip-style topic. "General" is NOT a row — it is the unfiltered room;
/// only real Topic rows ride this shape.
public struct WireTopic: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let emoji: String?
    public let lastMessageAt: String?
    public let messageCount: Int?
}

public struct WireTopicsPage: Codable, Hashable, Sendable {
    public let topics: [WireTopic]?
}

/// Tolerant wrapper: POST /api/conversations/{id}/topics answers
/// { topic } on BOTH the 200 dedupe and the 201 create — falls back to the
/// bare topic object defensively (older-relay pattern).
public struct WireTopicEnvelope: Codable, Hashable, Sendable {
    public let topic: WireTopic?

    public static func extract(from data: Data) throws -> WireTopic {
        if let wrapped = try? JSONDecoder().decode(WireTopicEnvelope.self, from: data),
           let topic = wrapped.topic {
            return topic
        }
        return try JSONDecoder().decode(WireTopic.self, from: data)
    }
}

/// POST /api/messages/{id}/transcribe verdict — `cached: true` on the
/// second call (the transcript is stored on the message row, never re-billed).
public struct WireTranscribeResult: Codable, Hashable, Sendable {
    public let transcript: String
    public let transcribedAt: String?
    public let cached: Bool?
}

/// DELETE /api/topics/{id} verdict — { ok: true } (creator/admin only).
public struct WireOk: Codable, Hashable, Sendable {
    public let ok: Bool?
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

    /// N10-b — archive state from the wire timestamp (ChatActionSheet reads it).
    public var isArchived: Bool { !(archivedAt ?? "").isEmpty }

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

/// GET /api/stories?requesterId= — 24h status groups (mine first, others by
/// newest story DESC). Fields are tolerant/optional: unknown keys are ignored
/// and a missing field decodes as nil so older builds never crash on drift.
public struct WireStoryGroup: Codable, Hashable, Sendable {
    public let user: WireSender?
    public let mine: Bool?
    public let allSeen: Bool?
    public let stories: [WireStoryItem]?
}

/// One story (Wave 4 widening — the full GET /api/stories item shape).
public struct WireStoryItem: Codable, Hashable, Sendable {
    public let id: String?
    /// "image" | "text" — imagePath != nil ⇒ image (the wire kind is advisory).
    public let kind: String?
    public let imagePath: String?
    public let caption: String?
    /// Gradient palette key (emerald/rose/amber/violet/teal/orange/pink/cyan).
    public let background: String?
    /// ISO-8601 UTC stamps (server truth; expiresAt = createdAt + 24h).
    public let createdAt: String?
    public let expiresAt: String?
    public let viewCount: Int?
    public let viewedByMe: Bool?
}

public struct WireStoriesPage: Codable, Sendable {
    public let groups: [WireStoryGroup]?
}

/// 201 POST /api/stories response — { story: StoryItem }.
public struct WireStoryCreated: Codable, Sendable {
    public let story: WireStoryItem?
}

/// POST /api/stories/{id}/view response — { viewCount, owner? }.
public struct WireStoryViewCount: Codable, Sendable {
    public let viewCount: Int?
    public let owner: Bool?
}

/// One row of the owner-only viewers list (oldest viewer first).
public struct WireStoryViewer: Codable, Hashable, Sendable {
    public let userId: String?
    public let name: String?
    public let username: String?
    public let color: String?
    public let viewedAt: String?
}

/// GET /api/stories/{id}/view response — { viewers: [...] }.
public struct WireStoryViewersPage: Codable, Sendable {
    public let viewers: [WireStoryViewer]?
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
    /// Cache-restore constructor — rebuilds a display-grade summary from a
    /// PulseConversation row (Wave 0 offline rehydration: title, preview,
    /// unread, pin/mute/archive flags). Members/lastMessage are NOT cached;
    /// bubbles fall back to the domain fields. Replaced on first refresh.
    init(cached: PulseConversation) {
        // Fabricate a display-grade last message from the cached preview so
        // offline rows keep their snippet (kind/author are not cached).
        let cachedMessage = cached.lastMessagePreview.map { preview in
            WireChatMessage(
                id: "cached-\(cached.id)",
                conversationId: cached.id,
                senderId: "",
                content: preview,
                kind: "text",
                createdAt: cached.lastActivityAt ?? "",
                editedAt: nil, deletedAt: nil, sender: nil, reactions: nil,
                replyTo: nil, parentId: nil, imagePath: nil, audioPath: nil,
                durationMs: nil, filePath: nil, fileName: nil, fileSize: nil,
                pinnedAt: nil, viewOnce: nil, anon: nil, anonAlias: nil,
                viewedAt: nil, viewedBy: nil, transcript: nil,
                transcribedAt: nil, topicId: nil, linkUrl: nil,
                linkPreview: nil, poll: nil,
            )
        }
        self.init(
            id: cached.id,
            isGroup: cached.kind == .GROUP || cached.kind == .CHANNEL,
            name: cached.kind == .DM ? nil : cached.title,
            photo: cached.avatar,
            createdAt: nil,
            updatedAt: cached.lastActivityAt,
            members: [],
            lastMessage: cachedMessage,
            unreadCount: cached.unreadCount,
            pinnedAt: cached.isPinned ? "cached" : nil,
            mutedUntil: nil,
            archivedAt: cached.isArchived ? "cached" : nil,
            ttlSeconds: nil,
            broadcastMode: cached.kind == .CHANNEL ? true : nil,
            isSelf: false,
            myDraft: nil,
            myStreak: nil,
            deadStreak: nil,
            lostStreak: nil,
            myManualUnread: nil,
        )
    }

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
            fileSize: fileSize, pinnedAt: pinnedAt, viewOnce: viewOnce,
            anon: anon, anonAlias: anonAlias,
            viewedAt: viewedAt, viewedBy: viewedBy, transcript: transcript,
            transcribedAt: transcribedAt, topicId: topicId, linkUrl: linkUrl,
            linkPreview: linkPreview, poll: poll
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
            // Spec §2 row 5 — parentId is the THREAD ROOT; replyToId is ONLY
            // the inline-quote snippet. Never conflate them again.
            replyToId: replyTo?.id,
            threadRootId: parentId,
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

// ─────────────────────────────────────────────────────────────
// W5-f — Wave 5 voice rooms / stage / space (parity spec
// docs/WAVE5-VOICE-SPACES-PARITY-SPEC.md §0). Relay events are
// [String: Any] dictionaries; the tolerant decode helpers below
// mirror PulseSession.decodeMessage (JSONSerialization →
// JSONDecoder) so older/mutated payloads degrade to nil fields,
// never crashes. All rooms are EPHEMERAL — nothing here is stored.
// ─────────────────────────────────────────────────────────────

/// The 21 wire event names (14 C→S + 7 S→C) of the rooms relay,
/// mirroring mini-services/pulse-socket/index.ts handlers and
/// packages/protocol/src/contracts.ts. Shared names (voice:ptt,
/// voice:chunk, voice:transcript) appear once — direction is clear
/// from the emit/receive site.
public enum PulseSocketEvents: String, CaseIterable, Sendable {
    // C→S — voice room (walkie-talkie PTT)
    case voiceJoin = "voice:join"
    case voiceLeave = "voice:leave"
    case voicePtt = "voice:ptt"
    case voiceChunk = "voice:chunk"
    case voiceTranscript = "voice:transcript"
    // C→S — stage room (Clubhouse hierarchy)
    case stageJoin = "stage:join"
    case stageHand = "stage:hand"
    case stageApprove = "stage:approve"
    case stageMute = "stage:mute"
    case stageEnd = "stage:end"
    case stageLeave = "stage:leave"
    // C→S — spatial presence
    case spaceJoin = "space:join"
    case spaceMove = "space:move"
    case spaceLeave = "space:leave"
    // S→C
    case voiceRoster = "voice:roster"
    case stageState = "stage:state"
    case stageEnded = "stage:ended"
    case spaceState = "space:state"

    /// Exactly the 14 client→server events.
    public static let clientToServer: [PulseSocketEvents] = [
        .voiceJoin, .voiceLeave, .voicePtt, .voiceChunk, .voiceTranscript,
        .stageJoin, .stageHand, .stageApprove, .stageMute, .stageEnd, .stageLeave,
        .spaceJoin, .spaceMove, .spaceLeave,
    ]

    /// Exactly the 7 server→client events.
    public static let serverToClient: [PulseSocketEvents] = [
        .voiceRoster, .voicePtt, .voiceChunk, .voiceTranscript,
        .stageState, .stageEnded, .spaceState,
    ]
}

/// One `voice:roster` peer — the public-safe shape the relay emits
/// ({ id, name, username, color }; joinedAt is NOT on the wire but
/// the roster ORDER is by it — tolerated here for forward drift).
public struct WireVoicePeer: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String?
    public let username: String?
    public let color: String?
    public let joinedAt: Double?
}

/// `voice:roster` payload — the roster REPLACES any previous one
/// wholesale (web parity: roster state = server truth).
public struct WireVoiceRoster: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let peers: [WireVoicePeer]?

    public init(conversationId: String?, peers: [WireVoicePeer]?) {
        self.conversationId = conversationId
        self.peers = peers
    }

    /// Tolerant decode from the raw relay dictionary (the Signal carries
    /// the peers array as [[String: Any]]). Each entry decodes
    /// INDIVIDUALLY — a malformed entry (missing id, wrong shape) is
    /// dropped, never poisoning the whole roster.
    public static func decode(conversationId: String, rawPeers: [[String: Any]]) -> WireVoiceRoster? {
        let peers = rawPeers.compactMap { entry -> WireVoicePeer? in
            guard JSONSerialization.isValidJSONObject(entry),
                  let data = try? JSONSerialization.data(withJSONObject: entry) else { return nil }
            return try? JSONDecoder().decode(WireVoicePeer.self, from: data)
        }
        return WireVoiceRoster(conversationId: conversationId, peers: peers)
    }
}

/// `voice:ptt` — the relay echoes to the SENDER too (self ring glows).
/// The server emits `on`; the tolerant decode also accepts `active`.
public struct WireVoicePtt: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let userId: String?
    public let on: Bool?

    public var active: Bool { on ?? false }

    private enum CodingKeys: String, CodingKey { case conversationId, userId, on, active }

    public init(conversationId: String?, userId: String?, on: Bool?) {
        self.conversationId = conversationId
        self.userId = userId
        self.on = on
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        conversationId = try? container.decodeIfPresent(String.self, forKey: .conversationId)
        userId = try? container.decodeIfPresent(String.self, forKey: .userId)
        // `on` is the wire truth; a legacy `active` alone still decodes.
        let decodedOn = (try? container.decodeIfPresent(Bool.self, forKey: .on)) ?? nil
        let decodedActive = (try? container.decodeIfPresent(Bool.self, forKey: .active)) ?? nil
        on = decodedOn ?? decodedActive
    }

    /// Custom encode — `active` is a computed accessor and must never be
    /// synthesized-encoded; the wire only ever carries `on`.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(conversationId, forKey: .conversationId)
        try container.encodeIfPresent(userId, forKey: .userId)
        try container.encodeIfPresent(on, forKey: .on)
    }
}

/// `voice:chunk` — base64 of Int16LE PCM 16 kHz mono, 250 ms = 4000
/// samples per full chunk; seq starts at 1 (server floor()s, relays
/// to the room EXCEPT the sender).
public struct WireVoiceChunk: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let userId: String?
    public let seq: Int?
    public let data: String?
}

/// `voice:transcript` — the server stamps name/color/at (the emitter
/// only sends conversationId/userId/text).
public struct WireVoiceTranscriptEvent: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let userId: String?
    public let name: String?
    public let color: String?
    public let text: String?
    /// Server epoch ms stamp (tolerated — the client sweeps with its own clock).
    public let at: Double?
}

/// POST /api/voice/transcribe verdict (≤280 chars).
public struct WireVoiceTranscriptResult: Codable, Hashable, Sendable {
    public let transcript: String
}

/// One person on a stage roster ({ id, name, color } — public-safe).
public struct WireStagePerson: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String?
    public let color: String?
}

/// `stage:state` — host (null = seat empty, NO auto-promotion),
/// speakers (host first), hands (FIFO by raisedAt), listeners and the
/// additive listenerCount (fallback = listeners.count on old relays).
public struct WireStageState: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let host: WireStagePerson?
    public let speakers: [WireStagePerson]?
    public let hands: [WireStagePerson]?
    public let listeners: [WireStagePerson]?
    public let listenerCount: Int?

    public var speakerList: [WireStagePerson] { speakers ?? [] }
    public var handList: [WireStagePerson] { hands ?? [] }
    public var listenerList: [WireStagePerson] { listeners ?? [] }
    /// The header chip count — the wire field when present, else the row count.
    public var listenerTotal: Int { listenerCount ?? listenerList.count }
}

/// `space:state` player — normalized 0..1 coords, rounded 4dp by the server.
public struct WireSpacePlayer: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String?
    public let color: String?
    public let x: Double?
    public let y: Double?
}

/// `space:state` — FULL state replace (stale players self-heal; the
/// server prunes 5-minute idlers).
public struct WireSpaceState: Codable, Hashable, Sendable {
    public let conversationId: String?
    public let players: [WireSpacePlayer]?
}

/// Pure C→S payload builders for the 14 rooms events (W5-f) — the EXACT
/// shapes mini-services/pulse-socket/index.ts validates. Single source of
/// truth for both PulseSocketClient's typed emit helpers and the session
/// model's emit funnel; unit-tested in VoiceRoomWireTests.
public enum VoiceRoomWire {
    /// Public-safe user dict — { id, name, username, color }.
    public static func user(id: String, name: String, username: String?, color: String?) -> [String: Any] {
        [
            "id": id,
            "name": name,
            "username": username ?? "",
            "color": color ?? "emerald",
        ]
    }

    // ── voice room ───────────────────────────────────────
    public static func voiceJoin(conversationId: String, userId: String, name: String, username: String?, color: String?) -> [String: Any] {
        [
            "conversationId": conversationId,
            "user": user(id: userId, name: name, username: username, color: color),
        ]
    }

    public static func voiceLeave(conversationId: String) -> [String: Any] {
        ["conversationId": conversationId]
    }

    public static func voicePtt(conversationId: String, userId: String, on: Bool) -> [String: Any] {
        ["conversationId": conversationId, "userId": userId, "on": on]
    }

    public static func voiceChunk(conversationId: String, userId: String, seq: Int, data: String) -> [String: Any] {
        ["conversationId": conversationId, "userId": userId, "seq": seq, "data": data]
    }

    public static func voiceTranscript(conversationId: String, userId: String, text: String) -> [String: Any] {
        ["conversationId": conversationId, "userId": userId, "text": text]
    }

    // ── stage room ───────────────────────────────────────
    public static func stageJoin(conversationId: String, userId: String, name: String, username: String?, color: String?, asHost: Bool) -> [String: Any] {
        [
            "conversationId": conversationId,
            "user": user(id: userId, name: name, username: username, color: color),
            "asHost": asHost,
        ]
    }

    public static func stageHand(conversationId: String, userId: String, raised: Bool) -> [String: Any] {
        ["conversationId": conversationId, "user": ["id": userId], "raised": raised]
    }

    public static func stageApprove(conversationId: String, byUserId: String, targetUserId: String) -> [String: Any] {
        ["conversationId": conversationId, "byUserId": byUserId, "targetUserId": targetUserId]
    }

    public static func stageMute(conversationId: String, byUserId: String, targetUserId: String) -> [String: Any] {
        ["conversationId": conversationId, "byUserId": byUserId, "targetUserId": targetUserId]
    }

    public static func stageEnd(conversationId: String, byUserId: String) -> [String: Any] {
        ["conversationId": conversationId, "byUserId": byUserId]
    }

    public static func stageLeave(conversationId: String) -> [String: Any] {
        ["conversationId": conversationId]
    }

    // ── spatial presence ─────────────────────────────────
    public static func spaceJoin(conversationId: String, userId: String, name: String, username: String?, color: String?) -> [String: Any] {
        [
            "conversationId": conversationId,
            "user": user(id: userId, name: name, username: username, color: color),
        ]
    }

    public static func spaceMove(conversationId: String, x: Double, y: Double) -> [String: Any] {
        ["conversationId": conversationId, "x": x, "y": y]
    }

    public static func spaceLeave(conversationId: String) -> [String: Any] {
        ["conversationId": conversationId]
    }
}

// ─────────────────────────────────────────────────────────────
// W3-b — Wave 3 call history (GET/POST /api/calls, src/lib/call-types.ts).
// ─────────────────────────────────────────────────────────────

/// Peer info resolved server-side for history rows (wire CallPeerInfo).
public struct WireCallPeerInfo: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let username: String?
    public let color: String?
    public let avatar: String?
}

/// One call-history row as returned by GET /api/calls (wire CallLogItem).
/// `outgoing` is relative to the LISTING viewer; `peer` is the OTHER party.
public struct WireCallLogItem: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let conversationId: String
    public let callerId: String
    public let calleeId: String
    /// "voice" | "video" (optional on the wire — server toItem always sends it,
    /// tolerant for older relays).
    public let kind: String?
    /// "completed" | "missed" | "declined".
    public let status: String
    public let durationSec: Int?
    public let startedAt: String
    public let outgoing: Bool
    public let peer: WireCallPeerInfo?
}

/// GET /api/calls → { items: [...] } (tolerant items for older relays).
public struct WireCallLogPage: Codable, Hashable, Sendable {
    public let items: [WireCallLogItem]?
}

/// POST /api/calls → 201 { item } — tolerant wrapper that falls back to the
/// bare row object defensively (older-relay pattern, mirrors WireTopicEnvelope).
public struct WireCallLogEnvelope: Codable, Hashable, Sendable {
    public let item: WireCallLogItem?

    public static func extract(from data: Data) throws -> WireCallLogItem {
        if let wrapped = try? JSONDecoder().decode(WireCallLogEnvelope.self, from: data),
           let item = wrapped.item {
            return item
        }
        return try JSONDecoder().decode(WireCallLogItem.self, from: data)
    }
}

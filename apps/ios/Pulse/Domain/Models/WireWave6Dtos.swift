import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 6 — social graph & discovery wire DTOs (F-CP / F-SM /
// F-FD / F-CH). Mirror of the live gateway JSON routes audited in
// worklog "Task ID: 6-a/6-b" — users/{stats,safety,block,blocks,
// report}, channels, invite, mentions entries. Tolerant decode:
// unknown keys ignored; only fields the UI genuinely needs stay
// required, everything else decodes as nil so older relays never
// crash the surface.
// ─────────────────────────────────────────────────────────────

/// GET /api/users/{id}/stats → { stats } — real activity stamps.
public struct WireUserStats: Codable, Hashable, Sendable {
    public let messages: Int?
    public let reactions: Int?
    public let photos: Int?
    public let voiceNotes: Int?
    public let chats: Int?
    public let groups: Int?
    public let days: Int?
    public let joinedAt: String?
    public let lastSeenAt: String?
}

public struct WireUserStatsPage: Codable, Sendable {
    public let stats: WireUserStats
}

/// GET /api/users/{id}/safety?userId= — the pair's shared safety number
/// (server-computed, "12×5 digits space-joined") + THIS viewer's state.
public struct WireSafetyState: Codable, Hashable, Sendable {
    public let peerId: String?
    public let safetyNumber: String
    public let verified: Bool
    public let verifiedAt: String?
}

/// POST /api/users/{id}/safety { userId } → { verified: true, verifiedAt }.
public struct WireSafetyVerdict: Codable, Sendable {
    public let verified: Bool?
    public let verifiedAt: String?
}

/// GET /api/users/{id}/block?userId= → { blocked } (pair state).
public struct WireBlockState: Codable, Sendable {
    public let blocked: Bool
}

/// One row of GET /api/users/{id}/blocks — the owner's block list.
public struct WireBlockedAccount: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String?
    public let username: String?
    public let avatar: String?
    public let color: String?
    public let blockedAt: String?
}

public struct WireBlocksPage: Codable, Sendable {
    public let blocks: [WireBlockedAccount]
}

/// POST /api/users/{id}/report → { reported, updated? } — repeat with the
/// same reason refreshes the row (updated:true) instead of duplicating.
public struct WireReportVerdict: Codable, Sendable {
    public let reported: Bool?
    public let updated: Bool?
}

/// One row of GET /api/users/{id}/report?userId= — the reporter's OWN prior
/// submissions about this account (drives the private "already reported" hint).
public struct WireReportReasonRow: Codable, Hashable, Sendable {
    public let reason: String?
    public let at: String?
}

public struct WireReportHistory: Codable, Sendable {
    public let reasons: [WireReportReasonRow]
}

/// GET /api/channels — one broadcast-channel directory row
/// (ChannelSummary on the wire; name falls back to "Channel" server-side).
public struct WireChannelSummary: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let conversationId: String?
    public let name: String?
    public let description: String?
    public let createdAt: String?
    public let memberCount: Int?
    public let isSubscribed: Bool?
    public let unread: Bool?
    public let preview: String?
    public let photo: String?
}

public struct WireChannelsPage: Codable, Sendable {
    public let channels: [WireChannelSummary]
}

/// 201 POST /api/channels response — { channel } (+ conversationId sibling).
public struct WireChannelCreated: Codable, Sendable {
    public let channel: WireChannelSummary?
}

/// POST /api/channels/{id}/subscribe → { already, memberCount }
/// (never downgrades an admin — already:true is a no-op join).
public struct WireSubscribeResult: Codable, Sendable {
    public let already: Bool?
    public let memberCount: Int?
}

/// DELETE /api/channels/{id}/subscribe → { ok, memberCount }.
public struct WireUnsubscribeResult: Codable, Sendable {
    public let ok: Bool?
    public let memberCount: Int?
}

/// GET /api/invite/{code}?userId= → { invite } — public preview leaking
/// nothing beyond name/count/membership (404 = "no longer valid").
public struct WireInvitePreview: Codable, Hashable, Sendable {
    public let code: String?
    public let conversationId: String?
    public let isGroup: Bool?
    public let name: String?
    public let memberCount: Int?
    public let alreadyMember: Bool?
}

public struct WireInvitePage: Codable, Sendable {
    public let invite: WireInvitePreview
}

/// POST /api/invite/{code}/join { userId } → { conversationId, alreadyMember }.
public struct WireInviteJoinResult: Codable, Sendable {
    public let conversationId: String
    public let alreadyMember: Bool?
}

/// Author chip of a mention row (GET /api/mentions items).
public struct WireMentionAuthor: Codable, Hashable, Sendable {
    public let id: String?
    public let name: String?
    public let color: String?
    public let avatar: String?
}

/// GET /api/mentions?userId=&limit= — one feed row. 14-day window,
/// senderId≠viewer, snippet ≤160 — NO read-state/ack: count = feed length.
public struct WireMentionEntry: Codable, Hashable, Sendable, Identifiable {
    public let messageId: String?
    public let conversationId: String?
    public let conversationName: String?
    public let isGroup: Bool?
    public let author: WireMentionAuthor?
    public let snippet: String?
    public let createdAt: String?

    /// Identifiable over the message id (the feed dedupes on it).
    public var id: String { messageId ?? UUID().uuidString }
}

public struct WireMentionEntriesPage: Codable, Sendable {
    public let items: [WireMentionEntry]?
}

/// { folder } envelope — POST/PATCH /api/folders + PUT …/conversations all
/// answer with the fresh folder row (entries included, position asc).
public struct WireFolderEnvelope: Codable, Sendable {
    public let folder: WireFolder
}

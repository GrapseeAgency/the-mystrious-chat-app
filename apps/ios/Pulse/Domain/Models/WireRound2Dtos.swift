import Foundation

// R2-B - web↔native parity wire DTOs (Round 2):
//   • R39/R41 automations  - GET/POST /api/conversations/[id]/automations,
//                            PATCH/DELETE /api/automations/[id]
//   • webhooks             - GET/POST /api/webhooks, PATCH/DELETE
//                            /api/webhooks/[token]
//   • Section 12           - the integration rails gain the monotonic room
//                            revision + shared keyset page verdict on every
//                            list, durable clientId replay receipts on every
//                            mutation (replayed), the webhook rotatedAt
//                            capability stamp and the WireWebhookMutation
//                            create/rotate/delete receipts.
//   • R34-b AI recap       - POST /api/ai/recap → { recap, basedOn, cached }
//   • R42 per-viewer veil  - PATCH /api/conversations/[id]/screen-privacy
// Mirror of the live route contracts (web src/lib/types.ts AutomationSummary,
// src/app/api/webhooks/route.ts WebhookDTO). Tolerant house decode: only
// id-critical fields stay required; everything else decodes as nil so an
// older relay never crashes the surface.

// MARK: - Automations (R39 keyword auto-replies)

/// The rule author chip ({id,name,color,avatar} - mapAutomation include).
public struct WireAutomationAuthor: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let color: String?
    public let avatar: String?
}

/// One keyword auto-reply rule - web AutomationSummary parity
/// (src/lib/types.ts:330-340). trigger 2-40 chars, reply 1-500 chars,
/// hits = lifetime fire count, lastFiredAt = ISO or null.
public struct WireAutomation: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let conversationId: String?
    public let trigger: String
    public let reply: String?
    public let enabled: Bool?
    public let hits: Int?
    public let lastFiredAt: String?
    public let createdAt: String?
    public let createdBy: WireAutomationAuthor?
}

/// Section 12 item 2 - the shared keyset page verdict both integration
/// lists return (`{ nextCursor: String|null, hasMore: Bool }`); the cursor
/// is the opaque "<epochMs>.<id>" keyset stamp.
public struct WireIntegrationPage: Codable, Sendable {
    public var nextCursor: String? = nil
    public var hasMore: Bool? = nil
}

/// GET /api/conversations/{id}/automations?userId= → { automations: [...] }
/// (createdAt desc, creator included). Section 12 item 2: the room's
/// monotonic integration revision (0 = never bumped) and the page verdict
/// ride along.
public struct WireAutomationsPage: Codable, Sendable {
    public let automations: [WireAutomation]?
    public var revision: Int? = nil
    public var page: WireIntegrationPage? = nil
}

/// POST /api/conversations/{id}/automations → 201 { automation, revision,
/// replayed? } and PATCH /api/automations/{id} → { automation, revision }
/// (tolerant envelope; replayed is present only when a clientId was sent).
public struct WireAutomationEnvelope: Codable, Sendable {
    public let automation: WireAutomation?
    public var revision: Int? = nil
    public var replayed: Bool? = nil
}

// MARK: - Webhooks (Discord-style incoming integrations)

/// One incoming webhook row - web WebhookDTO parity (webhooks/route.ts:19-27).
/// `url` is the RELATIVE ingest path ("/api/webhooks/<token>"); clients pair
/// it with the gateway origin when copying the shareable URL. Section 12
/// item 9: rotatedAt is the ISO instant of the last capability rotation
/// (null = the original token is still the live one).
public struct WireWebhook: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let token: String
    public let avatarColor: String?
    public let url: String?
    public let createdAt: String?
    public let createdBy: String?
    public var rotatedAt: String? = nil
}

/// GET /api/webhooks?conversationId=&requesterId= → { webhooks, revision,
/// page } (createdAt asc keyset; revision = the room's integration
/// revision, page = the shared verdict).
public struct WireWebhooksPage: Codable, Sendable {
    public let webhooks: [WireWebhook]?
    public var revision: Int? = nil
    public var page: WireIntegrationPage? = nil
}

/// Section 12 items 1/9 - the mutation receipts the integration rails
/// answer with. Create/rotate spread the full capability DTO (+ revision,
/// replayed), delete answers { ok, deleted, token, ... } with the SAME
/// verdict on a lost-ACK retry even though the row is gone. Every field
/// tolerates absence so an older relay never crashes the manager surface;
/// the client re-derives the honesty gates in validateWebhookMutation.
public struct WireWebhookMutation: Codable, Sendable {
    public var id: String? = nil
    public var name: String? = nil
    public var token: String? = nil
    public var avatarColor: String? = nil
    public var url: String? = nil
    public var createdAt: String? = nil
    public var createdBy: String? = nil
    public var rotatedAt: String? = nil
    public var revision: Int? = nil
    public var replayed: Bool? = nil
    public var ok: Bool? = nil
    public var deleted: Bool? = nil
}

// MARK: - AI recap (R34-b)

/// POST /api/ai/recap { userId, conversationId }
/// → 200 { recap: string, basedOn: number, cached: boolean }
/// (409 <5 live messages · 502 LLM down - both surface as Failure with the
/// server's verbatim error copy).
public struct WireRecapResult: Codable, Sendable {
    public let recap: String
    public let basedOn: Int?
    public let cached: Bool?
}

// MARK: - Per-viewer screen privacy (R42)

/// PATCH /api/conversations/{id}/screen-privacy { userId, on }
/// → { ok: true, screenPrivacy: boolean } (the viewer's personal flag).
public struct WireScreenPrivacyResult: Codable, Sendable {
    public let ok: Bool?
    public let screenPrivacy: Bool?
}

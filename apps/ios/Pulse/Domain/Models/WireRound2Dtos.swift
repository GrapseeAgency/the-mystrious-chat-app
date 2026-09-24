import Foundation

// ─────────────────────────────────────────────────────────────
// R2-B — web↔native parity wire DTOs (Round 2):
//   • R39/R41 automations  — GET/POST /api/conversations/[id]/automations,
//                            PATCH/DELETE /api/automations/[id]
//   • webhooks             — GET/POST /api/webhooks, DELETE /api/webhooks/[token]
//   • R34-b AI recap       — POST /api/ai/recap → { recap, basedOn, cached }
//   • R42 per-viewer veil  — PATCH /api/conversations/[id]/screen-privacy
// Mirror of the live route contracts (web src/lib/types.ts AutomationSummary,
// src/app/api/webhooks/route.ts WebhookDTO). Tolerant house decode: only
// id-critical fields stay required; everything else decodes as nil so an
// older relay never crashes the surface.
// ─────────────────────────────────────────────────────────────

// MARK: - Automations (R39 keyword auto-replies)

/// The rule author chip ({id,name,color,avatar} — mapAutomation include).
public struct WireAutomationAuthor: Codable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let color: String?
    public let avatar: String?
}

/// One keyword auto-reply rule — web AutomationSummary parity
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

/// GET /api/conversations/{id}/automations?userId= → { automations: [...] }
/// (createdAt desc, creator included).
public struct WireAutomationsPage: Codable, Sendable {
    public let automations: [WireAutomation]?
}

/// POST /api/conversations/{id}/automations → 201 { automation } and
/// PATCH /api/automations/{id} → { automation } (tolerant envelope).
public struct WireAutomationEnvelope: Codable, Sendable {
    public let automation: WireAutomation?
}

// MARK: - Webhooks (Discord-style incoming integrations)

/// One incoming webhook row — web WebhookDTO parity (webhooks/route.ts:19-27).
/// `url` is the RELATIVE ingest path ("/api/webhooks/<token>"); clients pair
/// it with the gateway origin when copying the shareable URL.
public struct WireWebhook: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let token: String
    public let avatarColor: String?
    public let url: String?
    public let createdAt: String?
    public let createdBy: String?
}

/// GET /api/webhooks?conversationId=&requesterId= → { webhooks: [...] }
/// (createdAt asc).
public struct WireWebhooksPage: Codable, Sendable {
    public let webhooks: [WireWebhook]?
}

// MARK: - AI recap (R34-b)

/// POST /api/ai/recap { userId, conversationId }
/// → 200 { recap: string, basedOn: number, cached: boolean }
/// (409 <5 live messages · 502 LLM down — both surface as Failure with the
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

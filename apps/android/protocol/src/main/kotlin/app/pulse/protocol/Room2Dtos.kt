package app.pulse.protocol

import kotlinx.serialization.Serializable

/**
 * Round-2 parity DTOs - R2-A (web ground truth):
 *  · Automations  - R39 keyword auto-replies (automations-sheet.tsx,
 *    /api/conversations/[id]/automations + /api/automations/[id]).
 *  · Webhooks     - Discord-style incoming hooks (group-info-sheet.tsx
 *    WebhooksSection, /api/webhooks).
 *  · AI recap     - R34-b Zoom AI-Companion summary (/api/ai/recap).
 * Section 12 widened the two R2-A lists onto the shared paged envelope
 * ({ revision, page:{nextCursor,hasMore} }), stamped webhook capability
 * rotation (rotatedAt) and replay identity (replayed) onto the mutation
 * receipts; the shapes still mirror src/lib/types.ts + the route handlers
 * and every field stays defaulted so an older gateway degrades honestly.
 */

/** One automation rule row - web `AutomationSummary` (types.ts:330). */
@Serializable
data class AutomationAuthorDto(
    val id: String = "",
    val name: String = "",
    val color: String? = null,
    val avatar: String? = null,
)

@Serializable
data class AutomationDto(
    val id: String = "",
    val conversationId: String = "",
    /** 2-40 chars, matched as a standalone phrase server-side. */
    val trigger: String = "",
    /** 1-500 chars. */
    val reply: String = "",
    val enabled: Boolean = true,
    /** Lifetime fire count (web `hits`). */
    val hits: Long = 0,
    val lastFiredAt: String? = null,
    val createdAt: String? = null,
    val createdBy: AutomationAuthorDto? = null,
)

/** Section 12 - the shared keyed-page envelope of the integration lists. */
@Serializable
data class IntegrationPageDto(
    /** Opaque continuation "<epochMs>.<id>"; null on the last page. */
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/** GET /api/conversations/{id}/automations → { automations[], revision, page? }.
 * Order: createdAt DESC, id DESC; revision 0 = the list was never bumped. */
@Serializable
data class AutomationsPageDto(
    val automations: List<AutomationDto> = emptyList(),
    val revision: Int = 0,
    val page: IntegrationPageDto? = null,
)

/** POST /api/conversations/{id}/automations · PATCH /api/automations/{id}
 * → { automation, revision, replayed? } (replayed receipts ride the Section 12
 * clientId identity). */
@Serializable
data class AutomationEnvelopeDto(
    val automation: AutomationDto = AutomationDto(),
    val revision: Int = 0,
    val replayed: Boolean? = null,
)

/** One incoming webhook row - web `WebhookItem` (group-info-sheet.tsx:97).
 * Section 12 item 7: [rotatedAt] is stamped by capability rotation. */
@Serializable
data class WebhookDto(
    val id: String = "",
    val name: String = "",
    val token: String = "",
    val avatarColor: String = "",
    /** Relative ingest URL - pair with the app origin on the client (route.ts). */
    val url: String = "",
    val createdAt: String? = null,
    val createdBy: String = "",
    val rotatedAt: String? = null,
)

/** GET /api/webhooks?conversationId=&requesterId= → { webhooks[], revision, page? }.
 * Order: createdAt ASC; revision 0 = the list was never bumped. */
@Serializable
data class WebhooksPageDto(
    val webhooks: List<WebhookDto> = emptyList(),
    val revision: Int = 0,
    val page: IntegrationPageDto? = null,
)

/**
 * Section 12 items 6/7/8 - the webhook mutation receipt. Create answers with
 * the bare webhook fields (+ replayed?), rotate answers with the FRESH token
 * spread (+ revision, replayed?), delete answers with the ok/deleted verdict;
 * one defaulted shape parses all three without a nested envelope.
 */
@Serializable
data class WebhookMutationDto(
    val id: String = "",
    val name: String = "",
    val token: String = "",
    val avatarColor: String = "",
    val url: String = "",
    val createdAt: String? = null,
    val createdBy: String = "",
    val rotatedAt: String? = null,
    val revision: Int = 0,
    val replayed: Boolean? = null,
    val ok: Boolean? = null,
    val deleted: Boolean? = null,
)

/** POST /api/ai/recap { userId, conversationId } → 200 { recap, basedOn, cached }. */
@Serializable
data class AiRecapDto(
    val recap: String = "",
    val basedOn: Int = 0,
    val cached: Boolean = false,
)

/** PATCH /api/conversations/{id}/screen-privacy { userId, on } → { ok, screenPrivacy }. */
@Serializable
data class ScreenPrivacyAckDto(
    val ok: Boolean = false,
    val screenPrivacy: Boolean = false,
)

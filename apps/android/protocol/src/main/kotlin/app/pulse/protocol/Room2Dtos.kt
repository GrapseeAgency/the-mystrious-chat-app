package app.pulse.protocol

import kotlinx.serialization.Serializable

/**
 * Round-2 parity DTOs — R2-A (web ground truth):
 *  · Automations  — R39 keyword auto-replies (automations-sheet.tsx,
 *    /api/conversations/[id]/automations + /api/automations/[id]).
 *  · Webhooks     — Discord-style incoming hooks (group-info-sheet.tsx
 *    WebhooksSection, /api/webhooks).
 *  · AI recap     — R34-b Zoom AI-Companion summary (/api/ai/recap).
 * All shapes mirror src/lib/types.ts + the route handlers; every field is
 * defaulted so an older gateway degrades honestly instead of crashing decode.
 */

/** One automation rule row — web `AutomationSummary` (types.ts:330). */
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

/** GET /api/conversations/{id}/automations → { automations[] }. */
@Serializable
data class AutomationsPageDto(
    val automations: List<AutomationDto> = emptyList(),
)

/** POST /api/conversations/{id}/automations · PATCH /api/automations/{id} → { automation }. */
@Serializable
data class AutomationEnvelopeDto(
    val automation: AutomationDto = AutomationDto(),
)

/** One incoming webhook row — web `WebhookItem` (group-info-sheet.tsx:97). */
@Serializable
data class WebhookDto(
    val id: String = "",
    val name: String = "",
    val token: String = "",
    val avatarColor: String = "",
    /** Relative ingest URL — pair with the app origin on the client (route.ts). */
    val url: String = "",
    val createdAt: String? = null,
    val createdBy: String = "",
)

/** GET /api/webhooks?conversationId=&requesterId= → { webhooks[] }. */
@Serializable
data class WebhooksPageDto(
    val webhooks: List<WebhookDto> = emptyList(),
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

package app.pulse.protocol

import kotlinx.serialization.Serializable

/**
 * Wave 6 — social graph & discovery wire DTOs (users / stats / safety /
 * blocks / reports / invites / channels). Tolerant house style: every field
 * defaulted so shape drift can never crash a surface (stories rule).
 *
 * Wire sources (repo-root web app):
 *   src/app/api/users/[id]            → { user: AppUser }
 *   src/app/api/users/[id]/stats      → { stats: UserStats }
 *   src/app/api/users/[id]/safety     → { peerId, safetyNumber, verified, verifiedAt }
 *   src/app/api/users/[id]/block      → { ok, blocked }
 *   src/app/api/users/[id]/blocks     → { blocks: BlockedAccount[] }
 *   src/app/api/users/[id]/report     → { reported, updated? } / { reasons }
 *   src/app/api/invite/[code]         → { invite: InvitePreview }
 *   src/app/api/invite/[code]/join    → { conversationId, alreadyMember }
 *   src/app/api/channels              → { channels: ChannelSummary[] }
 *   src/app/api/channels/[id]/subscribe → { already, memberCount }
 */

/**
 * The full AppUser (GET/PATCH /api/users/{id} → mapUser).
 * `lastSeenAt` is SCRUBBED server-side (null) when the owner hides it —
 * surfaces must render "Last seen hidden", never a guess.
 */
@Serializable
data class FullUserDto(
    val id: String = "",
    val name: String = "",
    val username: String? = null,
    val about: String? = null,
    val color: String? = null,
    val avatar: String? = null,
    val statusEmoji: String? = null,
    val statusText: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class UserEnvelopeDto(
    val user: FullUserDto? = null,
)

/** GET /api/users/{id}/stats → live DB counts (profile page grid). */
@Serializable
data class UserStatsDto(
    val messages: Long = 0,
    val reactions: Long = 0,
    val photos: Long = 0,
    val voiceNotes: Long = 0,
    val chats: Long = 0,
    val groups: Long = 0,
    val days: Long = 0,
    val joinedAt: String? = null,
    /** null = scrubbed by the lastSeenVisible=false privacy choice. */
    val lastSeenAt: String? = null,
)

@Serializable
data class StatsEnvelopeDto(
    val stats: UserStatsDto? = null,
)

/** GET /api/users/{id}/safety?userId= — 12×5 space-joined digits + verify stamp. */
@Serializable
data class SafetyStateDto(
    val peerId: String = "",
    val safetyNumber: String = "",
    val verified: Boolean = false,
    val verifiedAt: String? = null,
)

/** POST /api/users/{id}/safety { userId } → { verified, verifiedAt }. */
@Serializable
data class VerifyAckDto(
    val verified: Boolean = false,
    val verifiedAt: String? = null,
)

/** GET/POST/DELETE /api/users/{id}/block — pair flag (tolerant: ok optional). */
@Serializable
data class BlockStateDto(
    val ok: Boolean? = null,
    val blocked: Boolean = false,
)

/** One row of the owner's block list (GET /api/users/{id}/blocks). */
@Serializable
data class BlockedAccountDto(
    val id: String = "",
    val name: String = "",
    val username: String? = null,
    val avatar: String? = null,
    val color: String? = null,
    val blockedAt: String? = null,
)

@Serializable
data class BlockedPageDto(
    val blocks: List<BlockedAccountDto> = emptyList(),
)

/** POST /api/users/{id}/report → 201 { reported } / 200 { reported, updated }. */
@Serializable
data class ReportAckDto(
    val reported: Boolean = false,
    val updated: Boolean = false,
)

/** GET /api/users/{id}/report?userId= → { reasons: [{reason, at}] } (private). */
@Serializable
data class ReportReasonRowDto(
    val reason: String = "",
    val at: String? = null,
)

@Serializable
data class ReportReasonsPageDto(
    val reasons: List<ReportReasonRowDto> = emptyList(),
)

/** GET /api/invite/[code]?userId= → public preview (name + member count only). */
@Serializable
data class InvitePreviewDto(
    val code: String = "",
    val conversationId: String = "",
    val isGroup: Boolean = true,
    val name: String? = null,
    val memberCount: Int = 0,
    val alreadyMember: Boolean = false,
)

@Serializable
data class InviteEnvelopeDto(
    val invite: InvitePreviewDto? = null,
)

/** POST /api/invite/[code]/join { userId } → idempotent join. */
@Serializable
data class InviteJoinResultDto(
    val conversationId: String = "",
    val alreadyMember: Boolean = false,
)

/** One channel directory row (GET /api/channels) — ChannelSummary wire shape. */
@Serializable
data class ChannelDto(
    val id: String = "",
    /** Wire is `conv.name ?? 'Channel'` — tolerated nullable anyway (mapper falls back). */
    val name: String? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val memberCount: Int = 0,
    val isSubscribed: Boolean = false,
    val unread: Boolean = false,
    /** ≤60-char last-message snippet (server-side '…' elision) or null. */
    val preview: String? = null,
    val photo: String? = null,
)

@Serializable
data class ChannelsPageDto(
    val channels: List<ChannelDto> = emptyList(),
)

/** POST /api/channels → 201 { channel: ChannelSummary & { conversationId } }. */
@Serializable
data class ChannelCreatedDto(
    val channel: ChannelDto? = null,
    val conversationId: String? = null,
)

/** POST /api/channels/[id]/subscribe { userId } → { already, memberCount }. */
@Serializable
data class SubscribeAckDto(
    val already: Boolean = false,
    val memberCount: Int = 0,
)

/** DELETE /api/channels/[id]/subscribe { userId } → { ok, memberCount }. */
@Serializable
data class UnsubscribeAckDto(
    val ok: Boolean = true,
    val memberCount: Int = 0,
)

// ── Wave 6 — client-side profile rules (pure JVM, JVM-tested) ─────────

/**
 * Validation constants + helpers EXACTLY per the audit (web serializers.ts
 * and the profile editors). Single source for both the edit form and tests.
 */
object PulseProfileRules {
    const val NAME_MAX = 32
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 20
    const val ABOUT_MAX = 140
    const val STATUS_EMOJI_MAX = 8
    const val STATUS_TEXT_MAX = 48
    const val REPORT_DETAILS_MAX = 500
    const val FOLDER_NAME_MIN = 1
    const val FOLDER_NAME_MAX = 24
    const val FOLDER_EMOJI_MAX = 16
    const val CHANNEL_NAME_MIN = 2
    const val CHANNEL_NAME_MAX = 40
    const val CHANNEL_DESCRIPTION_MAX = 200
    /** check-username availability debounce (handle-editor.tsx HANDLE_DEBOUNCE_MS). */
    const val HANDLE_DEBOUNCE_MS = 350L

    /** [a-z0-9_]{3,20} — the wire normalizeUsername rule. */
    const val USERNAME_REGEX = "^[a-z0-9_]{3,20}$"

    val usernameRegex: Regex = Regex(USERNAME_REGEX)

    fun isValidUsername(raw: String): Boolean = usernameRegex.matches(raw)

    /** Same sanitization as the web handle editor: lowercase, strip others, cap 20. */
    fun sanitizeUsernameInput(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9_]"), "")
            .take(USERNAME_MAX)

    /** 11 fixed stored glyph values (profile status-glyph.tsx GLYPH_BY_VALUE keys). */
    val STATUS_GLYPHS: List<String> = listOf(
        "🔥", "✨", "🎯", "☕", "🎧", "🌙", "💡", "🚀", "😴", "🍽️", "vacation",
    )

    /** The 8-color avatar whitelist (pulse-utils.ts PULSE_COLORS). */
    val COLOR_WHITELIST: List<String> = listOf(
        "emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan",
    )
}

/**
 * Global-search snippet window (web spotlight parity): clip the matched
 * content to a ≤64-char window so the row shows the hit in context.
 * Start = idx-24 when idx > 28 (else 0), end = idx+query.len+28 — with
 * cut flags so the UI renders the leading/trailing elision honestly.
 */
data class SnippetWindow(val text: String, val leadingCut: Boolean, val trailingCut: Boolean)

fun snippetWindow(content: String, query: String, maxLen: Int = 64): SnippetWindow? {
    val q = query.trim()
    if (q.isEmpty()) return null
    val idx = content.lowercase().indexOf(q.lowercase())
    if (idx < 0) return null
    if (content.length <= maxLen) return SnippetWindow(content, false, false)
    val start = if (idx > 28) idx - 24 else 0
    val end = (idx + q.length + 28).coerceAtMost(content.length)
    return SnippetWindow(
        text = content.substring(start, end),
        leadingCut = start > 0,
        trailingCut = end < content.length,
    )
}

// ── Wave 6 — feed badge cap + safety grid + channel/folder rules (pure) ──

/** Feed-badge label — the mentions/channels header cap ("99+" past 99). */
fun badgeLabel(count: Int): String = if (count > 99) "99+" else "$count"

/**
 * "##### ##### …" → the 12 five-digit group strings for the safety sheet's
 * 3×4 grid. The server already space-joins the 60 digits; this tolerates a
 * bare digit string too (non-digits stripped, zero-padded to 60).
 */
object SafetyGrid {
    fun groups(safetyNumber: String): List<String> {
        val digits = safetyNumber.filter { it.isDigit() }.padStart(60, '0').take(60)
        return (0 until 12).map { digits.substring(it * 5, it * 5 + 5) }
    }
}

/** Channel-create validation (web new-channel segment, verbatim errors). */
object PulseChannelRules {
    fun nameError(name: String): String? = when {
        name.trim().length < PulseProfileRules.CHANNEL_NAME_MIN -> "Name needs at least 2 characters."
        name.trim().length > PulseProfileRules.CHANNEL_NAME_MAX -> "Keep the name under 41 characters."
        else -> null
    }

    fun descriptionError(description: String): String? =
        if (description.length > PulseProfileRules.CHANNEL_DESCRIPTION_MAX) {
            "Keep the description under ${PulseProfileRules.CHANNEL_DESCRIPTION_MAX + 1} characters."
        } else {
            null
        }
}

/** Folder-create/rename validation (web folders-sheet, 1–24 chars). */
object PulseFolderRules {
    fun nameError(name: String): String? =
        if (name.trim().length !in PulseProfileRules.FOLDER_NAME_MIN..PulseProfileRules.FOLDER_NAME_MAX) {
            "Folder names are 1–24 characters."
        } else {
            null
        }
}

/**
 * Client mirror of the /api/mentions rule — the FIRST "@FullName" token in a
 * snippet, case-insensitive, followed by whitespace/end/non-alphanumeric
 * (mentions-page.tsx mentionPatternFor). Returns the token bounds so the UI
 * renders the emerald chip.
 */
fun mentionTokenRange(snippet: String, myName: String): IntRange? {
    val name = myName.trim()
    if (name.isEmpty()) return null
    val escaped = Regex.escape(name)
    // Web parity lookahead: whitespace | end-of-input | non-alphanumeric.
    // ('$' is spliced as a char so it stays a regex anchor, not a literal.)
    val lookahead = "(?=\\s|" + '$' + "|[^A-Za-z0-9])"
    val match = Regex("@$escaped$lookahead", RegexOption.IGNORE_CASE).find(snippet)
    return match?.range
}

// ── Wave 6 — composer @-suggester token math (pure, JVM-tested) ──────

/** An active "@query" token ending at the cursor. */
data class MentionQuery(val start: Int, val query: String)

/**
 * Detect an active @mention token ending at `cursor` — boundary-safe: the @
 * must start the draft or follow whitespace, and the token itself carries no
 * whitespace (it is the partial name typed so far).
 */
fun mentionQueryAt(draft: String, cursor: Int): MentionQuery? {
    if (cursor <= 0 || cursor > draft.length) return null
    val start = draft.lastIndexOf('@', cursor - 1)
    if (start < 0) return null
    if (start > 0 && !draft[start - 1].isWhitespace()) return null
    val token = draft.substring(start + 1, cursor)
    if (token.isEmpty() || token.length > 32) return null
    if (token.any { it.isWhitespace() }) return null
    return MentionQuery(start = start, query = token)
}

/** Replace the active @token ending at `cursor` with "@Name " (trailing space). */
fun mentionApply(draft: String, cursor: Int, name: String): Pair<String, Int>? {
    val active = mentionQueryAt(draft, cursor) ?: return null
    val name = name.trim().takeIf { it.isNotBlank() } ?: return null
    val head = draft.substring(0, active.start) + "@$name "
    return head + draft.substring(cursor) to head.length
}

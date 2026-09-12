package app.pulse.feature.stories

/**
 * Pure composer state (web composer parity, JVM-testable): Text/Photo pill
 * toggle, hard 280-char caption cap with live counter, the 8 gradient
 * palette keys, upload/post flags and the canPost gate.
 *
 * Wire truth (POST /api/stories route validation):
 *  - caption ≤ 280;
 *  - must have photo OR text — "A status needs a photo or some text.";
 *  - background must be one of the 8 palette keys and is a TEXT-story
 *    affordance only (image stories are forced "emerald" server-side).
 */
data class ComposerState(
    val mode: Mode = Mode.TEXT,
    val caption: String = "",
    val background: String = DEFAULT_BACKGROUND,
    /** Uploaded gateway filename (from POST /api/uploads) — non-null ⇒ photo mode has content. */
    val imagePath: String? = null,
    val uploading: Boolean = false,
    val posting: Boolean = false,
    /** User-visible failure copy (snackbar/toast channel). */
    val error: String? = null,
) {

    enum class Mode { TEXT, PHOTO }

    /** Post gate: the exact client-side mirror of the server validation. */
    val canPost: Boolean
        get() = !uploading && !posting && when (mode) {
            Mode.TEXT -> caption.isNotBlank()
            Mode.PHOTO -> !imagePath.isNullOrBlank()
        }

    /** Hard cap: typing past 280 is TRUNCATED (never silently sent and 400'd). */
    fun withCaption(raw: String): ComposerState = copy(
        caption = raw.take(CAPTION_MAX),
        error = null,
    )

    fun withMode(mode: Mode): ComposerState = copy(mode = mode, error = null)

    /** Swatch select — TEXT mode only (ignored in photo mode, matching the wire rule). */
    fun withBackground(key: String): ComposerState =
        if (mode == Mode.TEXT && isValidBackground(key)) {
            copy(background = key, error = null)
        } else {
            this
        }

    /** An uploaded photo flips the composer into photo mode; null clears it. */
    fun withImage(imagePath: String?): ComposerState = copy(
        imagePath = imagePath,
        mode = if (imagePath != null) Mode.PHOTO else Mode.TEXT,
        error = null,
    )

    fun withUploading(flag: Boolean): ComposerState = copy(uploading = flag)
    fun withPosting(flag: Boolean): ComposerState = copy(posting = flag)
    fun withError(message: String): ComposerState = copy(error = message)

    companion object {
        const val CAPTION_MAX = 280
        const val DEFAULT_BACKGROUND = "emerald"

        /** The 8 gradient keys — AVATAR_COLORS on the wire (route validation). */
        val BACKGROUNDS = listOf(
            "emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan",
        )

        fun isValidBackground(key: String): Boolean = key in BACKGROUNDS
    }
}

/**
 * "now" / "Xm" / "Xh" / "Xd" — compact Instagram-style relative stamp
 * (web storyRelativeTime). The day branch is unreachable for live stories
 * (24h TTL) but kept for the owner viewers list.
 */
fun storyRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val mins = ((nowMs - epochMs) / 60_000L).coerceAtLeast(0L)
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}

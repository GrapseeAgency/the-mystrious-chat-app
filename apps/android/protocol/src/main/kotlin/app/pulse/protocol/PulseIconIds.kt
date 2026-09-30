package app.pulse.protocol

/**
 * Stable identifiers for every user-pickable icon in Pulse (folders, topics,
 * profile status). Pure JVM so data/domain/features can all share it.
 *
 * Mirror of web src/lib/icon-ids.ts: the wire value is the id string carried
 * by Folder.emoji / Topic.emoji / AppUser.statusEmoji; each surface maps the
 * id to its own designed glyph (see app.pulse.ui.PulseIconGlyphs). Stored
 * values are never rendered raw - unknown or stale values (including legacy
 * emoji literals from before the id contract) resolve to the registry
 * default on read.
 */

/** User-pickable folder icon ids (Folder.emoji). Default: [FOLDER_ICON_DEFAULT]. */
val FOLDER_ICON_IDS: List<String> = listOf(
    "folder", "briefcase", "game", "heart", "flame", "target", "music", "brain",
)

const val FOLDER_ICON_DEFAULT = "folder"

/** User-pickable topic icon ids (Topic.emoji). Default: [TOPIC_ICON_DEFAULT]. */
val TOPIC_ICON_IDS: List<String> = listOf(
    "chat", "palette", "rocket", "brain", "confetti", "wrench", "pin", "coffee",
)

const val TOPIC_ICON_DEFAULT = "chat"

/** User-pickable profile status icon ids (AppUser.statusEmoji). */
val STATUS_ICON_IDS: List<String> = listOf(
    "flame", "sparkles", "target", "coffee", "headphones", "moon", "bulb",
    "rocket", "sleep", "food", "vacation",
)

const val STATUS_ICON_DEFAULT = "sparkles"

private fun pick(ids: List<String>, value: String?, fallback: String): String =
    if (value != null && value in ids) value else fallback

/** Normalizes a stored folder value to a registry id (stale values to the default). */
fun folderIconId(value: String?): String = pick(FOLDER_ICON_IDS, value, FOLDER_ICON_DEFAULT)

/** Normalizes a stored topic value to a registry id (stale values to the default). */
fun topicIconId(value: String?): String = pick(TOPIC_ICON_IDS, value, TOPIC_ICON_DEFAULT)

/**
 * Normalizes a stored status value: null/blank stays null (no status) so the
 * caller renders "no status"; unknown non-blank values resolve to the default.
 */
fun statusIconIdOrNull(value: String?): String? =
    when {
        value.isNullOrBlank() -> null
        value in STATUS_ICON_IDS -> value
        else -> STATUS_ICON_DEFAULT
    }

package app.pulse.protocol

/**
 * Stable identifiers for every user-pickable icon in Pulse (folders, topics,
 * profile status, message reactions, sticker stamps). Pure JVM so
 * data/domain/features can all share it.
 *
 * Mirror of web src/lib/icon-ids.ts: the wire value is the id string carried
 * by Folder.emoji / Topic.emoji / AppUser.statusEmoji / Reaction.emoji /
 * sticker payload {emoji}; each surface maps the id to its own designed glyph
 * (see app.pulse.ui.PulseIconGlyphs). Stored values are never rendered raw -
 * unknown or stale values (including legacy emoji literals from before the id
 * contract) resolve to the registry default on read. Legacy keys below are
 * written as unicode escapes so the source itself stays emoji-free.
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

// Reactions: message reactions carry one of these stable ids on the wire
// (the Reaction.emoji column keeps its historical name, the value domain is
// ids). Each surface maps the id to its own glyph.

/** User-pickable reaction ids (Reaction.emoji). Default: [REACTION_DEFAULT]. */
val REACTION_IDS: List<String> = listOf(
    "heart", "fire", "laugh", "wow", "sad", "celebrate", "thumbsup",
)

const val REACTION_DEFAULT = "heart"

/** A11y labels for reaction ids (web REACTION_LABELS). */
val REACTION_LABELS: Map<String, String> = mapOf(
    "heart" to "Heart",
    "fire" to "Fire",
    "laugh" to "Laugh",
    "wow" to "Wow",
    "sad" to "Sad",
    "celebrate" to "Celebrate",
    "thumbsup" to "Thumbs up",
)

/**
 * Legacy pre-id reaction values resolve to ids for rendering. Keys are the
 * escaped emoji code points of web REACTION_LEGACY (1:1).
 */
val REACTION_LEGACY: Map<String, String> = mapOf(
    // heart family
    "\u2764" to "heart", // heavy black heart
    "\u2665" to "heart", // black heart suit
    "\uD83E\uDE77" to "heart", // pink heart
    "\uD83E\uDE75" to "heart", // orange heart
    "\uD83E\uDD0D" to "heart", // white heart
    "\uD83E\uDD0E" to "heart", // brown heart
    "\uD83D\uDC96" to "heart", // sparkling heart
    "\uD83D\uDC9D" to "heart", // heart with arrow
    "\uD83D\uDC98" to "heart", // heart with kiss mark
    "\uD83D\uDC97" to "heart", // growing heart
    "\uD83D\uDC93" to "heart", // beating heart
    "\uD83D\uDC9E" to "heart", // revolving hearts
    "\uD83D\uDC95" to "heart", // two hearts
    "\uD83D\uDC9F" to "heart", // heart decoration
    "\uD83E\uDDE1" to "heart", // orange heart
    "\uD83D\uDC9B" to "heart", // yellow heart
    "\uD83D\uDC9A" to "heart", // green heart
    "\uD83D\uDC99" to "heart", // blue heart
    "\uD83D\uDDA4" to "heart", // black heart
    // fire
    "\uD83D\uDD25" to "fire", // fire
    // laugh
    "\uD83D\uDE02" to "laugh", // face with tears of joy
    "\uD83E\uDD23" to "laugh", // rolling on the floor laughing
    "\uD83D\uDE06" to "laugh", // face with open mouth and closed eyes
    "\uD83D\uDE04" to "laugh", // grinning face with smiling eyes
    "\uD83D\uDE03" to "laugh", // grinning face with big eyes
    "\uD83D\uDE00" to "laugh", // grinning face
    "\uD83D\uDE42" to "laugh", // slightly smiling face
    // wow
    "\uD83D\uDE2E" to "wow", // face with open mouth
    "\uD83D\uDE32" to "wow", // astonished face
    "\uD83E\uDD2F" to "wow", // exploding head
    // sad
    "\uD83D\uDE22" to "sad", // crying face
    "\uD83D\uDE2D" to "sad", // loudly crying face
    "\u2639" to "sad", // frowning face
    "\uD83D\uDE41" to "sad", // slightly frowning face
    "\uD83D\uDE1E" to "sad", // disappointed face
    "\uD83D\uDE14" to "sad", // pensive face
    // celebrate
    "\uD83C\uDF89" to "celebrate", // party popper
    "\uD83C\uDF8A" to "celebrate", // confetti ball
    "\uD83E\uDD73" to "celebrate", // partying face
    // thumbs up family
    "\uD83D\uDC4D" to "thumbsup", // thumbs up
    "\uD83D\uDC4F" to "thumbsup", // clapping hands
    "\uD83E\uDEF6" to "thumbsup", // heart hands
    "\uD83E\uDD1D" to "thumbsup", // handshake
    "\uD83D\uDE4F" to "thumbsup", // folded hands
)

/**
 * Resolve a wire reaction value to a reaction id. Ids pass through, legacy
 * emoji values map to their id, anything else falls back to the default so
 * nothing unknown is ever rendered.
 */
fun reactionId(value: String?): String {
    val v = value ?: ""
    if (v in REACTION_IDS) return v
    return REACTION_LEGACY[v] ?: REACTION_DEFAULT
}

// Stamps: the sticker surface. A sticker message carries a stamp id in its
// payload (the payload key keeps its historical name). Each surface maps the
// id to its own large glyph.

/** User-pickable stamp ids (sticker payload emoji). Default: [STAMP_DEFAULT]. */
val STAMP_IDS: List<String> = listOf(
    "bolt", "flame", "sparkles", "rocket", "target", "star",
    "trophy", "crown", "gift", "cake", "music", "heart",
    "palette", "camera", "mic", "gamepad", "brain", "drama",
    "smile", "pin", "sun", "shield", "key", "thumbsup",
    "thumbsdown", "leaf", "moon", "drop", "planet", "coffee", "paw",
)

const val STAMP_DEFAULT = "sparkles"

/** A11y labels for stamp ids (web STAMP_LABELS). */
val STAMP_LABELS: Map<String, String> = mapOf(
    "bolt" to "Bolt",
    "flame" to "Flame",
    "sparkles" to "Sparkles",
    "rocket" to "Rocket",
    "target" to "Target",
    "star" to "Star",
    "trophy" to "Trophy",
    "crown" to "Crown",
    "gift" to "Gift",
    "cake" to "Cake",
    "music" to "Music",
    "heart" to "Heart",
    "palette" to "Palette",
    "camera" to "Camera",
    "mic" to "Microphone",
    "gamepad" to "Gamepad",
    "brain" to "Brain",
    "drama" to "Drama",
    "smile" to "Smile",
    "pin" to "Pin",
    "sun" to "Sun",
    "shield" to "Shield",
    "key" to "Key",
    "thumbsup" to "Thumbs up",
    "thumbsdown" to "Thumbs down",
    "leaf" to "Leaf",
    "moon" to "Moon",
    "drop" to "Drop",
    "planet" to "Planet",
    "coffee" to "Coffee",
    "paw" to "Paw",
)

/**
 * Legacy pre-id sticker values resolve to stamp ids. Keys are the escaped
 * emoji code points of web STAMP_LEGACY (1:1).
 */
val STAMP_LEGACY: Map<String, String> = mapOf(
    // bolt
    "\u26A1" to "bolt", // high voltage
    "\u26A1\uFE0F" to "bolt", // high voltage + variation selector
    "\uD83D\uDCA5" to "bolt", // collision
    "\uD83D\uDCAA" to "bolt", // flexed biceps
    // flame
    "\uD83D\uDD25" to "flame", // fire
    // sparkles
    "\u2728" to "sparkles", // sparkles
    "\uD83C\uDF1F" to "sparkles", // glowing star
    "\uD83D\uDCAB" to "sparkles", // dizzy
    "\u2B50" to "sparkles", // star
    // rocket
    "\uD83D\uDE80" to "rocket", // rocket
    // target
    "\uD83C\uDFAF" to "target", // direct hit
    "\u26BD" to "target", // soccer ball
    // trophy
    "\uD83C\uDFC6" to "trophy", // trophy
    // crown
    "\uD83D\uDC51" to "crown", // crown
    // gift
    "\uD83C\uDF81" to "gift", // gift
    // cake
    "\uD83C\uDF82" to "cake", // birthday cake
    "\uD83C\uDF70" to "cake", // shortcake
    "\uD83C\uDF55" to "cake", // pizza slice (web maps it here)
    // music
    "\uD83C\uDFB5" to "music", // musical note
    "\uD83C\uDFB6" to "music", // musical notes
    // heart family
    "\u2764" to "heart", // heavy black heart
    "\u2764\uFE0F" to "heart", // heavy black heart + variation selector
    "\uD83E\uDDE1" to "heart", // orange heart
    "\uD83D\uDC9B" to "heart", // yellow heart
    "\uD83D\uDC9A" to "heart", // green heart
    "\uD83D\uDC99" to "heart", // blue heart
    "\uD83D\uDC9C" to "heart", // purple heart
    "\uD83D\uDDA4" to "heart", // black heart
    "\uD83D\uDC96" to "heart", // sparkling heart
    "\uD83D\uDC98" to "heart", // heart with kiss mark
    "\uD83D\uDC9E" to "heart", // revolving hearts
    "\uD83E\uDEF6" to "heart", // heart hands
    // palette
    "\uD83C\uDFA8" to "palette", // artist palette
    // camera
    "\uD83D\uDCF7" to "camera", // camera
    "\uD83D\uDCF8" to "camera", // camera with flash
    // mic
    "\uD83C\uDFA4" to "mic", // microphone
    "\uD83C\uDF99" to "mic", // studio microphone
    // gamepad
    "\uD83C\uDFAE" to "gamepad", // video game
    // brain
    "\uD83E\uDDE0" to "brain", // brain
    // drama
    "\uD83C\uDFAD" to "drama", // performing arts
    // smile family
    "\uD83D\uDE00" to "smile", // grinning face
    "\uD83D\uDE02" to "smile", // tears of joy
    "\uD83E\uDD79" to "smile", // melting face
    "\uD83D\uDE0D" to "smile", // smiling face with heart-eyes
    "\uD83D\uDE0E" to "smile", // smiling face with sunglasses
    "\uD83E\uDD14" to "smile", // thinking face
    "\uD83D\uDE34" to "moon", // sleeping face (web maps to moon)
    "\uD83E\uDD73" to "smile", // partying face
    "\uD83D\uDE4C" to "smile", // raised hands
    "\uD83D\uDC4B" to "smile", // waving hand
    "\uD83D\uDC4D" to "thumbsup", // thumbs up
    "\uD83D\uDC4E" to "thumbsdown", // thumbs down
    "\uD83D\uDE4F" to "smile", // folded hands
    "\uD83D\uDC4F" to "smile", // clapping hands
    "\uD83E\uDD1D" to "smile", // handshake
    "\uD83D\uDE05" to "smile", // grinning face with sweat
    "\uD83E\uDEE1" to "smile", // saluting face
    "\uD83E\uDD0C" to "smile", // pinching hand
    "\uD83E\uDD17" to "smile", // hugging face
    // sun
    "\u2600" to "sun", // sun
    "\u2600\uFE0F" to "sun", // sun + variation selector
    // leaf
    "\uD83C\uDF08" to "leaf", // rainbow (web maps it here)
    // moon
    "\uD83C\uDF19" to "moon", // crescent moon
    // coffee
    "\u2615" to "coffee", // hot beverage
    // paw family
    "\uD83D\uDC3E" to "paw", // paw prints
    "\uD83D\uDC36" to "paw", // dog face
    "\uD83D\uDC31" to "paw", // cat face
    "\uD83D\uDC3C" to "paw", // panda
    "\uD83E\uDD8A" to "paw", // fox
    "\uD83D\uDC38" to "paw", // frog
    "\uD83D\uDC35" to "paw", // monkey face
    "\uD83E\uDD84" to "paw", // unicorn
    "\uD83D\uDC19" to "paw", // octopus
    "\uD83E\uDD8B" to "paw", // butterfly
    "\uD83D\uDC22" to "paw", // turtle
    // planet
    "\uD83C\uDF0D" to "planet", // globe europe-africa
    "\uD83C\uDF0E" to "planet", // globe americas
)

/**
 * Resolve a wire stamp value. Ids pass through, legacy emoji values map to
 * their stamp, anything else falls back to the default.
 */
fun stampId(value: String?): String {
    val v = value ?: ""
    if (v in STAMP_IDS) return v
    return STAMP_LEGACY[v] ?: STAMP_DEFAULT
}

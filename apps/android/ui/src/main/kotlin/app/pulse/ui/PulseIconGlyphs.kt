package app.pulse.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.ui.graphics.vector.ImageVector
import app.pulse.protocol.folderIconId
import app.pulse.protocol.reactionId
import app.pulse.protocol.stampId
import app.pulse.protocol.statusIconIdOrNull
import app.pulse.protocol.topicIconId

/**
 * R18 icon-id contract - the native glyph mapping for the stable icon ids
 * carried on the wire (Folder.emoji / Topic.emoji / AppUser.statusEmoji).
 * The web renders ids through Phosphor (src/components/ui/icons.tsx +
 * profile/status-glyph.tsx); Android renders the same ids through the
 * Material filled set the app already ships. Raw values are never rendered:
 * every accessor normalizes through the registry, so stale or unknown
 * values (including legacy emoji literals) resolve to the default glyph.
 */

/** Material glyph for a stored folder icon id (stale values to the default). */
fun pulseFolderGlyph(value: String?): ImageVector = when (folderIconId(value)) {
    "briefcase" -> Icons.Filled.Work
    "game" -> Icons.Filled.SportsEsports
    "heart" -> Icons.Filled.Favorite
    "flame" -> Icons.Filled.Whatshot
    "target" -> Icons.Filled.TrackChanges
    "music" -> Icons.Filled.MusicNote
    "brain" -> Icons.Filled.Psychology
    else -> Icons.Filled.Folder
}

/** Material glyph for a stored topic icon id (stale values to the default). */
fun pulseTopicGlyph(value: String?): ImageVector = when (topicIconId(value)) {
    "palette" -> Icons.Filled.Palette
    "rocket" -> Icons.Filled.RocketLaunch
    "brain" -> Icons.Filled.Psychology
    "confetti" -> Icons.Filled.Celebration
    "wrench" -> Icons.Filled.Build
    "pin" -> Icons.Filled.PushPin
    "coffee" -> Icons.Filled.LocalCafe
    else -> Icons.Filled.ChatBubble
}

/**
 * Material glyph for a stored profile status value: null when there is no
 * status (null/blank wire value); unknown values resolve to the default.
 */
fun pulseStatusGlyph(value: String?): ImageVector? = when (statusIconIdOrNull(value)) {
    null -> null
    "flame" -> Icons.Filled.Whatshot
    "sparkles" -> Icons.Filled.AutoAwesome
    "target" -> Icons.Filled.TrackChanges
    "coffee" -> Icons.Filled.LocalCafe
    "headphones" -> Icons.Filled.Headset
    "moon" -> Icons.Filled.DarkMode
    "bulb" -> Icons.Filled.Lightbulb
    "rocket" -> Icons.Filled.RocketLaunch
    "sleep" -> Icons.Filled.Bedtime
    "food" -> Icons.Filled.Restaurant
    "vacation" -> Icons.Filled.Flight
    else -> Icons.Filled.AutoAwesome
}

/** Material glyph for a KNOWN status icon id (picker/registry use). */
fun pulseStatusGlyphFor(id: String): ImageVector =
    pulseStatusGlyph(id) ?: Icons.Filled.AutoAwesome

/** Picker label for a status icon id (web status-glyph.tsx LABEL_BY_ID). */
fun pulseStatusLabel(id: String): String = when (id) {
    "flame" -> "On fire"
    "sparkles" -> "Sparkles"
    "target" -> "Focused"
    "coffee" -> "Coffee break"
    "headphones" -> "Listening"
    "moon" -> "Night owl"
    "bulb" -> "Ideas"
    "rocket" -> "Shipping"
    "sleep" -> "Sleeping"
    "food" -> "Eating"
    "vacation" -> "On vacation"
    else -> "Sparkles"
}

/**
 * R19-a - Material glyph for a wire REACTION value (web reactions render ids
 * through vector glyphs too). The stored value is normalized first, so legacy
 * emoji rows resolve to their id's glyph and unknown values to the default.
 */
fun pulseReactionGlyph(value: String?): ImageVector = when (reactionId(value)) {
    "fire" -> Icons.Filled.LocalFireDepartment
    "laugh" -> Icons.Filled.EmojiEmotions
    "wow" -> Icons.Filled.Visibility
    "sad" -> Icons.Filled.SentimentDissatisfied
    "celebrate" -> Icons.Filled.Celebration
    "thumbsup" -> Icons.Filled.ThumbUp
    else -> Icons.Filled.Favorite
}

/** Material glyph for a KNOWN reaction id (picker/registry use). */
fun pulseReactionGlyphFor(id: String): ImageVector = pulseReactionGlyph(id)

/** A11y label for a wire reaction value (web REACTION_LABELS). */
fun pulseReactionLabel(value: String?): String =
    app.pulse.protocol.REACTION_LABELS[reactionId(value)] ?: "Heart"

/**
 * R19-a - Material glyph for a wire STAMP value (sticker render + picker).
 * Legacy emoji sticker values resolve through the normalizer, unknown values
 * render the default stamp - a raw value never reaches the screen.
 */
fun pulseStampGlyph(value: String?): ImageVector = when (stampId(value)) {
    "bolt" -> Icons.Filled.Bolt
    "flame" -> Icons.Filled.LocalFireDepartment
    "rocket" -> Icons.Filled.RocketLaunch
    "target" -> Icons.Filled.GpsFixed
    "star" -> Icons.Filled.Star
    "trophy" -> Icons.Filled.EmojiEvents
    "crown" -> Icons.Filled.WorkspacePremium
    "gift" -> Icons.Filled.CardGiftcard
    "cake" -> Icons.Filled.Cake
    "music" -> Icons.Filled.MusicNote
    "heart" -> Icons.Filled.Favorite
    "palette" -> Icons.Filled.Palette
    "camera" -> Icons.Filled.PhotoCamera
    "mic" -> Icons.Filled.Mic
    "gamepad" -> Icons.Filled.SportsEsports
    "brain" -> Icons.Filled.Psychology
    "drama" -> Icons.Filled.TheaterComedy
    "smile" -> Icons.Filled.SentimentSatisfied
    "pin" -> Icons.Filled.PushPin
    "sun" -> Icons.Filled.WbSunny
    "shield" -> Icons.Filled.Shield
    "key" -> Icons.Filled.Key
    "thumbsup" -> Icons.Filled.ThumbUp
    "thumbsdown" -> Icons.Filled.ThumbDown
    "leaf" -> Icons.Filled.Eco
    "moon" -> Icons.Filled.DarkMode
    "drop" -> Icons.Filled.WaterDrop
    "planet" -> Icons.Filled.Public
    "coffee" -> Icons.Filled.LocalCafe
    "paw" -> Icons.Filled.Pets
    else -> Icons.Filled.AutoAwesome
}

/** Material glyph for a KNOWN stamp id (picker/registry use). */
fun pulseStampGlyphFor(id: String): ImageVector = pulseStampGlyph(id)

/** A11y label for a wire stamp value (web STAMP_LABELS). */
fun pulseStampLabel(value: String?): String =
    app.pulse.protocol.STAMP_LABELS[stampId(value)] ?: "Sparkles"

package app.pulse.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import app.pulse.protocol.folderIconId
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

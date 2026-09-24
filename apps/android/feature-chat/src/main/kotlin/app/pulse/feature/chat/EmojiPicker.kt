package app.pulse.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * R5-B ITEM 1 — the composer EMOJI picker (web chat-room.tsx:5382-5418).
 *
 * This is a DRAFT-EDIT engine, deliberately distinct from the sticker picker
 * (StickerPickerSheet — F-MS-24): stickers SEND immediately as kind:"sticker"
 * messages, while this popup only APPENDS the chosen emoji to the current
 * draft text at cursor end and keeps the composer focused. The 24 choices are
 * byte-identical to web EMOJI_PICKER_CHOICES (src/lib/pulse-utils.ts:146-149),
 * laid out in the same 8-column grid (3 rows × 8, 2dp-ish gaps, oversized
 * glyphs).
 */
val EMOJI_PICKER_CHOICES: List<String> = listOf(
    "😀", "😂", "🥹", "😍", "😎", "🤔", "😴", "🥳",
    "👍", "🙏", "👏", "🔥", "❤️", "💜", "✨", "🎉",
    "🚀", "🌈", "☀️", "🌙", "☕", "🍕", "🎂", "⚽",
)

/** Web grid is 8 columns wide (272px popover, gap 0.5 = 2dp). */
internal val EMOJI_GRID_COLUMNS = 8

/**
 * Compact emoji popup anchored above the composer. Web parity details:
 * rounded-2xl container, p-2 (8dp) padding, 8-col grid with 2dp gaps,
 * ~text-xl glyphs, per-cell aria-label "Insert {emoji}". Tapping a cell does
 * NOT close the popup (the web keeps it open for multi-append combos) —
 * dismissal is an outside tap or a second tap on the smile button.
 */
@Composable
internal fun EmojiPickerPopup(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, -8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .padding(8.dp)
                    .semantics { contentDescription = "Emoji picker" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EMOJI_PICKER_CHOICES.chunked(EMOJI_GRID_COLUMNS).forEach { rowEmojis ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        rowEmojis.forEach { emoji -> EmojiCell(emoji, onPick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, onPick: (String) -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = { onPick(emoji) })
            .semantics { contentDescription = "Insert $emoji" },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 21.sp)
    }
}

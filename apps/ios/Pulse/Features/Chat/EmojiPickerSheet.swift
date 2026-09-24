import SwiftUI

// ─────────────────────────────────────────────────────────────
// R5-A Item 1 — composer emoji picker (web chat-room.tsx:5385-5412
// parity). The Smile button in the composer row opens this compact
// sheet: the EXACT 24 web choices in an 8-column grid (3 rows × 8),
// a tap APPENDS the emoji to the current draft (never replaces it)
// and the host re-focuses the composer so the keyboard stays up.
// Deliberately DISTINCT from StickerPickerSheet — stickers send a
// kind:'sticker' message immediately; this only edits the draft text.
// Choices are byte-identical to EMOJI_PICKER_CHOICES
// (src/lib/pulse-utils.ts:146-149).
// ─────────────────────────────────────────────────────────────

/// Web EMOJI_PICKER_CHOICES mirror — 24 glyphs, 3 rows × 8.
enum PulseEmojiChoices {
    static let picker: [String] = [
        "😀", "😂", "🥹", "😍", "😎", "🤔", "😴", "🥳",
        "👍", "🙏", "👏", "🔥", "❤️", "💜", "✨", "🎉",
        "🚀", "🌈", "☀️", "🌙", "☕", "🍕", "🎂", "⚽",
    ]
}

struct EmojiPickerSheet: View {
    /// Fired on tap with the picked emoji — the host appends it to the draft.
    let onPick: (String) -> Void

    private let columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 2), count: 8)

    var body: some View {
        VStack(spacing: 10) {
            Capsule()
                .fill(Color.secondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .padding(.top, 8)
            Text("Emoji")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            LazyVGrid(columns: columns, spacing: 2) {
                ForEach(PulseEmojiChoices.picker, id: \.self) { emoji in
                    Button {
                        onPick(emoji)
                    } label: {
                        Text(emoji)
                            .font(.system(size: 24))
                            .frame(maxWidth: .infinity, minHeight: 42)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Insert \(emoji)")
                }
            }
            .padding(.horizontal, 8)
            Spacer(minLength: 0)
        }
        .presentationDetents([.height(300)])
        .presentationDragIndicator(.visible)
    }
}

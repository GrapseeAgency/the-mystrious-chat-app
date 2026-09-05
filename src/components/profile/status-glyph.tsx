// ─────────────────────────────────────────────────────────────
// Pulse — status glyph layer (R25-b).
// The custom-status field stores a short glyph string (legacy emoji
// values persisted by the old picker + 'vacation'). This layer renders
// those stored values as Lucide icons ONLY, so no raw emoji ever
// reaches the R25 UI while the persisted data contract stays intact
// (other surfaces may still render the stored string verbatim).
// ─────────────────────────────────────────────────────────────
'use client'

import {
  BedDouble,
  Coffee,
  Flame,
  Headphones,
  Lightbulb,
  Moon,
  Plane,
  Rocket,
  Sparkles,
  Target,
  Utensils,
  type LucideIcon,
} from 'lucide-react'

export interface StatusGlyphChoice {
  /** exact string persisted to `AppUser.statusEmoji` */
  value: string
  icon: LucideIcon
  label: string
}

/** Module-scope record — value → Lucide icon (never an emoji). */
const GLYPH_BY_VALUE: Record<string, LucideIcon> = {
  '🔥': Flame,
  '✨': Sparkles,
  '🎯': Target,
  '☕': Coffee,
  '🎧': Headphones,
  '🌙': Moon,
  '💡': Lightbulb,
  '🚀': Rocket,
  '😴': BedDouble,
  '🍽️': Utensils,
  vacation: Plane,
}

const GLYPH_LABELS: Record<string, string> = {
  '🔥': 'On fire',
  '✨': 'Sparkles',
  '🎯': 'Focused',
  '☕': 'Coffee break',
  '🎧': 'Listening',
  '🌙': 'Night owl',
  '💡': 'Ideas',
  '🚀': 'Shipping',
  '😴': 'Sleeping',
  '🍽️': 'Eating',
  vacation: 'On vacation',
}

/** Picker choices — `value` is what the PATCH /api/users/[id] route stores. */
export const STATUS_GLYPH_CHOICES: Array<StatusGlyphChoice> =
  Object.keys(GLYPH_BY_VALUE).map((value) => ({
    value,
    icon: GLYPH_BY_VALUE[value] ?? Sparkles,
    label: GLYPH_LABELS[value] ?? value,
  }))

/** Resolve a stored status value to its Lucide icon (never returns an emoji). */
export function statusGlyphFor(value: string | null | undefined): LucideIcon {
  if (!value) return Sparkles
  return GLYPH_BY_VALUE[value] ?? Sparkles
}

/** Icon for a stored status value, ready to drop into hero/status rows. */
export function StatusGlyph({
  value,
  className,
}: {
  value: string | null | undefined
  className?: string
}) {
  // module-scope record member access — stable component references only
  const Icon: LucideIcon = GLYPH_BY_VALUE[value ?? ''] ?? Sparkles
  return <Icon className={className} aria-hidden />
}

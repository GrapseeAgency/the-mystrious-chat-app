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
import { type StatusIconId, statusIconId } from '@/lib/icon-ids'

export interface StatusGlyphChoice {
  /** id persisted to AppUser.statusEmoji via PATCH /api/users/[id] */
  value: StatusIconId
  icon: LucideIcon
  label: string
}

const GLYPH_BY_ID: Record<StatusIconId, LucideIcon> = {
  flame: Flame,
  sparkles: Sparkles,
  target: Target,
  coffee: Coffee,
  headphones: Headphones,
  moon: Moon,
  bulb: Lightbulb,
  rocket: Rocket,
  sleep: BedDouble,
  food: Utensils,
  vacation: Plane,
}

const LABEL_BY_ID: Record<StatusIconId, string> = {
  flame: 'On fire',
  sparkles: 'Sparkles',
  target: 'Focused',
  coffee: 'Coffee break',
  headphones: 'Listening',
  moon: 'Night owl',
  bulb: 'Ideas',
  rocket: 'Shipping',
  sleep: 'Sleeping',
  food: 'Eating',
  vacation: 'On vacation',
}

/** Picker choices for the profile status editor. */
export const STATUS_GLYPH_CHOICES: Array<StatusGlyphChoice> = Object.keys(
  GLYPH_BY_ID,
).map((id) => ({
  value: id as StatusIconId,
  icon: GLYPH_BY_ID[id as StatusIconId],
  label: LABEL_BY_ID[id as StatusIconId],
}))

/** Label for a stored status id; empty string when there is no status. */
export function statusLabelFor(value: string | null | undefined): string {
  const id = statusIconId(value)
  return id ? LABEL_BY_ID[id] : ''
}

/** Icon for a stored status value, ready to drop into hero/status rows. */
export function statusGlyphFor(value: string | null | undefined): LucideIcon {
  const id = statusIconId(value)
  return id ? GLYPH_BY_ID[id] : Sparkles
}

export function StatusGlyph({
  value,
  className,
}: {
  value: string | null | undefined
  className?: string
}) {
  const id = statusIconId(value)
  if (!id) return null
  // module-scope record member access: stable component reference
  const Icon = GLYPH_BY_ID[id]
  return <Icon className={className} aria-hidden />
}

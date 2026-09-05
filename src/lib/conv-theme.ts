// ─────────────────────────────────────────────────────────────
// Pulse — per-conversation chat themes (R29-a).
// iMessage-style per-room wallpaper/tint stored INSIDE the existing
// User.preferences JSON blob under the key `chat.convThemes`.
//
// Contract:
//  - ConvThemeMap = Record<conversationId, { wallpaper, tint? }>
//  - Wallpaper values use the EXACT same option set as the global
//    Appearance setting (PulsePrefs['wallpaper'] — prefs-defaults).
//  - Tint is a per-conversation accent layered over the wallpaper
//    glows (chat-room.tsx); the global system has no tint concept.
//  - Effective wallpaper for a room = override ?? prefs.wallpaper.
//    Clearing the override falls back to the global default.
//
// Pure functions only — imported by BOTH the client store path
// (prefs-defaults mergePrefs sanitization) and chat UI. No DOM.
// ─────────────────────────────────────────────────────────────

import type { PulsePrefs } from '@/lib/prefs-defaults'

/** Prefs blob key carrying the per-conversation theme map. */
export const CONV_THEMES_KEY = 'chat.convThemes' as const

/** Accent tint layered over the room wallpaper (per-conversation only). */
export type ConvTint = 'emerald' | 'rose' | 'amber' | 'violet' | 'teal'

/** One conversation's theme. `wallpaper` mirrors PulsePrefs['wallpaper']. */
export type ConvTheme = {
  wallpaper: PulsePrefs['wallpaper']
  tint?: ConvTint
}

/** conversationId → theme override. Missing id = follows the global default. */
export type ConvThemeMap = Record<string, ConvTheme>

/**
 * Wallpaper options — the SAME set the global Appearance picker uses
 * (prefs-defaults `WALLPAPER`). Typed against PulsePrefs['wallpaper'] so
 * the two lists can never drift apart: adding/removing a global wallpaper
 * token without updating this list is a compile error.
 */
export const CONV_WALLPAPERS: readonly PulsePrefs['wallpaper'][] = [
  'none',
  'aurora',
  'dusk',
  'forest',
  'mono',
]

/** Tint chips shown in the per-conversation picker. */
export const CONV_TINTS: readonly ConvTint[] = ['emerald', 'rose', 'amber', 'violet', 'teal']

/** Tint presentation: label, chip swatch class, glow color used by chat-room. */
export const CONV_TINT_META: Record<ConvTint, { label: string; swatch: string; glow: string }> = {
  emerald: { label: 'Emerald', swatch: 'bg-emerald-500', glow: 'rgba(16,185,129,0.17)' },
  rose: { label: 'Rose', swatch: 'bg-rose-500', glow: 'rgba(244,63,94,0.16)' },
  amber: { label: 'Amber', swatch: 'bg-amber-500', glow: 'rgba(245,158,11,0.16)' },
  violet: { label: 'Violet', swatch: 'bg-violet-500', glow: 'rgba(139,92,246,0.17)' },
  teal: { label: 'Teal', swatch: 'bg-teal-500', glow: 'rgba(20,184,166,0.16)' },
}

/**
 * Wallpaper swatch previews — byte-for-byte the same Tailwind classes the
 * settings Appearance picker renders (settings-screen WALLPAPERS), so the
 * per-conversation picker tiles look identical to the global ones.
 */
export const WALLPAPER_META: ReadonlyArray<{
  id: PulsePrefs['wallpaper']
  label: string
  preview: string
}> = [
  { id: 'none', label: 'None', preview: 'bg-zinc-100 dark:bg-zinc-800' },
  {
    id: 'aurora',
    label: 'Aurora',
    preview:
      'bg-gradient-to-br from-emerald-200 via-teal-200 to-emerald-400 dark:from-emerald-900 dark:via-teal-950 dark:to-emerald-700',
  },
  {
    id: 'dusk',
    label: 'Dusk',
    preview:
      'bg-gradient-to-br from-amber-200 via-rose-300 to-zinc-400 dark:from-amber-950 dark:via-rose-950 dark:to-zinc-800',
  },
  {
    id: 'forest',
    label: 'Forest',
    preview:
      'bg-gradient-to-br from-lime-200 via-emerald-300 to-green-500 dark:from-green-950 dark:via-emerald-900 dark:to-green-700',
  },
  {
    id: 'mono',
    label: 'Mono',
    preview: 'bg-gradient-to-br from-zinc-200 to-zinc-400 dark:from-zinc-700 dark:to-zinc-900',
  },
]

/**
 * Hard cap on stored overrides. The /api/settings PATCH enforces a 4KB
 * serialized prefs budget shared with every other pref — at ~70 bytes per
 * entry (cuid + wallpaper + tint) 48 entries stays comfortably inside it.
 * Beyond the cap the sanitizer silently drops further entries.
 */
const MAX_CONV_THEMES = 48

/** conversation ids are cuid-style tokens — keep the stored keys conservative. */
const CONV_ID_OK = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isWallpaperToken(v: string): v is PulsePrefs['wallpaper'] {
  return (CONV_WALLPAPERS as readonly string[]).includes(v)
}

function isTint(v: string): v is ConvTint {
  return (CONV_TINTS as readonly string[]).includes(v)
}

/**
 * Sanitized loader for the `chat.convThemes` blob. Never throws, never
 * returns junk: non-object input → undefined; entries with unknown
 * conversation-id shapes, unknown wallpaper tokens or unknown tints are
 * dropped; the MAX_CONV_THEMES budget caps payload size. Valid entries
 * survive byte-identical.
 */
export function sanitizeConvThemeMap(raw: unknown): ConvThemeMap | undefined {
  if (!isRecord(raw)) return undefined
  const out: ConvThemeMap = {}
  let count = 0
  for (const [key, value] of Object.entries(raw)) {
    if (count >= MAX_CONV_THEMES) break
    // prototype-pollution + junk key guard (out is a plain object literal)
    if (key === '__proto__' || key === 'constructor' || key === 'prototype') continue
    if (!CONV_ID_OK.test(key)) continue
    if (!isRecord(value)) continue
    const wallpaper = value.wallpaper
    if (typeof wallpaper !== 'string' || !isWallpaperToken(wallpaper)) continue
    const tint = value.tint
    if (tint !== undefined && (typeof tint !== 'string' || !isTint(tint))) {
      continue
    }
    out[key] = tint === undefined ? { wallpaper } : { wallpaper, tint }
    count += 1
  }
  return out
}

/**
 * Read one conversation's theme override.
 * Returns undefined when the room follows the global default.
 */
export function getConvTheme(prefs: PulsePrefs, conversationId: string): ConvTheme | undefined {
  return prefs[CONV_THEMES_KEY]?.[conversationId]
}

/**
 * The wallpaper a room actually renders: per-conversation override,
 * else the global Appearance default. (chat-room consumption point.)
 */
export function effectiveConvWallpaper(
  prefs: PulsePrefs,
  conversationId: string,
): PulsePrefs['wallpaper'] {
  return getConvTheme(prefs, conversationId)?.wallpaper ?? prefs.wallpaper
}

/**
 * Blend a per-conversation tint into the room's wallpaper glow pair.
 * No tint → glows pass through untouched. With a tint, the top glow is
 * replaced by the tint color (visible even on the `none` wallpaper) while
 * the bottom glow keeps the wallpaper's character.
 */
export function applyConvTint(
  glows: readonly [string, string],
  tint: ConvTint | undefined,
): [string, string] {
  if (!tint) return [glows[0], glows[1]]
  return [CONV_TINT_META[tint].glow, glows[1]]
}

/**
 * Pure update — merge `patch` into the conversation's stored theme and
 * return the full next prefs object. Does NOT persist; feed the returned
 * prefs' `chat.convThemes` key through the normal save() path.
 *
 *  - `patch.wallpaper` (validated token) sets the override wallpaper. A
 *    fresh entry is seeded from the CURRENT global default so a tint-only
 *    customization still has a complete, deterministic theme.
 *  - `patch.tint: ConvTint` sets the tint; `patch.tint: null` clears it.
 *
 * Invalid tokens are ignored (no partial writes from bad input).
 */
export function setConvTheme(
  prefs: PulsePrefs,
  conversationId: string,
  patch: { wallpaper?: PulsePrefs['wallpaper']; tint?: ConvTint | null },
): PulsePrefs {
  const map: ConvThemeMap = { ...(prefs[CONV_THEMES_KEY] ?? {}) }
  // seed a new entry from the global default so tint-only rooms are complete
  const current: ConvTheme = { ...(map[conversationId] ?? { wallpaper: prefs.wallpaper }) }
  if (patch.wallpaper !== undefined && isWallpaperToken(patch.wallpaper)) {
    current.wallpaper = patch.wallpaper
  }
  if ('tint' in patch) {
    if (patch.tint !== null && patch.tint !== undefined && isTint(patch.tint)) {
      current.tint = patch.tint
    } else {
      delete current.tint
    }
  }
  map[conversationId] = current
  return { ...prefs, [CONV_THEMES_KEY]: map }
}

/**
 * Remove the conversation's override entirely — the room falls back to
 * the global Appearance default. Returns the SAME prefs reference when
 * there is nothing to clear (cheap no-op for callers).
 */
export function clearConvTheme(prefs: PulsePrefs, conversationId: string): PulsePrefs {
  const map = prefs[CONV_THEMES_KEY]
  if (!map || !(conversationId in map)) return prefs
  const next: ConvThemeMap = { ...map }
  delete next[conversationId]
  return { ...prefs, [CONV_THEMES_KEY]: next }
}

/**
 * One-line human summary for UI rows (room info "Chat theme").
 * `custom` is true when the room has any override; `text` names the
 * effective wallpaper (+ tint) or the global default it follows.
 */
export function convThemeSummary(
  prefs: PulsePrefs,
  conversationId: string,
): { custom: boolean; text: string } {
  const override = getConvTheme(prefs, conversationId)
  const globalLabel = WALLPAPER_META.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'
  if (!override) return { custom: false, text: `Global default · ${globalLabel}` }
  const wallpaperLabel =
    WALLPAPER_META.find((w) => w.id === override.wallpaper)?.label ?? override.wallpaper
  const parts = [wallpaperLabel]
  if (override.tint) parts.push(`${CONV_TINT_META[override.tint].label} tint`)
  return { custom: true, text: `Custom · ${parts.join(' · ')}` }
}

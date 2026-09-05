/**
 * Pulse user preferences — shared shape + defaults.
 * Stored as a JSON string on User.preferences (SQLite has no Json type).
 * Imported by BOTH the API layer (src/app/api/settings) and the client
 * store (src/lib/prefs.ts) so validation never drifts.
 */

import type { ConvThemeMap } from '@/lib/conv-theme'
import { sanitizeConvThemeMap } from '@/lib/conv-theme'

export type PulsePrefs = {
  /** Chat bubble corner style consumed by chat-room bubbles. */
  bubbleRadius: 'md' | 'lg' | 'pill'
  /** Message list density consumed by chat-room row spacing. */
  density: 'cozy' | 'compact'
  /** Chat wallpaper token consumed by chat-room background. */
  wallpaper: 'none' | 'aurora' | 'dusk' | 'forest' | 'mono'
  /** Show message text in notification-style toasts. */
  notifPreviews: boolean
  /** Play a soft pop on incoming messages. */
  notifSound: boolean
  /** Vibrate on incoming messages (where supported). */
  notifVibrate: boolean
  /** Broadcast last-seen to other users (privacy). */
  lastSeenVisible: boolean
  /** Send read receipts (privacy). */
  readReceipts: boolean
  /** Reduce non-essential motion (effects/parallax) app-wide. */
  reducedMotion: boolean
  /** WebGL ambient field mode — see src/components/fx/webgl-glow.tsx (WEBGL_MODES). */
  'fx.webglMode'?: string
  /** Per-conversation chat themes (R29-a) — sanitized by src/lib/conv-theme.ts. */
  'chat.convThemes'?: ConvThemeMap
}

export const DEFAULT_PREFERENCES: PulsePrefs = {
  bubbleRadius: 'lg',
  density: 'cozy',
  wallpaper: 'none',
  notifPreviews: true,
  notifSound: true,
  notifVibrate: false,
  lastSeenVisible: true,
  readReceipts: true,
  reducedMotion: false,
}

const RADIUS = ['md', 'lg', 'pill']
const DENSITY = ['cozy', 'compact']
const WALLPAPER = ['none', 'aurora', 'dusk', 'forest', 'mono']
const WEBGL_MODES_OK = ['off', 'aurora', 'caustics', 'mesh', 'stars']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Shallow-merge a stored/sent partial prefs object over the defaults,
 * silently discarding anything malformed (never throws, never returns junk).
 */
export function mergePrefs(raw: unknown): PulsePrefs {
  const out: PulsePrefs = { ...DEFAULT_PREFERENCES }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out
  const p = raw as Record<string, unknown>
  if (typeof p.bubbleRadius === 'string' && RADIUS.includes(p.bubbleRadius)) {
    out.bubbleRadius = p.bubbleRadius as PulsePrefs['bubbleRadius']
  }
  if (typeof p.density === 'string' && DENSITY.includes(p.density)) {
    out.density = p.density as PulsePrefs['density']
  }
  if (typeof p.wallpaper === 'string' && WALLPAPER.includes(p.wallpaper)) {
    out.wallpaper = p.wallpaper as PulsePrefs['wallpaper']
  }
  if (typeof p['fx.webglMode'] === 'string' && WEBGL_MODES_OK.includes(p['fx.webglMode'])) {
    out['fx.webglMode'] = p['fx.webglMode']
  }
  if (isRecord(p['chat.convThemes'])) {
    out['chat.convThemes'] = sanitizeConvThemeMap(p['chat.convThemes'])
  }
  for (const k of [
    'notifPreviews',
    'notifSound',
    'notifVibrate',
    'lastSeenVisible',
    'readReceipts',
    'reducedMotion',
  ] as const) {
    if (typeof p[k] === 'boolean') out[k] = p[k] as boolean
  }
  return out
}

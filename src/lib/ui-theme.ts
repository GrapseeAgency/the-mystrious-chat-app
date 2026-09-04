// ─────────────────────────────────────────────────────────────
// Pulse UI Theme System (R25) — five locked design languages.
// The old single-theme chrome is gone; every surface now renders
// through ONE of these five languages, applied via `data-ui` on
// the shell root + CSS custom properties (see globals.css).
//
//   glass    · Immersive Glassmorphic Chat UI
//   kinetic  · Kinetic UI
//   minimal  · Motion-Driven Minimalist UI
//   dynamic  · Dynamic Minimalism
//   aero     · Kinetic Minimalist Interface
//
// Persisted in localStorage `pulse.uiTheme.v2` (zustand persist),
// broadcast via `pulse.uiTheme.changed` for out-of-React listeners.
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

export type UiThemeId = 'glass' | 'kinetic' | 'minimal' | 'dynamic' | 'aero'

export interface UiThemeMeta {
  id: UiThemeId
  label: string
  tagline: string
  /** one-line description for pickers */
  detail: string
  /** preview accent (oklch/hex) for swatches */
  swatch: [string, string]
  /** motion personality — surfaces may pick springs by theme */
  motion: 'elastic' | 'crisp' | 'quiet' | 'playful' | 'glide'
}

export const UI_THEMES: Array<UiThemeMeta> = [
  {
    id: 'glass',
    label: 'Immersive Glass',
    tagline: 'Glassmorphic Chat UI',
    detail: 'Layered frosted glass, aurora backdrop, specular edges, elastic motion.',
    swatch: ['#10b981', '#0ea5e9'],
    motion: 'elastic',
  },
  {
    id: 'kinetic',
    label: 'Kinetic',
    tagline: 'Kinetic UI',
    detail: 'High-contrast ink, sharp corners, bold type, whip-crack springs.',
    swatch: ['#18181b', '#f43f5e'],
    motion: 'crisp',
  },
  {
    id: 'minimal',
    label: 'Quiet Minimal',
    tagline: 'Motion-Driven Minimalist UI',
    detail: 'Hairlines and whitespace. Motion whispers, structure speaks.',
    swatch: ['#52525b', '#a1a1aa'],
    motion: 'quiet',
  },
  {
    id: 'dynamic',
    label: 'Dynamic',
    tagline: 'Dynamic Minimalism',
    detail: 'Soft neutrals with vivid gradient accents and playful bounce.',
    swatch: ['#f59e0b', '#ec4899'],
    motion: 'playful',
  },
  {
    id: 'aero',
    label: 'Aero Kinetic',
    tagline: 'Kinetic Minimalist Interface',
    detail: 'Frost-stroke panels on cool graphite, gliding inertia.',
    swatch: ['#38bdf8', '#818cf8'],
    motion: 'glide',
  },
]

export const DEFAULT_UI_THEME: UiThemeId = 'glass'

export const UI_THEME_EVENT = 'pulse.uiTheme.changed'

export function isUiThemeId(v: unknown): v is UiThemeId {
  return v === 'glass' || v === 'kinetic' || v === 'minimal' || v === 'dynamic' || v === 'aero'
}

interface UiThemeState {
  theme: UiThemeId
  setTheme: (t: UiThemeId) => void
}

export const useUiThemeStore = create<UiThemeState>()(
  persist(
    (set) => ({
      theme: DEFAULT_UI_THEME,
      setTheme: (theme) => {
        set({ theme })
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent<UiThemeId>(UI_THEME_EVENT, { detail: theme }))
        }
      },
    }),
    { name: 'pulse.uiTheme.v2', storage: createJSONStorage(() => localStorage) },
  ),
)

/** React hook — current theme + setter.
 *  Primitive + stable-function selectors (a fresh array in the selector
 *  would trip useSyncExternalStore's getSnapshot cache → infinite loop). */
export function useUiTheme(): [UiThemeId, (t: UiThemeId) => void] {
  const theme = useUiThemeStore((s) => s.theme)
  const setTheme = useUiThemeStore((s) => s.setTheme)
  return [theme, setTheme]
}

export function getUiThemeMeta(id: UiThemeId): UiThemeMeta {
  return UI_THEMES.find((t) => t.id === id) ?? UI_THEMES[0]
}

/**
 * Motion character per theme — surfaces that want theme-aware physics
 * call this instead of hardcoding spring presets.
 */
export function themeMotion(id: UiThemeId): UiThemeMeta['motion'] {
  return getUiThemeMeta(id).motion
}

/**
 * Apply the theme to a container element (data attribute drives the
 * CSS variable sets in globals.css). Called by the app shell on every
 * render of the current theme.
 */
export function uiThemeAttr(id: UiThemeId): string {
  return `ui-${id}`
}

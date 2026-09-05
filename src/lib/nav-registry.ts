// ─────────────────────────────────────────────────────────────
// Pulse Navigation Registry (R25) — twelve swappable navigation
// architectures. The old 4-style system is deleted; these twelve
// are the only nav languages the app ships:
//
//   capsule         · Floating Capsule Navigation Bar  (DEFAULT)
//   floating-top    · Floating Top Nav
//   floating-dock   · Floating Dock
//   pill            · Pill Navigation
//   bottom-bar      · Bottom Navigation Bar
//   tab-bar         · Tab Bar
//   floating-tab-bar· Floating Tab Bar
//   command-bar     · Command Bar
//   rail            · Navigation Rail
//   island          · Island Navigation
//   radial          · Radial Navigation
//   gesture         · Gesture Navigation
//   contextual-dock · Contextual Dock
//
// Persisted `pulse.navStyle.v2`; broadcast `pulse.navStyle.changed`.
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

export type NavStyleId =
  | 'capsule'
  | 'floating-top'
  | 'floating-dock'
  | 'pill'
  | 'bottom-bar'
  | 'tab-bar'
  | 'floating-tab-bar'
  | 'command-bar'
  | 'rail'
  | 'island'
  | 'radial'
  | 'gesture'
  | 'contextual-dock'

export interface NavStyleMeta {
  id: NavStyleId
  label: string
  hint: string
  /** where the nav lives visually */
  zone: 'bottom' | 'top' | 'side' | 'overlay'
}

export const NAV_STYLES: Array<NavStyleMeta> = [
  { id: 'capsule', label: 'Floating Capsule', hint: 'Detached glass capsule dock — the default', zone: 'bottom' },
  { id: 'floating-top', label: 'Floating Top Nav', hint: 'Capsule bar floating beneath the top edge', zone: 'top' },
  { id: 'floating-dock', label: 'Floating Dock', hint: 'Desktop-style dock with magnifying icons', zone: 'bottom' },
  { id: 'pill', label: 'Pill Navigation', hint: 'Single segmented pill with sliding fill', zone: 'bottom' },
  { id: 'bottom-bar', label: 'Bottom Bar', hint: 'Classic edge-to-edge bottom bar', zone: 'bottom' },
  { id: 'tab-bar', label: 'Tab Bar', hint: 'iOS-style tab bar with tinted squircles', zone: 'bottom' },
  { id: 'floating-tab-bar', label: 'Floating Tab Bar', hint: 'Detached card, elevated active tab', zone: 'bottom' },
  { id: 'command-bar', label: 'Command Bar', hint: 'Compact text command strip with search', zone: 'top' },
  { id: 'rail', label: 'Navigation Rail', hint: 'Persistent vertical side rail', zone: 'side' },
  { id: 'island', label: 'Island Navigation', hint: 'Dynamic-island pill that expands on tap', zone: 'bottom' },
  { id: 'radial', label: 'Radial Navigation', hint: 'FAB fanning destinations in an arc', zone: 'overlay' },
  { id: 'gesture', label: 'Gesture Navigation', hint: 'Edge swipes + gesture pill quick switcher', zone: 'bottom' },
  { id: 'contextual-dock', label: 'Contextual Dock', hint: 'Dock that adapts to the active tab', zone: 'bottom' },
]

export const DEFAULT_NAV_STYLE: NavStyleId = 'capsule'

export const NAV_STYLE_EVENT = 'pulse.navStyle.changed'

export function isNavStyleId(v: unknown): v is NavStyleId {
  return NAV_STYLES.some((s) => s.id === v)
}

interface NavStyleState {
  style: NavStyleId
  setStyle: (s: NavStyleId) => void
}

export const useNavStyleStore = create<NavStyleState>()(
  persist(
    (set) => ({
      style: DEFAULT_NAV_STYLE,
      setStyle: (style) => {
        set({ style })
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent<NavStyleId>(NAV_STYLE_EVENT, { detail: style }))
        }
      },
    }),
    { name: 'pulse.navStyle.v2', storage: createJSONStorage(() => localStorage) },
  ),
)

export function getNavStyleMeta(id: NavStyleId): NavStyleMeta {
  return NAV_STYLES.find((s) => s.id === id) ?? NAV_STYLES[0]
}

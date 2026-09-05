// ─────────────────────────────────────────────────────────────
// Pulse — hash-based sub-page router (R26).
// The sandbox exposes a single Next.js route (/), but the product
// needs REAL sub-page navigation: Settings → Appearance, Profile →
// Edit Profile, user profile pages, etc. This module provides a
// tiny hash router (#/settings/appearance) with:
//   • useHashRoute()  — reactive current path (+ helpers)
//   • navigate(path)  — push a hash entry (browser back works)
//   • replacePath(p)  — rewrite the current entry
//   • back()          — history.back() when we pushed, else root
// Paths are normalized: no leading '#' and always a leading '/'.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useSyncExternalStore } from 'react'

export interface HashRoute {
  /** normalized path, e.g. "/settings/appearance" ("/" when no hash) */
  path: string
  /** path segments, e.g. ["settings", "appearance"] */
  segments: string[]
}

function normalize(raw: string): string {
  const cleaned = raw.replace(/^#/, '')
  if (cleaned === '' || cleaned === '/') return '/'
  const withSlash = cleaned.startsWith('/') ? cleaned : `/${cleaned}`
  return withSlash.length > 1 ? withSlash.replace(/\/+$/, '') : withSlash
}

function readHash(): string {
  if (typeof window === 'undefined') return '/'
  return normalize(window.location.hash)
}

let lastSnapshot = '/'
/**
 * How many hash entries THIS app session pushed. Browser-back on a
 * deep-link boot (pushDepth 0) would exit the site — so backHash only
 * pops history when we actually pushed, else it replaces in place.
 */
let pushDepth = 0

function subscribe(onChange: () => void): () => void {
  // always notify — useSyncExternalStore re-reads getSnapshot and skips the
  // render itself when nothing changed. Gating here races the snapshot cache
  // (a render between replaceState and dispatch would swallow the update).
  const handler = () => onChange()
  window.addEventListener('hashchange', handler)
  return () => window.removeEventListener('hashchange', handler)
}

/** Exported raw subscription — lets shells gate UI on the live hash. */
export const subscribeHash = subscribe

// keep the depth honest when the user browser-backs through our pushes
if (typeof window !== 'undefined') {
  window.addEventListener('popstate', () => {
    pushDepth = Math.max(0, pushDepth - 1)
  })
}

function getSnapshot(): string {
  // read live but keep a stable reference for useSyncExternalStore
  const live = readHash()
  if (live !== lastSnapshot) lastSnapshot = live
  return lastSnapshot
}

const getServerSnapshot = (): string => '/'

/** Reactive hash path. Re-renders whenever the location hash changes. */
export function useHashRoute(): HashRoute {
  const path = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
  const segments = path === '/' ? [] : path.slice(1).split('/')
  return { path, segments }
}

/** Push a hash entry — creates a history step so browser/gesture back works. */
export function navigateHash(path: string): void {
  if (typeof window === 'undefined') return
  const target = `#${normalize(path)}`
  if (window.location.hash === target) return
  pushDepth += 1
  window.location.hash = target
}

/** Rewrite the current hash without adding a history entry. */
export function replaceHash(path: string): void {
  if (typeof window === 'undefined') return
  const target = `#${normalize(path)}`
  const before = window.location.hash
  const url = window.location.pathname + window.location.search + target
  window.history.replaceState(null, '', url)
  // replaceState does not fire hashchange — always notify when the URL moved
  if (before !== target) {
    lastSnapshot = normalize(target)
    window.dispatchEvent(new HashChangeEvent('hashchange'))
  }
}

/** Pop one in-app history entry we pushed; on a deep-link boot (nothing
 *  pushed yet) rewrite in place instead of leaving the site. */
export function backHash(fallback = '/'): void {
  if (typeof window === 'undefined') return
  if (pushDepth > 0 && window.history.length > 1) {
    pushDepth -= 1
    window.history.back()
  } else {
    pushDepth = 0
    replaceHash(fallback)
  }
}

/** Hook bundle for screens: route + memoized callbacks. */
export function useHashNav() {
  const route = useHashRoute()
  const navigate = useCallback((path: string) => navigateHash(path), [])
  const back = useCallback((fallback?: string) => backHash(fallback), [])
  return { ...route, navigate, back }
}

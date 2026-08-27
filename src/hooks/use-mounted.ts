// ─────────────────────────────────────────────────────────────
// Pulse Chat — SSR-safe "mounted" flag without setState effects.
// ─────────────────────────────────────────────────────────────
'use client'

import { useSyncExternalStore } from 'react'

const emptySubscribe = () => () => {}

export function useMounted(): boolean {
  return useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false,
  )
}

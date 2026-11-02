'use client'

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import {
  DEFAULT_PREFERENCES,
  mergePrefs,
  type PulsePrefs,
} from '@/lib/prefs-defaults'

/**
 * Client prefs store - optimistic local state synced to the REAL
 * /api/settings endpoint (User.preferences JSON blob in SQLite).
 * - hydrate(userId): pull server truth once per login
 * - save(patch): optimistic update + debounced PATCH carrying a durable
 *   clientId + per-field intent times (Section 13 items 1/2)
 * - flush(): the queued intent, retried automatically with backoff on
 *   failure (Section 13 item 3) - a user change is never dropped because a
 *   request failed; it stays queued until the server confirms it.
 * Persisted locally under 'pulse.prefs.v1' so the UI is instant on reload.
 */

type PrefsStore = {
  userId: string | null
  prefs: PulsePrefs
  settingsRevision: number
  hydratedFor: string | null
  hydrate: (userId: string) => Promise<void>
  refresh: (userId: string) => Promise<void>
  save: (patch: Partial<PulsePrefs>) => void
  flush: () => Promise<void>
}

let saveTimer: ReturnType<typeof setTimeout> | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let pendingPatch: Partial<PulsePrefs> = {}
let retryDelayMs = 0
const RETRY_BASE_MS = 4_000
const RETRY_MAX_MS = 30_000

function freshClientId(): string {
  // 32 hex chars - inside the server's 16-128 url-safe contract.
  return (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
    ? crypto.randomUUID().replace(/-/g, '')
    : `${Date.now().toString(16)}${Math.random().toString(16).slice(2, 12)}${Math.random().toString(16).slice(2, 12)}`.slice(0, 32)
}

export const usePrefs = create<PrefsStore>()(
  persist(
    (set, get) => ({
      userId: null,
      prefs: { ...DEFAULT_PREFERENCES },
      settingsRevision: 0,
      hydratedFor: null,

      hydrate: async (userId: string) => {
        if (get().hydratedFor === userId) return
        set({ userId, hydratedFor: userId })
        await get().refresh(userId)
        // Section 13 item 3: intent queued before the last reload still
        // deserves its automatic retry - the queue survives restarts.
        if (Object.keys(pendingPatch).length > 0) {
          setTimeout(() => { void get().flush() }, 0)
        }
      },

      refresh: async (userId: string) => {
        // Forced re-pull (realtime settings:changed handler); the hydratedFor
        // guard only protects hydrate().
        try {
          const res = await fetch(`/api/settings?userId=${encodeURIComponent(userId)}`)
          if (!res.ok) return
          const data = (await res.json()) as { preferences?: PulsePrefs; revision?: number }
          if (data.preferences) {
            set({
              prefs: mergePrefs(data.preferences),
              userId,
              settingsRevision: typeof data.revision === 'number' ? data.revision : get().settingsRevision,
            })
          }
        } catch {
          // offline → keep local persisted prefs; the queued intent retries
          // on its backoff timer and server truth wins on next refresh
        }
      },

      save: (patch: Partial<PulsePrefs>) => {
        const { userId } = get()
        const next = mergePrefs({ ...get().prefs, ...patch })
        set({ prefs: next })
        if (!userId) return
        pendingPatch = { ...pendingPatch, ...patch }
        retryDelayMs = 0 // a fresh user intent restarts the backoff curve
        if (saveTimer) clearTimeout(saveTimer)
        saveTimer = setTimeout(() => { void get().flush() }, 450)
      },

      flush: async () => {
        if (saveTimer) { clearTimeout(saveTimer); saveTimer = null }
        const { userId } = get()
        if (!userId || Object.keys(pendingPatch).length === 0) return
        const batch = pendingPatch
        const intentAt = new Date().toISOString()
        const fieldTimes: Record<string, string> = {}
        for (const field of Object.keys(batch)) fieldTimes[field] = intentAt
        const body = JSON.stringify({ userId, preferences: batch, clientId: freshClientId(), intentAt, fieldTimes })
        const send = () => fetch('/api/settings', {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body,
        })
        try {
          let res = await send()
          if (res.status === 409) {
            // Contention (or a probe verdict worth re-asking): ONE honest
            // retry of the SAME durable instructions, then give up to backoff.
            res = await send()
          }
          if (!res.ok) throw new Error(`settings write failed: ${res.status}`)
          const data = (await res.json()) as { preferences?: PulsePrefs; revision?: number; superseded?: string[] }
          // The response is authoritative: superseded fields arrive with the
          // value that actually won, so adopting it resolves the conflict.
          if (data.preferences) {
            set({ prefs: mergePrefs(data.preferences) })
          }
          if (typeof data.revision === 'number') set({ settingsRevision: data.revision })
          pendingPatch = {}
          retryDelayMs = 0
        } catch {
          // Section 13 item 3: durable automatic retry - keep the intent,
          // back off, never fabricate a receipt.
          if (saveTimer) clearTimeout(saveTimer)
          retryDelayMs = retryDelayMs === 0 ? RETRY_BASE_MS : Math.min(retryDelayMs * 2, RETRY_MAX_MS)
          if (retryTimer) clearTimeout(retryTimer)
          retryTimer = setTimeout(() => { void get().flush() }, retryDelayMs)
        }
      },
    }),
    {
      name: 'pulse.prefs.v1',
      partialize: (s) => ({ userId: s.userId, prefs: s.prefs, settingsRevision: s.settingsRevision }),
    },
  ),
)

/** Convenience selector hooks used across screens. */
export function usePrefsValues(): PulsePrefs {
  return usePrefs((s) => s.prefs)
}

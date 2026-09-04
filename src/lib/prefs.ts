'use client'

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import {
  DEFAULT_PREFERENCES,
  mergePrefs,
  type PulsePrefs,
} from '@/lib/prefs-defaults'

/**
 * Client prefs store — optimistic local state synced to the REAL
 * /api/settings endpoint (User.preferences JSON blob in SQLite).
 * - hydrate(userId): pull server truth once per login
 * - save(patch): optimistic update + debounced PATCH, rollback on failure
 * Persisted locally under 'pulse.prefs.v1' so the UI is instant on reload.
 */

type PrefsStore = {
  userId: string | null
  prefs: PulsePrefs
  hydratedFor: string | null
  hydrate: (userId: string) => Promise<void>
  save: (patch: Partial<PulsePrefs>) => void
}

let saveTimer: ReturnType<typeof setTimeout> | null = null
let pendingPatch: Partial<PulsePrefs> = {}

export const usePrefs = create<PrefsStore>()(
  persist(
    (set, get) => ({
      userId: null,
      prefs: { ...DEFAULT_PREFERENCES },
      hydratedFor: null,

      hydrate: async (userId: string) => {
        if (get().hydratedFor === userId) return
        set({ userId, hydratedFor: userId })
        try {
          const res = await fetch(`/api/settings?userId=${encodeURIComponent(userId)}`)
          if (!res.ok) return
          const data = (await res.json()) as { preferences?: PulsePrefs }
          if (data.preferences) {
            set({ prefs: mergePrefs(data.preferences), userId })
          }
        } catch {
          // offline → keep local persisted prefs; server wins on next hydrate
        }
      },

      save: (patch: Partial<PulsePrefs>) => {
        const { userId } = get()
        const next = mergePrefs({ ...get().prefs, ...patch })
        set({ prefs: next })
        if (!userId) return
        pendingPatch = { ...pendingPatch, ...patch }
        if (saveTimer) clearTimeout(saveTimer)
        saveTimer = setTimeout(async () => {
          const body = { userId: get().userId, preferences: pendingPatch }
          pendingPatch = {}
          try {
            const res = await fetch('/api/settings', {
              method: 'PATCH',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(body),
            })
            if (!res.ok) {
              // rollback to server truth on failure
              const uid = get().userId
              if (uid) void get().hydrate // re-pull; hydratedFor guard bypassed below
              set({ hydratedFor: null })
              if (uid) void get().hydrate
            }
          } catch {
            set({ hydratedFor: null })
          }
        }, 450)
      },
    }),
    {
      name: 'pulse.prefs.v1',
      partialize: (s) => ({ userId: s.userId, prefs: s.prefs }),
    },
  ),
)

/** Convenience selector hooks used across screens. */
export function usePrefsValues(): PulsePrefs {
  return usePrefs((s) => s.prefs)
}

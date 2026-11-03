// Pulse Chat - client session store (zustand + sessionStorage)
// Per-tab identity so two browser tabs can chat as two accounts.
import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { AppUser } from '@/lib/types'
import { setAuthTokenResolver } from '@/lib/pulse-utils'
import { readStoredToken } from '@/lib/session-token-client'

interface PulseSessionStore {
  user: AppUser | null
  setUser: (user: AppUser) => void
  clear: () => void
  /** true once the persisted slice has been read from sessionStorage */
  _hasHydrated: boolean
  setHasHydrated: (value: boolean) => void
}

export const usePulseSession = create<PulseSessionStore>()(
  persist(
    (set) => ({
      user: null,
      setUser: (user) => set({ user }),
      clear: () => set({ user: null }),
      _hasHydrated: false,
      setHasHydrated: (value) => set({ _hasHydrated: value }),
    }),
    {
      name: 'pulse.session.v1',
      version: 1,
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) => ({ user: state.user }) as PulseSessionStore,
      onRehydrateStorage: () => (state) => {
        state?.setHasHydrated(true)
      },
    },
  ),
)

/** Imperative accessor (used inside non-react callbacks like sockets). */
export function getPulseUser(): AppUser | null {
  return usePulseSession.getState().user
}

// Section 16 credential adoption: register the live resolver once at store
// load so every shared apiJson call attaches the active user's Bearer. The
// resolver reads lazily (inside apiJson), so importing this module anywhere
// is safe on the server too.
setAuthTokenResolver(() => {
  const user = usePulseSession.getState().user
  return user ? readStoredToken(user.id) : null
})

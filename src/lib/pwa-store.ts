// ─────────────────────────────────────────────────────────────
// Pulse Chat — PWA client store (session-only, NOT persisted).
// Holds the deferred install prompt captured from
// beforeinstallprompt so Profile can offer "Install Pulse".
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'

interface PulsePwaState {
  /** captured BeforeInstallPromptEvent, null once consumed/unavailable */
  installEvent: Event | null
  setInstallEvent: (event: Event | null) => void
}

export const usePulsePwa = create<PulsePwaState>((set) => ({
  installEvent: null,
  setInstallEvent: (installEvent) => set({ installEvent }),
}))

/**
 * Fire the captured install prompt. Returns the outcome
 * ('accepted' | 'dismissed' | 'unavailable') and clears the event.
 */
export async function promptPwaInstall(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
  const { installEvent, setInstallEvent } = usePulsePwa.getState()
  if (!installEvent) return 'unavailable'
  setInstallEvent(null)
  try {
    // BeforeInstallPromptEvent.prompt() — duck-typed, no public TS type needed
    await (installEvent as Event & { prompt: () => Promise<{ outcome: string }> }).prompt()
    return 'accepted'
  } catch {
    return 'dismissed'
  }
}

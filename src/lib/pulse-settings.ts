// ─────────────────────────────────────────────────────────────
// Pulse Chat — user preferences (sound / haptics).
// localStorage-persisted zustand store, usable outside React
// via pulseSettingsStore.getState().
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

interface PulseSettingsState {
  soundOn: boolean
  hapticsOn: boolean
  setSoundOn: (on: boolean) => void
  setHapticsOn: (on: boolean) => void
}

export const pulseSettingsStore = create<PulseSettingsState>()(
  persist(
    (set) => ({
      soundOn: true,
      hapticsOn: true,
      setSoundOn: (on) => set({ soundOn: on }),
      setHapticsOn: (on) => set({ hapticsOn: on }),
    }),
    {
      name: 'pulse.settings.v1',
      storage: createJSONStorage(() => localStorage),
    },
  ),
)

/** Haptic ping honoring the user's haptics preference. */
export function haptic(pattern: number = 20): void {
  if (!pulseSettingsStore.getState().hapticsOn) return
  const nav = typeof navigator !== 'undefined' ? navigator : undefined
  const vib = nav as (Navigator & { vibrate?: (p: number | number[]) => boolean }) | undefined
  try {
    vib?.vibrate?.(pattern)
  } catch {
    // unsupported — silent
  }
}

// ── Incoming-message ping (WebAudio, zero assets) ────────────

let audioCtx: AudioContext | null = null

function ctx(): AudioContext | null {
  if (typeof window === 'undefined') return null
  try {
    const Ctor =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!Ctor) return null
    audioCtx = audioCtx ?? new Ctor()
    if (audioCtx.state === 'suspended') void audioCtx.resume()
    return audioCtx
  } catch {
    return null
  }
}

/** Warm the audio context during a user gesture so later pings may play. */
export function primeSound(): void {
  ctx()
}

/** Soft two-note "ding" for incoming messages. Best-effort, never throws. */
export function playIncomingPing(): void {
  if (!pulseSettingsStore.getState().soundOn) return
  try {
    const ac = ctx()
    if (!ac || ac.state !== 'running') return
    const now = ac.currentTime
    const gain = ac.createGain()
    gain.gain.setValueAtTime(0.0001, now)
    gain.gain.exponentialRampToValueAtTime(0.12, now + 0.02)
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.42)
    gain.connect(ac.destination)
    const notes: Array<[number, number]> = [
      [880, 0],
      [1174.66, 0.12], // D6 — the "dong"
    ]
    for (const [freq, offset] of notes) {
      const osc = ac.createOscillator()
      osc.type = 'sine'
      osc.frequency.value = freq
      osc.connect(gain)
      osc.start(now + offset)
      osc.stop(now + offset + 0.3)
    }
  } catch {
    // audio blocked/unavailable — silent
  }
}

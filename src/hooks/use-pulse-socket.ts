// ─────────────────────────────────────────────────────────────
// Pulse Chat — realtime context contract + consumer hook.
// Implementation lives in components/chat/pulse-realtime-provider.tsx
// ─────────────────────────────────────────────────────────────
'use client'

import { createContext, useContext } from 'react'

export interface TypingEntry {
  userId: string
  userName: string
}

export interface TypingSignalOptions {
  viewerId: string
  userName: string
  /** member ids EXCLUDING the viewer */
  recipients: string[]
}

export interface PulseRealtimeValue {
  /** user ids with ≥1 live socket connection */
  onlineIds: ReadonlySet<string>
  isConnected: boolean
  /** other people currently typing in a conversation (auto-expires ~4s) */
  typersIn: (conversationId: string, excludeUserId?: string) => TypingEntry[]
  /** mark which conversation is open & visible (drives auto-read) */
  setActiveConversation: (conversationId: string | null) => void
  /** keystroke pump — throttles emits to ≤1 per 1.5s while typing */
  signalTyping: (conversationId: string, options: TypingSignalOptions) => void
  /** immediate typing=false (blur / send / unmount) */
  cancelTyping: (conversationId: string, options: Omit<TypingSignalOptions, 'userName'>) => void
}

export const PulseRealtimeContext = createContext<PulseRealtimeValue | null>(null)

export function usePulseRealtime(): PulseRealtimeValue {
  const ctx = useContext(PulseRealtimeContext)
  if (!ctx) {
    throw new Error('usePulseRealtime must be used inside <PulseRealtimeProvider>')
  }
  return ctx
}

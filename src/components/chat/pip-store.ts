// ─────────────────────────────────────────────────────────────
// Pulse — PiP chat-inside-chat store (zustand).
// A tiny global window state so any surface (header toolbar,
// floating buttons, future hub widgets) can drive the floating
// mini conversation without prop drilling.
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'

type PipChatState = {
  /** window visible */
  isOpen: boolean
  /** which conversation the window shows */
  conversationId: string | null
  /** collapsed to a 56px pill */
  minimized: boolean
  /** open (or retarget) the floating mini chat */
  open: (conversationId: string) => void
  close: () => void
  minimize: () => void
  restore: () => void
}

export const usePipChat = create<PipChatState>()((set) => ({
  isOpen: false,
  conversationId: null,
  minimized: false,
  open: (conversationId: string) => set({ isOpen: true, conversationId, minimized: false }),
  close: () => set({ isOpen: false, conversationId: null, minimized: false }),
  minimize: () => set({ minimized: true }),
  restore: () => set({ minimized: false }),
}))

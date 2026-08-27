// ─────────────────────────────────────────────────────────────
// Pulse Chat — per-conversation composer drafts.
// localStorage-persisted so a half-typed message survives
// leaving/re-entering a chat room (per browser profile).
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

interface PulseDraftsState {
  drafts: Record<string, string>
  setDraft: (conversationId: string, text: string) => void
  clearDraft: (conversationId: string) => void
}

const MAX_DRAFT = 2000

export const pulseDraftsStore = create<PulseDraftsState>()(
  persist(
    (set) => ({
      drafts: {},
      setDraft: (conversationId, text) =>
        set((state) => {
          const trimmed = text.slice(0, MAX_DRAFT)
          const drafts = { ...state.drafts }
          if (trimmed.length === 0) delete drafts[conversationId]
          else drafts[conversationId] = trimmed
          return { drafts }
        }),
      clearDraft: (conversationId) =>
        set((state) => {
          if (!(conversationId in state.drafts)) return state
          const drafts = { ...state.drafts }
          delete drafts[conversationId]
          return { drafts }
        }),
    }),
    {
      name: 'pulse.drafts.v1',
      storage: createJSONStorage(() => localStorage),
    },
  ),
)

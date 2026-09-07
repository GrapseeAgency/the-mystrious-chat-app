// ─────────────────────────────────────────────────────────────
// Pulse Chat — per-conversation composer drafts.
// localStorage-persisted so a half-typed message survives
// leaving/re-entering a chat room (per browser profile).
// R45 — every local mutation ALSO mirrors to the server
// (PATCH /api/conversations/[id]/draft, debounced ~600ms), making drafts
// cross-device: the chats list "Draft: …" preview renders from the
// summary on any device and the composer restores a server draft when the
// local store is empty. This store is the single funnel — every existing
// save/clear call site (send, edit-cancel, slash commands, clear-all…)
// syncs with zero per-site wiring.
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { getPulseUser } from '@/lib/pulse-store'

interface PulseDraftsState {
  drafts: Record<string, string>
  setDraft: (conversationId: string, text: string) => void
  clearDraft: (conversationId: string) => void
}

const MAX_DRAFT = 2000

/** Per-conversation debounce timers so typing bursts collapse into one PATCH. */
const syncTimers = new Map<string, ReturnType<typeof setTimeout>>()

/**
 * Mirror a local draft mutation to the server (best-effort, silent on
 * failure — drafts keep working offline/local-first either way).
 */
function scheduleServerSync(conversationId: string, text: string) {
  const user = getPulseUser()
  if (!user) return // logged out — nothing to sync against
  const prev = syncTimers.get(conversationId)
  if (prev) clearTimeout(prev)
  syncTimers.set(
    conversationId,
    setTimeout(() => {
      syncTimers.delete(conversationId)
      void fetch(`/api/conversations/${encodeURIComponent(conversationId)}/draft`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: user.id, draft: text }),
        keepalive: true,
      }).catch(() => {}) // sync failure never breaks local drafting
    }, 600),
  )
}

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
          scheduleServerSync(conversationId, trimmed) // R45 cross-device mirror
          return { drafts }
        }),
      clearDraft: (conversationId) =>
        set((state) => {
          scheduleServerSync(conversationId, '') // R45 mirror the clear too
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

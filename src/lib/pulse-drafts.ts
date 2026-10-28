// Pulse Chat - per-conversation composer drafts.
// localStorage-persisted so a half-typed message survives
// leaving/re-entering a chat room (per browser profile).
// R45 - every local mutation ALSO mirrors to the server
// (PATCH /api/conversations/[id]/draft, debounced ~600ms), making drafts
// cross-device: the chats list "Draft: …" preview renders from the
// summary on any device and the composer restores a server draft when the
// local store is empty. This store is the single funnel - every existing
// save/clear call site (send, edit-cancel, slash commands, clear-all…)
// syncs with zero per-site wiring.
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { getPulseUser } from '@/lib/pulse-store'
import { DraftMirror } from '@/lib/draft-mirror'

interface PulseDraftsState {
  drafts: Record<string, string>
  setDraft: (conversationId: string, text: string) => void
  clearDraft: (conversationId: string) => void
}

const MAX_DRAFT = 2000

/** Per-conversation debounce timers so typing bursts collapse into one PATCH. */
const syncTimers = new Map<string, ReturnType<typeof setTimeout>>()

const mirrors = new Map<string, DraftMirror>()
function mirrorFor(conversationId: string) {
  const user = getPulseUser()
  if (!user) return null
  const key = user.id + '|' + conversationId
  let mirror = mirrors.get(key)
  if (!mirror) {
    mirror = new DraftMirror(user.id, conversationId, () => getPulseUser()?.id === user.id,
      (...args) => fetch(...args), () => crypto.randomUUID(), message => {
        window.dispatchEvent(new CustomEvent('pulse:draft-sync-error', { detail: { conversationId, message } }))
      })
    mirrors.set(key, mirror)
  }
  return { key, mirror }
}

/** Capture the text actually shown by the room, not a fresh overwrite baseline. */
export function primeDraftMirror(conversationId: string, observed: string) {
  const selected = mirrorFor(conversationId)
  if (selected) void selected.mirror.prime(observed).catch(error => {
    window.dispatchEvent(new CustomEvent('pulse:draft-sync-error', { detail: { conversationId, message: error.message } }))
  })
}

function scheduleServerSync(conversationId: string, text: string, previous: string) {
  const selected = mirrorFor(conversationId)
  if (!selected) return
  // Prime immediately on the first edit, before subsequent debounced keystrokes
  // change the local predecessor. Transport failures never erase local drafts.
  void selected.mirror.prime(previous).catch(() => {})
  const prev = syncTimers.get(selected.key)
  if (prev) clearTimeout(prev)
  syncTimers.set(selected.key, setTimeout(() => {
    syncTimers.delete(selected.key)
    void selected.mirror.submit(text, previous)
  }, 600))
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
          scheduleServerSync(conversationId, trimmed, state.drafts[conversationId] ?? '') // R45 cross-device mirror
          return { drafts }
        }),
      clearDraft: (conversationId) =>
        set((state) => {
          scheduleServerSync(conversationId, '', state.drafts[conversationId] ?? '') // R45 mirror the clear too
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

// ─────────────────────────────────────────────────────────────
// Pulse Chat — offline outbox for outgoing text messages.
// localStorage-persisted queue: while the device is offline,
// composed messages land here with an optimistic "queued"
// bubble; on reconnect a flusher drains them in order.
// ─────────────────────────────────────────────────────────────
'use client'

import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { QueryClient } from '@tanstack/react-query'
import type { ChatMessage, MessageAuthor, ReplySnippet } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'

/** One held-for-later outgoing message. */
export interface QueuedMessage {
  clientId: string // matches `temp-<clientId>` optimistic bubble id
  conversationId: string
  content: string
  replyToId?: string
  sender: MessageAuthor // frozen at enqueue time so flushing works room-less
  replySnapshot: ReplySnippet | null // quoted-parent preview for the queued bubble
  queuedAt: string // ISO — display timestamp of the queued bubble
  attempts: number // failed POST count (diagnostics only)
}

const MAX_QUEUE = 50

interface PulseOutboxState {
  queue: QueuedMessage[] // oldest first — drain order matters
  enqueue: (entry: Omit<QueuedMessage, 'attempts'>) => void
  remove: (clientId: string) => void
  bumpAttempt: (clientId: string) => void
}

export const pulseOutboxStore = create<PulseOutboxState>()(
  persist(
    (set) => ({
      queue: [],
      enqueue: (entry) =>
        set((state) => {
          if (state.queue.some((q) => q.clientId === entry.clientId)) return state
          const next = [...state.queue, { ...entry, attempts: 0 }]
          return { queue: next.length > MAX_QUEUE ? next.slice(next.length - MAX_QUEUE) : next }
        }),
      remove: (clientId) =>
        set((state) => {
          if (!state.queue.some((q) => q.clientId === clientId)) return state
          return { queue: state.queue.filter((q) => q.clientId !== clientId) }
        }),
      bumpAttempt: (clientId) =>
        set((state) => ({
          queue: state.queue.map((q) =>
            q.clientId === clientId ? { ...q, attempts: q.attempts + 1 } : q,
          ),
        })),
    }),
    {
      name: 'pulse.outbox.v1',
      storage: createJSONStorage(() => localStorage),
    },
  ),
)

/** Number of held messages across all conversations. */
export function outboxCount(): number {
  return pulseOutboxStore.getState().queue.length
}

interface SendResponse {
  message: ChatMessage
}

/**
 * Drain every queued message in FIFO order while online.
 * Returns how many were delivered. Stops at the first failure
 * (keep temporal order — later sends must not overtake earlier ones).
 *
 * Cache behavior per delivery:
 * - ['messages', convId] present → swap `temp-<clientId>` for the real row
 *   (plus generic temp-content dedupe, mirroring sendMessage.onSuccess)
 * - ['conversations'] invalidated once at the end so previews/unread refresh
 */
export async function flushPulseOutbox(queryClient: QueryClient): Promise<number> {
  if (typeof window === 'undefined' || !navigator.onLine) return 0

  const store = pulseOutboxStore.getState()
  if (store.queue.length === 0) return 0

  let sent = 0
  for (const entry of [...store.queue]) {
    if (!navigator.onLine) break
    try {
      const res = await apiJson<SendResponse>(
        `/api/conversations/${encodeURIComponent(entry.conversationId)}/messages`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            senderId: entry.sender.id,
            content: entry.content,
            ...(entry.replyToId ? { replyToId: entry.replyToId } : {}),
          }),
        },
      )
      pulseOutboxStore.getState().remove(entry.clientId)
      queryClient.setQueryData<ChatMessage[]>(['messages', entry.conversationId], (old) => {
        if (!old) return old // room never cached → fresh fetch covers it
        const real = res.message
        const hadReal = old.some((m) => m.id === real.id)
        const cleaned = old.filter(
          (m) =>
            m.id !== `temp-${entry.clientId}` &&
            !(
              m.id.startsWith('temp-') &&
              m.content === real.content &&
              m.senderId === real.senderId
            ),
        )
        return hadReal ? cleaned : [...cleaned, real]
      })
      sent += 1
    } catch {
      pulseOutboxStore.getState().bumpAttempt(entry.clientId)
      break
    }
  }

  if (sent > 0) {
    queryClient.invalidateQueries({ queryKey: ['conversations'] })
  }
  return sent
}

// ─────────────────────────────────────────────────────────────
// Pulse — PiP pane store (zustand) — R28-a pane-management rework.
//
// A floating pane = one conversation rendered as a draggable glass
// window. Model:
//   · at most ONE expanded (focused) window at a time
//   · every other live pane is collapsed into the compact pill
//     stack on the right edge ("minimized")
//   · PIP_MAX_PANES live panes; opening a new one demotes the
//     focused window to the stack and evicts the OLDEST stacked
//     pane beyond the cap
//   · per-pane normalized position (0..1 of the draggable range)
//     survives reloads (localStorage `pulse.pip.v2`) and is
//     re-clamped into the phone frame on resize/orientation change
//     by the PipChat container
//
// LEGACY CONTRACT (kept byte-compatible for chat-room.tsx):
//   usePipChat selectors  s.open / s.close / s.isOpen / s.conversationId
//     · open(id)    → expand (or create) the pane for `id`, demote the rest
//     · close()     → close the focused window; stacked panes keep living
//     · isOpen      → true while ≥ 1 pane exists
//     · conversationId → the focused (expanded) pane's conversation,
//                        null when only the stack is showing
//   minimize()/restore() keep their old meaning against the focused pane.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo } from 'react'
import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { useQuery } from '@tanstack/react-query'
import type { ChatMessage } from '@/lib/types'

/** Max simultaneously live panes (1 expanded + rest stacked). */
export const PIP_MAX_PANES = 3

// ── frame geometry reserves (px, measured against the phone frame) ──
/** side margin the pane keeps from the frame edges */
export const PIP_MARGIN_X = 10
/** below the room header (~56px + safe-area + breathing room) */
export const PIP_TOP_RESERVE = 64
/** above the composer (~64px) / the ~76px nav capsule + safe-area */
export const PIP_BOTTOM_RESERVE = 92
/** pill size + gap used by the stack band */
export const PIP_STACK_PILL = 48
export const PIP_STACK_GAP = 8

// ── pane size clamps (mobile-first: fits a 390px frame with margins) ──
export const PIP_PANE_MIN_W = 220
export const PIP_PANE_MAX_W = 276
export const PIP_PANE_MIN_H = 300
export const PIP_PANE_MAX_H = 400

/**
 * Window event dispatched when a pane's header is TAPPED (a tap, never a
 * drag): "open this conversation in the main shell". New R28-a contract —
 * the shell may listen to navigate out of the current room. Harmless when
 * nothing listens (the pane is already above its own room).
 */
export const PIP_OPEN_CONVERSATION_EVENT = 'pulse:open-conversation'

export function dispatchPipOpenConversation(conversationId: string): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(
    new CustomEvent<{ conversationId: string }>(PIP_OPEN_CONVERSATION_EVENT, {
      detail: { conversationId },
    }),
  )
}

/** Display metadata captured from the expanded window's conversation detail
 *  so stack pills never need their own detail fetch. */
export interface PipPaneMeta {
  displayName: string
  isGroup: boolean
  partnerId: string | null
  partnerName: string | null
  partnerColor: string | null
  partnerAvatar: string | null
}

export interface PipPane {
  /** pane key == conversation id (one pane per conversation) */
  conversationId: string
  /** true → collapsed into the right-edge pill stack */
  minimized: boolean
  /** normalized top-left position, 0..1 across the draggable range */
  nx: number
  ny: number
  /** last moment this pane was the focused window (drives unread badges) */
  lastSeenAt: number
  /** creation/last-focus timestamp — oldest pane evicts beyond the cap */
  openedAt: number
  meta: PipPaneMeta | null
}

/** Live pixel geometry of the draggable area, derived by the PipChat
 *  container from the measured phone frame. `box` feeds framer's
 *  dragConstraints (allowed element edges); min/max are the allowed
 *  transform ranges the normalized positions map onto. */
export interface PipGeometry {
  frameW: number
  frameH: number
  paneW: number
  paneH: number
  box: { left: number; right: number; top: number; bottom: number }
  minX: number
  maxX: number
  minY: number
  maxY: number
}

type PipChatState = {
  /** ≥ 1 pane exists (window and/or stack) */
  isOpen: boolean
  /** focused (expanded) pane's conversation — null when stack-only */
  conversationId: string | null
  /** legacy flag: no window expanded while panes still live */
  minimized: boolean
  /** all live panes, oldest first */
  panes: PipPane[]
  /** open (or focus) the pane for a conversation */
  open: (conversationId: string) => void
  /** close the focused window (stacked panes stay) */
  close: () => void
  /** collapse the focused window into the stack */
  minimize: () => void
  /** expand the most recently demoted stacked pane */
  restore: () => void
  // ── R28-a pane ops ────────────────────────────────────────────
  focusPane: (conversationId: string) => void
  closePane: (conversationId: string) => void
  setPanePosition: (conversationId: string, nx: number, ny: number) => void
  setPaneMeta: (conversationId: string, meta: PipPaneMeta) => void
  markSeen: (conversationId: string, at: number) => void
}

const clamp01 = (v: number): number => (Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0)

function sanitizeMeta(raw: unknown): PipPaneMeta | null {
  if (typeof raw !== 'object' || raw === null) return null
  const m = raw as Partial<PipPaneMeta>
  if (typeof m.displayName !== 'string' || m.displayName.length === 0) return null
  return {
    displayName: m.displayName,
    isGroup: m.isGroup === true,
    partnerId: typeof m.partnerId === 'string' ? m.partnerId : null,
    partnerName: typeof m.partnerName === 'string' ? m.partnerName : null,
    partnerColor: typeof m.partnerColor === 'string' ? m.partnerColor : null,
    partnerAvatar: typeof m.partnerAvatar === 'string' ? m.partnerAvatar : null,
  }
}

function sanitizePanes(raw: unknown): PipPane[] {
  if (!Array.isArray(raw)) return []
  const out: PipPane[] = []
  const seen = new Set<string>()
  for (const item of raw) {
    if (typeof item !== 'object' || item === null) continue
    const p = item as Partial<PipPane>
    if (typeof p.conversationId !== 'string' || p.conversationId.length === 0) continue
    if (seen.has(p.conversationId)) continue
    seen.add(p.conversationId)
    out.push({
      conversationId: p.conversationId,
      minimized: p.minimized === true,
      nx: clamp01(p.nx ?? 1),
      ny: clamp01(p.ny ?? 0.08),
      lastSeenAt: typeof p.lastSeenAt === 'number' && Number.isFinite(p.lastSeenAt) ? p.lastSeenAt : 0,
      openedAt:
        typeof p.openedAt === 'number' && Number.isFinite(p.openedAt) ? p.openedAt : Date.now(),
      meta: sanitizeMeta(p.meta),
    })
  }
  // keep the newest PIP_MAX_PANES, array ordered oldest → newest
  return out.sort((a, b) => a.openedAt - b.openedAt).slice(-PIP_MAX_PANES)
}

export const usePipChat = create<PipChatState>()(
  persist(
    (set) => ({
      isOpen: false,
      conversationId: null,
      minimized: false,
      panes: [],

      open: (conversationId: string) =>
        set((state) => {
          const now = Date.now()
          const demoteAll = state.panes.map((p) =>
            p.conversationId === conversationId
              ? { ...p, minimized: false, openedAt: now }
              : { ...p, minimized: true },
          )
          let panes = demoteAll
          if (!state.panes.some((p) => p.conversationId === conversationId)) {
            // new pane: staggered default down the right edge
            const count = state.panes.length
            panes = [
              ...demoteAll,
              {
                conversationId,
                minimized: false,
                nx: 1,
                ny: clamp01(0.06 + 0.07 * count),
                lastSeenAt: now,
                openedAt: now,
                meta: null,
              },
            ]
          }
          // cap live panes — evict the OLDEST pane that is not the focused one
          while (panes.length > PIP_MAX_PANES) {
            const evictable = panes.filter((p) => p.conversationId !== conversationId)
            if (evictable.length === 0) break
            const oldest = evictable.reduce((a, b) => (a.openedAt <= b.openedAt ? a : b))
            panes = panes.filter((p) => p.conversationId !== oldest.conversationId)
          }
          return { panes, isOpen: true, conversationId, minimized: false }
        }),

      close: () =>
        set((state) => {
          const id = state.conversationId
          if (id === null) return state
          const panes = state.panes.filter((p) => p.conversationId !== id)
          return {
            panes,
            isOpen: panes.length > 0,
            conversationId: null,
            minimized: panes.length > 0,
          }
        }),

      minimize: () =>
        set((state) => {
          const id = state.conversationId
          if (id === null) return state
          return {
            panes: state.panes.map((p) =>
              p.conversationId === id ? { ...p, minimized: true } : p,
            ),
            conversationId: null,
            minimized: state.panes.length > 0,
            isOpen: state.panes.length > 0,
          }
        }),

      restore: () =>
        set((state) => {
          if (state.conversationId !== null) return state
          const stacked = state.panes.filter((p) => p.minimized)
          if (stacked.length === 0) return state
          const next = stacked.reduce((a, b) => (a.openedAt >= b.openedAt ? a : b))
          const now = Date.now()
          return {
            panes: state.panes.map((p) =>
              p.conversationId === next.conversationId
                ? { ...p, minimized: false, openedAt: now }
                : { ...p, minimized: true },
            ),
            conversationId: next.conversationId,
            minimized: false,
            isOpen: true,
          }
        }),

      focusPane: (conversationId: string) =>
        set((state) => {
          const pane = state.panes.find((p) => p.conversationId === conversationId)
          if (!pane || state.conversationId === conversationId) return state
          const now = Date.now()
          return {
            panes: state.panes.map((p) =>
              p.conversationId === conversationId
                ? { ...p, minimized: false, openedAt: now }
                : { ...p, minimized: true },
            ),
            conversationId,
            minimized: false,
            isOpen: true,
          }
        }),

      closePane: (conversationId: string) =>
        set((state) => {
          const panes = state.panes.filter((p) => p.conversationId !== conversationId)
          const wasFocused = state.conversationId === conversationId
          return {
            panes,
            isOpen: panes.length > 0,
            conversationId: wasFocused ? null : state.conversationId,
            minimized: panes.length > 0 && (wasFocused ? true : state.minimized),
          }
        }),

      setPanePosition: (conversationId: string, nx: number, ny: number) =>
        set((state) => {
          const pane = state.panes.find((p) => p.conversationId === conversationId)
          const sx = clamp01(nx)
          const sy = clamp01(ny)
          if (!pane || (Math.abs(pane.nx - sx) < 0.0005 && Math.abs(pane.ny - sy) < 0.0005)) {
            return state
          }
          return {
            panes: state.panes.map((p) =>
              p.conversationId === conversationId ? { ...p, nx: sx, ny: sy } : p,
            ),
          }
        }),

      setPaneMeta: (conversationId: string, meta: PipPaneMeta) =>
        set((state) => {
          const pane = state.panes.find((p) => p.conversationId === conversationId)
          if (!pane) return state
          const prev = pane.meta
          if (
            prev !== null &&
            prev.displayName === meta.displayName &&
            prev.isGroup === meta.isGroup &&
            prev.partnerId === meta.partnerId &&
            prev.partnerName === meta.partnerName &&
            prev.partnerColor === meta.partnerColor &&
            prev.partnerAvatar === meta.partnerAvatar
          ) {
            return state
          }
          return {
            panes: state.panes.map((p) =>
              p.conversationId === conversationId ? { ...p, meta } : p,
            ),
          }
        }),

      markSeen: (conversationId: string, at: number) =>
        set((state) => {
          const pane = state.panes.find((p) => p.conversationId === conversationId)
          if (!pane || at - pane.lastSeenAt < 1000) return state
          return {
            panes: state.panes.map((p) =>
              p.conversationId === conversationId ? { ...p, lastSeenAt: at } : p,
            ),
          }
        }),
    }),
    {
      name: 'pulse.pip.v2',
      version: 1,
      storage: createJSONStorage(() => localStorage),
      // rehydrated explicitly by the PipChat container AFTER mount —
      // keeps the server HTML and the first client render identical
      skipHydration: true,
      partialize: (s) => ({
        panes: s.panes,
        isOpen: s.isOpen,
        conversationId: s.conversationId,
        minimized: s.minimized,
      }),
      merge: (persisted, current) => {
        const raw = (typeof persisted === 'object' && persisted !== null ? persisted : {}) as
          Partial<PipChatState>
        const panes = sanitizePanes(raw.panes)
        const focused =
          typeof raw.conversationId === 'string'
            ? panes.find((p) => p.conversationId === raw.conversationId && !p.minimized)
            : undefined
        return {
          ...current,
          panes,
          isOpen: panes.length > 0 && raw.isOpen !== false,
          conversationId: focused ? focused.conversationId : null,
          minimized: panes.length > 0 && !focused,
        }
      },
    },
  ),
)

/**
 * Live unread count for a pane, derived from the SHARED messages cache the
 * realtime provider merges socket events into — no second socket, no extra
 * fetch. Unread = incoming (not mine, not pending, not deleted) messages
 * that arrived after the pane was last the focused window.
 */
export function usePaneUnread(conversationId: string, myId: string, lastSeenAt: number): number {
  const cached = useQuery<ChatMessage[]>({
    queryKey: ['messages', conversationId],
    enabled: false,
    queryFn: () => [],
  })
  const messages = cached.data ?? []
  return useMemo(
    () =>
      messages.filter(
        (m) =>
          m.senderId !== myId &&
          !m.id.startsWith('temp-') &&
          m.deletedAt === null &&
          Date.parse(m.createdAt) > lastSeenAt,
      ).length,
    [messages, myId, lastSeenAt],
  )
}

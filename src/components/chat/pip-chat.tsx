// ─────────────────────────────────────────────────────────────
// Pulse — PiP chat-inside-chat (Telegram "chat-in-chat" grade).
// R28-a pane-management rework:
//   · real pane physics — framer drag constrained to the phone
//     frame with elastic edges, then a magnetic spring settle to
//     the nearest horizontal edge; momentum fling from release
//     velocity; the pane always settles above the composer /
//     bottom nav capsule and below the room header
//   · tap-vs-drag: a tap on the header opens the conversation in
//     the main shell (pulse:open-conversation), a drag moves the
//     pane — threshold-guarded so they never fight
//   · normalized positions (0..1) persisted per pane in
//     pulse.pip.v2 and re-derived from the live frame on
//     resize/orientation change — a restored pane can never be
//     off-screen
//   · >1 pane: the compact pill stack (pip-stack.tsx) holds the
//     collapsed ones; max 3 live panes, oldest auto-evicts
//   · slim glass chrome: avatar, name, unread dot, minimize
//     (ChevronDown) and close (X) on a 48px drag-handle header
//   · data flow untouched: same shared TanStack caches the main
//     room uses (seeded lazily, merged by the realtime provider)
//     — no second socket, no extra polling beyond the old 12s
//     detail refresh
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  AnimatePresence,
  animate,
  motion,
  useDragControls,
  useMotionValue,
  useReducedMotion,
  type AnimationPlaybackControls,
  type PanInfo,
} from 'framer-motion'
import { ChevronDown, SendHorizontal, X } from 'lucide-react'
import { toast } from 'sonner'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { AppUser, ChatMessage, ConversationDetail } from '@/lib/types'
import { apiJson, conversationDisplayName, jsonBody, otherMemberOf } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { haptic } from '@/lib/pulse-settings'
import { spring } from '@/lib/motion'
import { parseLocationPayload } from '@/components/chat/location-share'
import {
  dispatchPipOpenConversation,
  PIP_BOTTOM_RESERVE,
  PIP_MARGIN_X,
  PIP_PANE_MAX_H,
  PIP_PANE_MAX_W,
  PIP_PANE_MIN_H,
  PIP_PANE_MIN_W,
  PIP_STACK_GAP,
  PIP_STACK_PILL,
  PIP_TOP_RESERVE,
  usePipChat,
  usePaneUnread,
  type PipGeometry,
  type PipPane,
  type PipPaneMeta,
} from '@/components/chat/pip-store'
import { PipStack } from '@/components/chat/pip-stack'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'

interface MessagesResponse {
  messages: ChatMessage[]
  hasMore?: boolean
  total?: number
}
interface SendResponse {
  message: ChatMessage
}
interface DetailResponse {
  conversation: ConversationDetail
}

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v))

/** Tiny bubble renderer — compact subset of the main room's MessageRow. */
function PipBubble({ message, myId }: { message: ChatMessage; myId: string }) {
  const mine = message.senderId === myId
  const deleted = message.deletedAt !== null
  const pending = message.id.startsWith('temp-')
  const sticker =
    !deleted && message.kind === 'sticker'
      ? (() => {
          try {
            const raw: unknown = message.payload ? JSON.parse(message.payload) : null
            if (typeof raw === 'object' && raw !== null && typeof (raw as { emoji?: unknown }).emoji === 'string') {
              return (raw as { emoji: string }).emoji
            }
          } catch {
            return null
          }
          return null
        })()
      : null
  const location = !deleted && message.kind === 'location' ? parseLocationPayload(message.payload) : null

  return (
    <div className={cn('flex w-full', mine ? 'justify-end' : 'justify-start')}>
      <div className="max-w-[86%]">
        {!mine ? (
          <p className="mb-0.5 truncate text-[9.5px] font-bold text-emerald-700 dark:text-emerald-400">
            {message.sender.name}
          </p>
        ) : null}
        <div
          className={cn(
            'inline-block max-w-full rounded-2xl px-2.5 py-1.5 text-left',
            deleted
              ? 'border border-dashed border-zinc-300 text-[11px] italic text-zinc-400 dark:border-zinc-600 dark:text-zinc-500'
              : sticker
                ? 'bg-transparent px-0 text-3xl leading-none'
                : mine
                  ? 'bg-emerald-500 text-white'
                  : 'border border-zinc-100 bg-white text-zinc-900 shadow-sm dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100',
            pending && 'opacity-70',
          )}
        >
          {deleted ? (
            'deleted'
          ) : sticker ? (
            <span aria-label={`sticker ${sticker}`}>{sticker}</span>
          ) : location ? (
            <span className="flex items-center gap-1 text-[11.5px] font-semibold">
              {location.label}
            </span>
          ) : message.imagePath ? (
            <img
              src={`/api/uploads/${encodeURIComponent(message.imagePath)}`}
              alt="Shared photo"
              loading="lazy"
              className="max-h-28 max-w-[180px] rounded-lg object-cover"
            />
          ) : (
            <span className="whitespace-pre-wrap break-words text-[11.5px] leading-snug">
              {message.content.replace(/\s+/g, ' ').trim() || '· · ·'}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

/** The single expanded (focused) pane — a draggable glass window. */
function PipWindow({
  me,
  pane,
  geo,
  frameKey,
  stackBand,
  onSettle,
  onMeta,
  onSeen,
  onMinimize,
  onClose,
}: {
  me: AppUser
  pane: PipPane
  geo: PipGeometry
  /** changes only when the frame/safe-area resizes → re-derive pixel position */
  frameKey: string
  stackBand: { top: number; bottom: number } | null
  onSettle: (conversationId: string, nx: number, ny: number) => void
  onMeta: (conversationId: string, meta: PipPaneMeta) => void
  onSeen: (conversationId: string, at: number) => void
  onMinimize: () => void
  onClose: () => void
}) {
  const reduced = useReducedMotion()
  const queryClient = useQueryClient()
  const realtime = usePulseRealtime()
  const dragControls = useDragControls()
  const conversationId = pane.conversationId

  const x = useMotionValue(0)
  const y = useMotionValue(0)
  const [dragging, setDragging] = useState(false)
  const [seedStatus, setSeedStatus] = useState<'idle' | 'loading' | 'error'>('idle')
  const [draft, setDraft] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  const snapAnimsRef = useRef<AnimationPlaybackControls[]>([])
  const settlingRef = useRef(false)
  const lastFrameKeyRef = useRef('')
  const pressRef = useRef<{ x: number; y: number; t: number } | null>(null)
  const draggedRef = useRef(false)

  // ── data: seed the shared cache if this conversation was never loaded,
  // then observe it (enabled:false → this observer never fetches; incoming
  // socket events arrive via the realtime provider's cache merge).
  useEffect(() => {
    // deferred one tick — opening the window never cascades a sync re-render
    const kick = setTimeout(() => {
      setSeedStatus('idle')
      const cached = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
      if (cached && cached.length > 0) return
      setSeedStatus('loading')
      const convId = conversationId
      void queryClient
        .fetchQuery({
          queryKey: ['messages', convId],
          queryFn: async (): Promise<ChatMessage[]> => {
            const res = await apiJson<MessagesResponse>(
              `/api/conversations/${encodeURIComponent(convId)}/messages?limit=30`,
            )
            return res.messages
          },
          staleTime: 15_000,
        })
        .then(() => setSeedStatus('idle'))
        .catch(() => setSeedStatus('error'))
    }, 0)
    return () => clearTimeout(kick)
  }, [conversationId, queryClient])

  const cachedMessages = useQuery<ChatMessage[]>({
    queryKey: ['messages', conversationId],
    enabled: false,
    queryFn: () => [],
  })

  const detailQuery = useQuery({
    queryKey: ['conversation', conversationId],
    enabled: true,
    staleTime: 15_000,
    refetchInterval: 12_000,
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<DetailResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversation
    },
  })

  const detail = detailQuery.data ?? null
  const displayName = detail ? conversationDisplayName(detail, me.id) : pane.meta?.displayName ?? 'Chat'
  const dmPartner = detail && !detail.isGroup ? otherMemberOf(detail, me.id) : null
  const recipients = detail ? detail.members.filter((m) => m.id !== me.id).map((m) => m.id) : []
  const messages = cachedMessages.data ?? []
  const unread = usePaneUnread(conversationId, me.id, pane.lastSeenAt)

  // keep the mini list pinned to the newest message
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages.length, seedStatus])

  // the expanded window is the reading surface — incoming history is seen
  useEffect(() => {
    if (messages.length === 0) return
    onSeen(conversationId, Date.now())
  }, [messages.length, conversationId, onSeen])

  // feed display metadata to the store so stack pills never re-fetch
  useEffect(() => {
    if (!detail) return
    const partner = detail.isGroup ? null : otherMemberOf(detail, me.id)
    onMeta(conversationId, {
      displayName,
      isGroup: detail.isGroup,
      partnerId: partner?.id ?? null,
      partnerName: partner?.name ?? null,
      partnerColor: partner?.color ?? null,
      partnerAvatar: partner?.avatar ?? null,
    })
  }, [detail, displayName, conversationId, me.id, onMeta])

  const send = useCallback(async () => {
    const content = draft.trim()
    if (content.length === 0) return
    setDraft('')
    try {
      const res = await apiJson<SendResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
        jsonBody({ senderId: me.id, content }),
      )
      const real = res.message
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
        if (!old || old.length === 0) return [real]
        if (old.some((m) => m.id === real.id)) return old
        return [...old, real]
      })
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    } catch {
      toast.error('Could not send from the mini chat')
      setDraft(content)
    }
  }, [draft, conversationId, me.id, queryClient])

  const signalPipTyping = useCallback(
    (value: string) => {
      setDraft(value)
      if (recipients.length === 0) return
      if (value.trim().length > 0) {
        realtime.signalTyping(conversationId, { viewerId: me.id, userName: me.name, recipients })
      } else {
        realtime.cancelTyping(conversationId, { viewerId: me.id, recipients })
      }
    },
    [conversationId, me.id, me.name, recipients, realtime],
  )

  // ── pane physics ─────────────────────────────────────────────
  const stopSnap = useCallback(() => {
    for (const ctrl of snapAnimsRef.current) ctrl.stop()
    snapAnimsRef.current = []
    settlingRef.current = false
  }, [])

  // apply the persisted normalized position whenever the FRAME changes
  // (mount, resize, orientation). Normalized × live range is always in
  // bounds, so a restored pane can never land off-screen.
  useLayoutEffect(() => {
    if (lastFrameKeyRef.current === frameKey) return
    lastFrameKeyRef.current = frameKey
    if (settlingRef.current) return // a settle animation is mid-flight toward the persisted target
    x.set(geo.minX + clamp01(pane.nx) * (geo.maxX - geo.minX))
    y.set(geo.minY + clamp01(pane.ny) * (geo.maxY - geo.minY))
  }, [frameKey, geo, pane.nx, pane.ny, x, y])

  useEffect(() => stopSnap, [stopSnap])

  /** magnetic settle: nearest horizontal edge + velocity fling, clamped
   *  above the composer / below the header, then persisted normalized. */
  const settle = useCallback(
    (info: PanInfo) => {
      const power = 0.14 // momentum projection factor (px per px/s)
      const projX = x.get() + info.velocity.x * power
      const projY = y.get() + info.velocity.y * power
      const rangeX = Math.max(1, geo.maxX - geo.minX)
      const rangeY = Math.max(1, geo.maxY - geo.minY)
      const tx =
        projX + geo.paneW / 2 < geo.frameW / 2 ? geo.minX : geo.maxX
      let ty = Math.min(Math.max(projY, geo.minY), geo.maxY)
      // keep the right-edge berth clear for the pill stack
      if (stackBand && tx === geo.maxX && ty < stackBand.bottom && ty + geo.paneH > stackBand.top) {
        const above = stackBand.top - geo.paneH - 6
        ty = above >= geo.minY ? above : Math.min(geo.maxY, stackBand.bottom + 6)
        ty = Math.min(Math.max(ty, geo.minY), geo.maxY)
      }
      const settleSpring = reduced
        ? { duration: 0 }
        : { type: 'spring' as const, stiffness: 340, damping: 30 }
      settlingRef.current = true
      let done = 0
      const finish = () => {
        done += 1
        if (done >= 2) settlingRef.current = false
      }
      snapAnimsRef.current = [
        animate(x, tx, { ...settleSpring, onComplete: finish }),
        animate(y, ty, { ...settleSpring, onComplete: finish }),
      ]
      onSettle(conversationId, (tx - geo.minX) / rangeX, (ty - geo.minY) / rangeY)
      haptic(6)
    },
    [conversationId, geo, onSettle, reduced, stackBand, x, y],
  )

  const onDragStart = useCallback(() => {
    draggedRef.current = true
    setDragging(true)
    stopSnap()
  }, [stopSnap])

  const onDragEnd = useCallback(
    (_event: unknown, info: PanInfo) => {
      setDragging(false)
      settle(info)
    },
    [settle],
  )

  // ── tap-vs-drag on the header (the drag handle) ──────────────
  const onHeaderPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if ((event.target as HTMLElement).closest('button')) return
      pressRef.current = { x: event.clientX, y: event.clientY, t: Date.now() }
      draggedRef.current = false
      stopSnap()
      dragControls.start(event)
    },
    [dragControls, stopSnap],
  )

  const onHeaderPointerUp = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const press = pressRef.current
      pressRef.current = null
      if (!press || draggedRef.current) return
      if ((event.target as HTMLElement).closest('button')) return
      const dist = Math.hypot(event.clientX - press.x, event.clientY - press.y)
      if (dist > 8 || Date.now() - press.t > 450) return
      // a clean tap → open this conversation in the main shell
      haptic(6)
      dispatchPipOpenConversation(conversationId)
    },
    [conversationId],
  )

  const headerCursor = dragging ? 'grabbing' : 'grab'

  return (
    <motion.div
      role="dialog"
      aria-label={`Mini chat — ${displayName}`}
      initial={reduced ? { opacity: 0 } : { opacity: 0, scale: 0.86 }}
      animate={reduced ? { opacity: 1 } : { opacity: 1, scale: 1 }}
      exit={reduced ? { opacity: 0 } : { opacity: 0, scale: 0.9 }}
      transition={spring.soft}
      drag
      dragListener={false}
      dragControls={dragControls}
      dragConstraints={geo.box}
      dragMomentum={false}
      dragElastic={reduced ? 0 : 0.14}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      whileDrag={reduced ? undefined : { scale: 1.03 }}
      style={{ x, y, width: geo.paneW, height: geo.paneH, willChange: 'transform', transformOrigin: '100% 100%' }}
      className="pointer-events-auto absolute top-0 left-0 z-[2] touch-none"
    >
      {/* soft shadow lift while dragging (bleeds outside the clipped body) */}
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -inset-1.5 rounded-[22px] bg-zinc-950/35 blur-xl"
        initial={false}
        animate={{ opacity: dragging ? 0.6 : 0 }}
        transition={{ duration: 0.18 }}
      />
      <div className="glass-deep glass-sheen flex h-full w-full flex-col overflow-hidden rounded-2xl">
        {/* header — the drag handle + tap-to-open target */}
        <div
          onPointerDown={onHeaderPointerDown}
          onPointerUp={onHeaderPointerUp}
          className="flex h-12 shrink-0 touch-none items-center gap-1.5 border-b border-zinc-900/[0.06] pr-1 pl-2 dark:border-white/[0.08]"
          style={{ cursor: headerCursor }}
        >
          {detail ? (
            detail.isGroup ? (
              <GroupAvatar title={displayName} id={conversationId} size={30} />
            ) : (
              <UserAvatar
                name={dmPartner?.name ?? displayName}
                color={dmPartner?.color}
                avatar={dmPartner?.avatar ?? pane.meta?.partnerAvatar ?? null}
                size={30}
                showPresence
                online={dmPartner ? realtime.onlineIds.has(dmPartner.id) : false}
              />
            )
          ) : (
            <Skeleton className="size-[30px] rounded-full" />
          )}
          <p className="min-w-0 flex-1 truncate text-[12.5px] font-bold text-zinc-800 dark:text-zinc-100">
            {displayName}
          </p>
          {unread > 0 ? (
            <span
              aria-label={`${unread} unread`}
              className="size-2 shrink-0 rounded-full bg-emerald-500"
            />
          ) : null}
          <button
            type="button"
            aria-label="Minimize mini chat"
            onPointerDown={(event) => event.stopPropagation()}
            onClick={() => {
              haptic(6)
              onMinimize()
            }}
            className="flex size-10 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-900/[0.06] hover:text-zinc-600 active:scale-90 dark:hover:bg-white/[0.08] dark:hover:text-zinc-200"
          >
            <ChevronDown className="size-4" aria-hidden />
          </button>
          <button
            type="button"
            aria-label="Close mini chat"
            onPointerDown={(event) => event.stopPropagation()}
            onClick={() => {
              haptic(6)
              onClose()
            }}
            className="flex size-10 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
          >
            <X className="size-4" aria-hidden />
          </button>
        </div>

        {/* messages */}
        <div
          ref={listRef}
          className="pulse-scroll flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto px-2.5 py-2"
        >
          {seedStatus === 'loading' ? (
            <div className="flex flex-1 flex-col justify-end gap-2 pb-1" role="status" aria-label="Loading messages">
              <Skeleton className="h-8 w-3/5 rounded-2xl" />
              <Skeleton className="ml-auto h-10 w-2/5 rounded-2xl" />
              <Skeleton className="h-8 w-1/2 rounded-2xl" />
            </div>
          ) : seedStatus === 'error' ? (
            <div className="flex flex-1 items-center justify-center px-4 text-center">
              <p className="text-[11.5px] font-medium text-zinc-400 dark:text-zinc-500">
                Couldn&apos;t load this chat — check your connection and reopen.
              </p>
            </div>
          ) : messages.length === 0 ? (
            <div className="flex flex-1 items-center justify-center px-4 text-center">
              <p className="text-[11.5px] font-medium text-zinc-400 dark:text-zinc-500">
                No messages here yet — say hi from the mini chat.
              </p>
            </div>
          ) : (
            messages.map((m) => <PipBubble key={m.id} message={m} myId={me.id} />)
          )}
        </div>

        {/* composer */}
        <div className="flex shrink-0 items-center gap-1.5 border-t border-zinc-900/[0.06] bg-white/40 px-2 py-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom))] dark:border-white/[0.08] dark:bg-zinc-900/40">
          <Input
            value={draft}
            onChange={(e) => signalPipTyping(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                void send()
              }
            }}
            placeholder="Message…"
            aria-label={`Message ${displayName} from mini chat`}
            maxLength={2000}
            className="h-9 min-w-0 flex-1 rounded-full border-zinc-200/80 bg-white/80 text-[12.5px] focus-visible:ring-emerald-500/50 dark:border-zinc-600/80 dark:bg-zinc-800/80"
          />
          <button
            type="button"
            aria-label="Send message"
            onClick={() => void send()}
            disabled={draft.trim().length === 0}
            className="flex size-9 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-90 disabled:opacity-40"
          >
            <SendHorizontal className="size-4" aria-hidden />
          </button>
        </div>
      </div>
    </motion.div>
  )
}

/**
 * Floating pane manager — mounted once inside the room overlay.
 * Renders the focused glass window plus the compact pill stack for
 * every collapsed pane. The wrapper stays mounted (pointer-events
 * none, zero footprint) so close/minimize exit animations can play.
 */
export function PipChat({ me }: { me: AppUser }) {
  const panes = usePipChat((s) => s.panes)
  const focusedId = usePipChat((s) => s.conversationId)
  const setPanePosition = usePipChat((s) => s.setPanePosition)
  const setPaneMeta = usePipChat((s) => s.setPaneMeta)
  const markSeen = usePipChat((s) => s.markSeen)
  const minimize = usePipChat((s) => s.minimize)
  const closePane = usePipChat((s) => s.closePane)
  const focusPane = usePipChat((s) => s.focusPane)

  const frameRef = useRef<HTMLDivElement>(null)
  const [frameSize, setFrameSize] = useState({ w: 0, h: 0 })
  const [safeArea, setSafeArea] = useState({ top: 0, bottom: 0 })

  // restore persisted panes AFTER mount — server HTML and the first
  // client render stay identical (no hydration mismatch)
  useEffect(() => {
    void usePipChat.persist.rehydrate()
  }, [])

  // measure the phone frame (the room overlay fills it) + follow resizes
  useEffect(() => {
    const el = frameRef.current
    if (!el) return
    const ro = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect
      if (!rect) return
      setFrameSize((prev) =>
        Math.abs(prev.w - rect.width) < 0.5 && Math.abs(prev.h - rect.height) < 0.5
          ? prev
          : { w: rect.width, h: rect.height },
      )
    })
    ro.observe(el)
    const onOrientation = () => {
      const rect = el.getBoundingClientRect()
      setFrameSize({ w: rect.width, h: rect.height })
    }
    window.addEventListener('orientationchange', onOrientation)
    return () => {
      ro.disconnect()
      window.removeEventListener('orientationchange', onOrientation)
    }
  }, [])

  // env(safe-area-inset-*) only resolves through computed style — probe once
  useEffect(() => {
    if (typeof window === 'undefined') return
    const raf = requestAnimationFrame(() => {
      const probe = document.createElement('div')
      probe.setAttribute('aria-hidden', 'true')
      probe.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;overflow:hidden'
      probe.innerHTML =
        '<div style="height:env(safe-area-inset-top,0px)"></div><div style="height:env(safe-area-inset-bottom,0px)"></div>'
      document.body.appendChild(probe)
      const first = probe.firstElementChild
      const second = probe.lastElementChild
      setSafeArea({
        top: first instanceof HTMLElement ? first.offsetHeight : 0,
        bottom: second instanceof HTMLElement ? second.offsetHeight : 0,
      })
      probe.remove()
    })
    return () => cancelAnimationFrame(raf)
  }, [])

  // pane geometry — derived from the live frame, mobile-first clamps
  const geo = useMemo<PipGeometry | null>(() => {
    if (frameSize.w < 120 || frameSize.h < 260) return null
    const paneW = Math.min(PIP_PANE_MAX_W, Math.max(PIP_PANE_MIN_W, Math.round(frameSize.w - 24)))
    const paneH = Math.round(Math.min(PIP_PANE_MAX_H, Math.max(PIP_PANE_MIN_H, frameSize.h * 0.52)))
    const bottomReserve = PIP_BOTTOM_RESERVE + safeArea.bottom
    const box = {
      left: PIP_MARGIN_X,
      right: frameSize.w - PIP_MARGIN_X,
      top: PIP_TOP_RESERVE + safeArea.top,
      bottom: frameSize.h - bottomReserve,
    }
    // degenerate frame (pane can't clear header + composer) → no window
    if (box.bottom - box.top < paneH + 24) return null
    return {
      frameW: frameSize.w,
      frameH: frameSize.h,
      paneW,
      paneH,
      box,
      minX: box.left,
      maxX: box.right - paneW,
      minY: box.top,
      maxY: box.bottom - paneH,
    }
  }, [frameSize, safeArea])

  const focusedPane = useMemo(
    () => panes.find((p) => p.conversationId === focusedId && !p.minimized) ?? null,
    [panes, focusedId],
  )
  const stackedPanes = useMemo(
    () => panes.filter((p) => p.minimized || p.conversationId !== focusedId),
    [panes, focusedId],
  )

  // the band the pill stack occupies (right edge, above the composer) —
  // right-edge snaps keep the window clear of it
  const stackBand = useMemo<{ top: number; bottom: number } | null>(() => {
    const n = stackedPanes.length
    if (n === 0 || !geo) return null
    const bottomEdge = geo.frameH - (PIP_BOTTOM_RESERVE + safeArea.bottom + 8)
    const height = n * PIP_STACK_PILL + (n - 1) * PIP_STACK_GAP
    return { top: bottomEdge - height, bottom: bottomEdge }
  }, [stackedPanes.length, geo, safeArea.bottom])

  const frameKey = `${Math.round(frameSize.w)}x${Math.round(frameSize.h)}x${safeArea.top}x${safeArea.bottom}`
  const stackBottom = PIP_BOTTOM_RESERVE + safeArea.bottom + 8

  return (
    <div ref={frameRef} className="pointer-events-none absolute inset-0 z-[60]">
      {geo ? (
        <>
          <AnimatePresence>
            {focusedPane ? (
              <PipWindow
                key={focusedPane.conversationId}
                me={me}
                pane={focusedPane}
                geo={geo}
                frameKey={frameKey}
                stackBand={stackBand}
                onSettle={setPanePosition}
                onMeta={setPaneMeta}
                onSeen={markSeen}
                onMinimize={minimize}
                onClose={() => closePane(focusedPane.conversationId)}
              />
            ) : null}
          </AnimatePresence>
          <PipStack
            panes={stackedPanes}
            bottom={stackBottom}
            meId={me.id}
            onExpand={focusPane}
            onClose={closePane}
          />
        </>
      ) : null}
    </div>
  )
}

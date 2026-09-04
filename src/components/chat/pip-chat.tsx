// ─────────────────────────────────────────────────────────────
// Pulse — PiP chat-inside-chat (Telegram "chat-in-chat" grade).
// A draggable 280×420 liquid-glass window floating bottom-right
// over the open conversation. It talks to the SAME real APIs as
// the main room (GET/POST /api/conversations/[id]/messages) and
// gets live updates through the shared TanStack cache that the
// existing realtime provider merges socket events into — no
// second socket is ever opened. Dragging is transform-only.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useDragControls, useMotionValue } from 'framer-motion'
import { LoaderCircle, Minus, SendHorizontal, X } from 'lucide-react'
import { toast } from 'sonner'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { AppUser, ChatMessage, ConversationDetail } from '@/lib/types'
import { apiJson, conversationDisplayName, jsonBody, otherMemberOf } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { parseLocationPayload } from '@/components/chat/location-share'
import { usePipChat } from '@/components/chat/pip-store'
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
              📍 {location.label}
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

export function PipChat({ me }: { me: AppUser }) {
  const { isOpen, conversationId, minimized, close, minimize, restore } = usePipChat()
  const queryClient = useQueryClient()
  const realtime = usePulseRealtime()
  const dragControls = useDragControls()
  const x = useMotionValue(0)
  const y = useMotionValue(0)
  const boundsRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const [draft, setDraft] = useState('')
  const [seedStatus, setSeedStatus] = useState<'idle' | 'loading' | 'error'>('idle')

  // ── data: seed the shared cache if this conversation was never loaded,
  // then observe it (enabled:false → this observer never fetches; incoming
  // socket events arrive via the realtime provider's cache merge).
  useEffect(() => {
    if (!isOpen || conversationId === null) return
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
  }, [isOpen, conversationId, queryClient])

  const cachedMessages = useQuery<ChatMessage[]>({
    queryKey: ['messages', conversationId ?? '-'],
    enabled: false,
    queryFn: () => [],
  })

  const detailQuery = useQuery({
    queryKey: ['conversation', conversationId ?? '-'],
    enabled: isOpen && conversationId !== null,
    staleTime: 15_000,
    refetchInterval: 12_000,
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<DetailResponse>(
        `/api/conversations/${encodeURIComponent(conversationId as string)}?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversation
    },
  })

  const detail = detailQuery.data ?? null
  const displayName = detail ? conversationDisplayName(detail, me.id) : 'Chat'
  const dmPartner = detail && !detail.isGroup ? otherMemberOf(detail, me.id) : null
  const recipients = detail ? detail.members.filter((m) => m.id !== me.id).map((m) => m.id) : []
  const messages = cachedMessages.data ?? []

  // keep the mini list pinned to the newest message
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages.length, seedStatus, minimized])

  const send = useCallback(async () => {
    const content = draft.trim()
    if (content.length === 0 || conversationId === null) return
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
      if (conversationId === null || recipients.length === 0) return
      if (value.trim().length > 0) {
        realtime.signalTyping(conversationId, { viewerId: me.id, userName: me.name, recipients })
      } else {
        realtime.cancelTyping(conversationId, { viewerId: me.id, recipients })
      }
    },
    [conversationId, me.id, me.name, recipients, realtime],
  )

  if (!isOpen || conversationId === null) return null

  return (
    <div ref={boundsRef} className="pointer-events-none absolute inset-0 z-[60]">
      <AnimatePresence>
        {minimized ? (
          <motion.button
            key="pip-pill"
            type="button"
            aria-label={`Restore mini chat — ${displayName}`}
            initial={{ opacity: 0, scale: 0.5, y: 14 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.6, y: 10 }}
            transition={{ type: 'spring', stiffness: 420, damping: 26 }}
            onClick={restore}
            className="pointer-events-auto absolute bottom-[92px] right-3 flex h-14 items-center gap-1.5 rounded-full border border-white/40 bg-white/80 py-1 pr-3 pl-1 shadow-2xl shadow-zinc-900/20 backdrop-blur-xl outline-none active:scale-95 dark:border-white/10 dark:bg-zinc-800/80"
          >
            {detail ? (
              detail.isGroup ? (
                <GroupAvatar title={displayName} id={conversationId} size={44} />
              ) : (
                <UserAvatar
                  name={dmPartner?.name ?? displayName}
                  color={dmPartner?.color}
                  size={44}
                  showPresence
                  online={dmPartner ? realtime.onlineIds.has(dmPartner.id) : false}
                />
              )
            ) : (
              <span className="size-11 animate-pulse rounded-full bg-zinc-200 dark:bg-zinc-700" />
            )}
            <span className="max-w-[96px] truncate text-xs font-bold text-zinc-800 dark:text-zinc-100">
              {displayName}
            </span>
          </motion.button>
        ) : (
          <motion.div
            key="pip-window"
            role="dialog"
            aria-label={`Mini chat — ${displayName}`}
            initial={{ opacity: 0, scale: 0.82, y: 26 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.86, y: 18 }}
            transition={{ type: 'spring', stiffness: 380, damping: 30 }}
            drag
            dragListener={false}
            dragControls={dragControls}
            dragConstraints={boundsRef}
            dragMomentum={false}
            dragElastic={0.08}
            style={{ x, y, willChange: 'transform' }}
            className="pointer-events-auto absolute bottom-[84px] right-3 flex h-[420px] w-[280px] flex-col overflow-hidden rounded-2xl border border-white/50 bg-white/85 shadow-2xl shadow-zinc-900/25 backdrop-blur-2xl dark:border-white/10 dark:bg-zinc-900/85"
          >
            {/* header — the drag handle */}
            <div
              onPointerDown={(event) => dragControls.start(event)}
              className="flex shrink-0 touch-none items-center gap-2 border-b border-zinc-200/70 bg-white/60 px-2 py-1.5 dark:border-zinc-700/70 dark:bg-zinc-800/60"
              style={{ cursor: 'grab' }}
            >
              {detail ? (
                detail.isGroup ? (
                  <GroupAvatar title={displayName} id={conversationId} size={28} />
                ) : (
                  <UserAvatar
                    name={dmPartner?.name ?? displayName}
                    color={dmPartner?.color}
                    size={28}
                    showPresence
                    online={dmPartner ? realtime.onlineIds.has(dmPartner.id) : false}
                  />
                )
              ) : (
                <Skeleton className="size-7 rounded-full" />
              )}
              <p className="min-w-0 flex-1 truncate text-xs font-bold text-zinc-800 dark:text-zinc-100">
                {displayName}
              </p>
              <button
                type="button"
                aria-label="Minimize mini chat"
                onPointerDown={(event) => event.stopPropagation()}
                onClick={minimize}
                className="flex size-7 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-200/70 hover:text-zinc-600 active:scale-90 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
              >
                <Minus className="size-3.5" aria-hidden />
              </button>
              <button
                type="button"
                aria-label="Close mini chat"
                onPointerDown={(event) => event.stopPropagation()}
                onClick={close}
                className="flex size-7 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
              >
                <X className="size-3.5" aria-hidden />
              </button>
            </div>

            {/* messages */}
            <div ref={listRef} className="pulse-scroll flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto px-2.5 py-2">
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
            <div className="flex shrink-0 items-center gap-1.5 border-t border-zinc-200/70 bg-white/60 px-2 py-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom))] dark:border-zinc-700/70 dark:bg-zinc-800/60">
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
          </motion.div>
        )}
      </AnimatePresence>
      {/* spinner slot while a seed fetch is in flight for a minimized pill */}
      {minimized && seedStatus === 'loading' ? (
        <LoaderCircle className="pointer-events-none absolute bottom-[104px] right-1 size-4 animate-spin text-emerald-500" aria-hidden />
      ) : null}
    </div>
  )
}

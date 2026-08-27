// ─────────────────────────────────────────────────────────────
// Pulse Chat — full-screen chat room overlay.
// Bubbles with clustering, day chips, typing indicators,
// read receipts, optimistic sending, delete-for-everyone.
// ─────────────────────────────────────────────────────────────
'use client'

import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import {
  ArrowDown,
  Check,
  CheckCheck,
  ChevronLeft,
  Clock,
  Copy,
  EllipsisVertical,
  Reply,
  SendHorizontal,
  Smile,
  Trash2,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import type {
  AppUser,
  ChatMessage,
  ConversationDetail,
} from '@/lib/types'
import {
  apiJson,
  conversationDisplayName,
  formatDayChip,
  formatListStamp,
  formatTime,
  isJumboEmoji,
  isSameDayIso,
  otherMemberOf,
  REACTION_CHOICES,
  EMOJI_PICKER_CHOICES,
  splitUrlSegments,
  uid,
} from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { cn } from '@/lib/utils'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { useMounted } from '@/hooks/use-mounted'

interface DetailResponse {
  conversation: ConversationDetail
}
interface MessagesResponse {
  messages: ChatMessage[]
}
interface SendResponse {
  message: ChatMessage
}
interface DeleteResponse {
  message: ChatMessage
}

const CLUSTER_WINDOW_MS = 5 * 60 * 1000
const NEAR_BOTTOM_PX = 160

type ClusterItem =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'unread'; key: string }
  | { kind: 'msg'; key: string; message: ChatMessage; head: boolean; tail: boolean }

export function ChatRoom({
  me,
  conversationId,
  unreadAnchorMs = null,
  onClose,
}: {
  me: AppUser
  conversationId: string
  /** pre-open read watermark frozen by the chats list at tap time (unread divider) */
  unreadAnchorMs?: number | null
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const realtime = usePulseRealtime()
  const { resolvedTheme } = useTheme()
  const themeMounted = useMounted()

  const [input, setInput] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  const [selected, setSelected] = useState<ChatMessage | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [showJump, setShowJump] = useState(false)
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)

  const viewportRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const nearBottomRef = useRef(true)
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** flipped (inside rAF) after the first history fetch so render stays ref-free */
  const [historyLoaded, setHistoryLoaded] = useState(false)

  // ── data ───────────────────────────────────────────────────

  const detail = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<DetailResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversation
    },
    staleTime: 15_000,
    // safety net: keeps read-ticks/membership fresh even if the socket path degrades
    refetchInterval: 6_000,
  })

  const messages = useQuery({
    queryKey: ['messages', conversationId],
    queryFn: async (): Promise<ChatMessage[]> => {
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=200`,
      )
      return res.messages
    },
    staleTime: 15_000,
    // safety net: messages still arrive within seconds even without websocket realtime
    refetchInterval: 3_500,
  })

  // ── realtime registration + read receipts ──────────────────

  useEffect(() => {
    realtime.setActiveConversation(conversationId)
    return () => realtime.setActiveConversation(null)
  }, [conversationId, realtime])

  /** POST read marker (visible rooms only) then patch local caches. */
  const markVisibleRead = useCallback(() => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    if ((messages.data ?? []).length === 0) return
    fetch(`/api/conversations/${encodeURIComponent(conversationId)}/read`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: me.id }),
    })
      .then(() => {
        const nowIso = new Date().toISOString()
        queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
          old
            ? {
                ...old,
                members: old.members.map((m) =>
                  m.id === me.id ? { ...m, lastReadAt: nowIso } : m,
                ),
              }
            : old,
        )
        // refresh list badges (my unread for this room is now zero)
        queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      })
      .catch(() => undefined)
  }, [conversationId, me.id, messages.data, queryClient])

  // opening the room + every new batch of messages ⇒ (debounced) read
  useEffect(() => {
    const timer = setTimeout(markVisibleRead, 600)
    return () => clearTimeout(timer)
  }, [markVisibleRead])

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'visible') markVisibleRead()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [markVisibleRead])

  // ── derived ────────────────────────────────────────────────

  const detailData = detail.data
  const other = useMemo(
    () => (detailData ? otherMemberOf(detailData, me.id) : null),
    [detailData, me.id],
  )
  const isGroup = detailData?.isGroup ?? false
  const displayName = detailData ? conversationDisplayName(detailData, me.id) : ''

  const recipients = useMemo(
    () => (detailData ? detailData.members.filter((m) => m.id !== me.id).map((m) => m.id) : []),
    [detailData, me.id],
  )

  const typers = realtime.typersIn(conversationId, me.id)
  const typerLabel = useMemo(() => {
    if (typers.length === 0) return ''
    if (!isGroup) return 'typing…'
    const names = typers.map((t) => t.userName)
    if (names.length === 1) return `${names[0]} is typing…`
    if (names.length === 2) return `${names[0]} and ${names[1]} are typing…`
    return `${names.length} people are typing…`
  }, [typers, isGroup])

  const onlineOthers = useMemo(
    () =>
      detailData
        ? detailData.members.filter((m) => m.id !== me.id && realtime.onlineIds.has(m.id)).length
        : 0,
    [detailData, me.id, realtime.onlineIds],
  )

  /** highest read watermark among OTHER members → double ticks */
  const othersMaxReadMs = useMemo(() => {
    if (!detailData) return Number.NEGATIVE_INFINITY
    let max = Number.NEGATIVE_INFINITY
    for (const m of detailData.members) {
      if (m.id === me.id) continue
      const t = Date.parse(m.lastReadAt)
      if (!Number.isNaN(t) && t > max) max = t
    }
    return max
  }, [detailData, me.id])

  const items = useMemo<ClusterItem[]>(() => {
    const list = messages.data ?? []
    const now = new Date()

    interface Entry {
      message: ChatMessage
      head: boolean
    }
    const built: Entry[] = []
    let prev: ChatMessage | null = null
    for (const message of list) {
      const clusterBreak =
        prev === null ||
        !isSameDayIso(prev.createdAt, message.createdAt) ||
        prev.senderId !== message.senderId ||
        Date.parse(message.createdAt) - Date.parse(prev.createdAt) > CLUSTER_WINDOW_MS
      built.push({ message, head: clusterBreak })
      prev = message
    }

    const out: ClusterItem[] = []
    let dayAnchor: ChatMessage | null = null
    let dividerPlaced = unreadAnchorMs === null
    for (let i = 0; i < built.length; i += 1) {
      const { message, head } = built[i]
      if (dayAnchor === null || !isSameDayIso(dayAnchor.createdAt, message.createdAt)) {
        out.push({ kind: 'day', key: `day-${message.id}`, label: formatDayChip(message.createdAt, now) })
        dayAnchor = message
      }
      if (
        !dividerPlaced &&
        message.senderId !== me.id &&
        message.deletedAt === null &&
        unreadAnchorMs !== null &&
        Date.parse(message.createdAt) > unreadAnchorMs
      ) {
        out.push({ kind: 'unread', key: 'unread-divider' })
        dividerPlaced = true
      }
      out.push({
        kind: 'msg',
        key: message.id,
        message,
        head,
        tail: i === built.length - 1 || built[i + 1].head,
      })
    }
    return out
  }, [messages.data, unreadAnchorMs, me.id])

  const lastMessageId =
    messages.data && messages.data.length > 0 ? messages.data[messages.data.length - 1].id : null

  // ── scrolling ──────────────────────────────────────────────

  const scrollToBottom = useCallback((smooth: boolean) => {
    const el = viewportRef.current
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  }, [])

  useEffect(() => {
    if (lastMessageId === null || !historyLoaded) return
    requestAnimationFrame(() => {
      if (nearBottomRef.current) {
        scrollToBottom(true)
      } else {
        setShowJump(true)
        buzz(10)
      }
    })
  }, [lastMessageId, historyLoaded, scrollToBottom])

  useEffect(() => {
    if (messages.isSuccess && !historyLoaded) {
      const frame = requestAnimationFrame(() => {
        scrollToBottom(false)
        setHistoryLoaded(true)
      })
      return () => cancelAnimationFrame(frame)
    }
    return undefined
  }, [messages.isSuccess, historyLoaded, scrollToBottom])

  const handleScroll = useCallback(() => {
    const el = viewportRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    nearBottomRef.current = distance < NEAR_BOTTOM_PX
    if (nearBottomRef.current) setShowJump(false)
  }, [])

  // ── sending ────────────────────────────────────────────────

  const sendMessage = useMutation({
    mutationFn: async ({
      clientId,
      content,
      replyToId,
    }: {
      clientId: string
      content: string
      replyToId?: string
    }) => {
      const res = await apiJson<SendResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ senderId: me.id, content, ...(replyToId ? { replyToId } : {}) }),
        },
      )
      return { res, clientId }
    },
    onMutate: async ({ clientId, content, replyToId }) => {
      const parentSnapshot = replyToId && replyTo && replyTo.id === replyToId
        ? {
            id: replyTo.id,
            content: replyTo.content,
            senderName: replyTo.sender.name,
            deleted: replyTo.deletedAt !== null,
          }
        : null
      const temp: ChatMessage = {
        id: `temp-${clientId}`,
        conversationId,
        senderId: me.id,
        content,
        deletedAt: null,
        createdAt: new Date().toISOString(),
        sender: { id: me.id, name: me.name, color: me.color },
        reactions: [],
        replyTo: parentSnapshot,
      }
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? [...old, temp] : [temp],
      )
    },
    onSuccess: ({ res, clientId }) => {
      const real = res.message
      setReplyTo(null)
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
        if (!old) return [real]
        const hadReal = old.some((m) => m.id === real.id)
        const cleaned = old.filter(
          (m) =>
            m.id !== `temp-${clientId}` &&
            !(m.id.startsWith('temp-') && m.content === real.content && m.senderId === real.senderId),
        )
        return hadReal ? cleaned : [...cleaned, real]
      })
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
    onError: (_error, { clientId }) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.filter((m) => m.id !== `temp-${clientId}`) : old,
      )
      toast.error('Message failed to send')
    },
  })

  /** Toggle an emoji reaction (optimistic; server response is truth). */
  const toggleReaction = useMutation({
    mutationFn: async ({ messageId, emoji }: { messageId: string; emoji: string }) => {
      const res = await apiJson<SendResponse>(`/api/messages/${encodeURIComponent(messageId)}/react`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, emoji }),
      })
      return res.message
    },
    onMutate: async ({ messageId, emoji }) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old
          ? old.map((m) => {
              if (m.id !== messageId) return m
              const groups = m.reactions.map((g) => ({ ...g, userIds: [...g.userIds] }))
              const mineIdx = groups.findIndex((g) => g.emoji === emoji)
              if (mineIdx >= 0) {
                const group = groups[mineIdx]
                const had = group.userIds.includes(me.id)
                if (had) {
                  group.userIds = group.userIds.filter((id) => id !== me.id)
                } else {
                  group.userIds.push(me.id)
                }
                group.count = group.userIds.length
                if (group.count === 0) groups.splice(mineIdx, 1)
              } else {
                groups.push({ emoji, userIds: [me.id], count: 1 })
              }
              return { ...m, reactions: groups }
            })
          : old,
      )
    },
    onSuccess: (real) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.map((m) => (m.id === real.id ? { ...m, reactions: real.reactions } : m)) : old,
      )
    },
    onError: () => {
      toast.error('Reaction failed — try again')
      queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
    },
  })

  const handleToggleReaction = useCallback(
    (messageId: string, emoji: string) => {
      haptic(12)
      toggleReaction.mutate({ messageId, emoji })
    },
    [toggleReaction],
  )

  const deleteMessage = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<DeleteResponse>(`/api/messages/${encodeURIComponent(messageId)}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id }),
      })
    },
    onMutate: async (messageId) => {
      const previous = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old
          ? old.map((m) => (m.id === messageId ? { ...m, deletedAt: new Date().toISOString() } : m))
          : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      const real = data.message
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.map((m) => (m.id === real.id ? real : m)) : old,
      )
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Message deleted')
    },
    onError: (_error, _messageId, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['messages', conversationId], context.previous)
      }
      toast.error('Could not delete the message')
    },
    onSettled: () => {
      setSelected(null)
      setConfirmingDelete(false)
    },
  })

  // ── composer behaviour ─────────────────────────────────────

  const autosize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  }, [])

  const stopTyping = useCallback(() => {
    if (recipients.length > 0) {
      realtime.cancelTyping(conversationId, { viewerId: me.id, recipients })
    }
  }, [realtime, conversationId, me.id, recipients])

  useEffect(() => () => stopTyping(), [stopTyping])

  const submit = useCallback(() => {
    const content = input.trim()
    if (content.length === 0 || sendMessage.isPending) return
    stopTyping()
    setInput('')
    requestAnimationFrame(autosize)
    sendMessage.mutate({
      clientId: uid(),
      content,
      ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
    })
  }, [input, sendMessage, stopTyping, autosize, replyTo])

  const handleInputChange = (value: string) => {
    setInput(value)
    autosize()
    if (value.trim().length > 0 && recipients.length > 0) {
      realtime.signalTyping(conversationId, {
        viewerId: me.id,
        userName: me.name,
        recipients,
      })
    } else {
      stopTyping()
    }
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      event.key === 'Enter' &&
      !event.shiftKey &&
      typeof window !== 'undefined' &&
      window.matchMedia('(min-width: 640px)').matches
    ) {
      event.preventDefault()
      submit()
    }
  }

  // ── long-press helpers ─────────────────────────────────────

  const clearLongPress = useCallback(() => {
    if (longPressRef.current !== null) {
      clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }, [])

  const startLongPress = useCallback(
    (message: ChatMessage) => {
      clearLongPress()
      longPressRef.current = setTimeout(() => {
        setSelected(message)
        longPressRef.current = null
      }, 450)
    },
    [clearLongPress],
  )

  const copySelected = async () => {
    if (!selected) return
    try {
      await navigator.clipboard.writeText(selected.content)
      toast.success('Copied to clipboard')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
    setSelected(null)
  }

  const canDeleteSelected = selected !== null && selected.senderId === me.id && !selected.deletedAt

  const headerTitle = !isGroup && other ? other.name : displayName || 'Conversation'

  const subtitle = typerLabel.length > 0
    ? typerLabel
    : isGroup
      ? `${detailData?.members.length ?? 0} members · ${onlineOthers} online`
      : !other
        ? ''
        : realtime.onlineIds.has(other.id)
          ? 'online'
          : 'offline'

  const dotColor = themeMounted && resolvedTheme === 'dark' ? 'rgba(255,255,255,0.055)' : 'rgba(0,0,0,0.05)'

  return (
    <motion.div
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={{ type: 'spring', stiffness: 320, damping: 34 }}
      className="absolute inset-0 z-40 flex flex-col bg-white dark:bg-zinc-900"
      role="dialog"
      aria-label={`Conversation with ${headerTitle}`}
    >
      {/* header */}
      <header className="relative z-20 flex min-h-14 shrink-0 items-center gap-1.5 border-b border-zinc-200 bg-white px-2 pt-[env(safe-area-inset-top)] dark:border-zinc-800 dark:bg-zinc-900">
        <Button
          variant="ghost"
          size="icon"
          aria-label="Back to chats"
          onClick={() => {
            stopTyping()
            onClose()
          }}
          className="size-10 shrink-0 rounded-full text-zinc-600 hover:bg-transparent hover:text-zinc-900 active:scale-95 dark:text-zinc-300 dark:hover:text-white"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </Button>
        {isGroup ? (
          <GroupAvatar title={displayName} id={conversationId} size={36} />
        ) : (
          <UserAvatar
            name={other?.name ?? displayName}
            color={other?.color}
            size={36}
            showPresence
            online={other ? realtime.onlineIds.has(other.id) : false}
          />
        )}
        <button
          type="button"
          onClick={() => setInfoOpen(true)}
          aria-label="Show info"
          className="ml-1.5 min-w-0 flex-1 text-left outline-none"
        >
          <p className="truncate text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
            {headerTitle}
          </p>
          <AnimatePresence mode="wait" initial={false}>
            <motion.p
              key={subtitle}
              initial={{ opacity: 0, y: 3 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.18 }}
              className={cn(
                'truncate text-[11px]',
                typerLabel.length > 0
                  ? 'font-medium text-emerald-600 italic dark:text-emerald-400'
                  : 'text-zinc-500 dark:text-zinc-400',
              )}
            >
              {subtitle}
            </motion.p>
          </AnimatePresence>
        </button>

        <Button
          variant="ghost"
          size="icon"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label="Conversation menu"
          onClick={() => setMenuOpen((v) => !v)}
          className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
        >
          <EllipsisVertical className="size-5" aria-hidden />
        </Button>

        <AnimatePresence>
          {menuOpen ? (
            <>
              <button
                type="button"
                aria-hidden
                tabIndex={-1}
                onClick={() => setMenuOpen(false)}
                className="fixed inset-0 z-30 cursor-default outline-none"
              />
              <motion.div
                initial={{ opacity: 0, scale: 0.92, y: -4 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95 }}
                transition={{ duration: 0.14 }}
                role="menu"
                className="absolute top-[calc(3.5rem+env(safe-area-inset-top))] right-2 z-40 min-w-44 overflow-hidden rounded-xl border border-zinc-200 bg-white p-1 shadow-lg dark:border-zinc-700 dark:bg-zinc-800"
              >
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setMenuOpen(false)
                    setInfoOpen(true)
                  }}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                >
                  {isGroup ? 'Group info' : 'Contact info'}
                </button>
              </motion.div>
            </>
          ) : null}
        </AnimatePresence>
      </header>

      {/* messages */}
      <div
        ref={viewportRef}
        onScroll={handleScroll}
        className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain bg-zinc-50 px-3 pt-3 pb-2 dark:bg-black/25"
        style={{
          backgroundImage: `radial-gradient(circle, ${dotColor} 1px, transparent 1px)`,
          backgroundSize: '16px 16px',
        }}
      >
        {messages.isPending && !historyLoaded ? (
          <div role="status" aria-label="Loading messages" className="space-y-3 pt-4">
            <Skeleton className="mx-auto h-5 w-24 rounded-full" />
            <Skeleton className="h-9 w-2/5 rounded-2xl" />
            <Skeleton className="ml-auto h-12 w-1/2 rounded-2xl" />
            <Skeleton className="h-9 w-1/3 rounded-2xl" />
            <Skeleton className="ml-auto h-9 w-2/5 rounded-2xl" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-1 text-center">
            <p className="text-sm font-semibold text-zinc-600 dark:text-zinc-300">No messages yet</p>
            <p className="text-xs text-zinc-400 dark:text-zinc-500">
              Say hello — your words travel in real time.
            </p>
          </div>
        ) : (
          <div className="flex flex-col">
            {items.map((item) =>
              item.kind === 'day' ? (
                <div key={item.key} className="my-3 flex justify-center">
                  <span className="rounded-full bg-zinc-200/70 px-3 py-1 text-[11px] font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">
                    {item.label}
                  </span>
                </div>
              ) : item.kind === 'unread' ? (
                <div key={item.key} className="my-3 flex items-center gap-2 px-1" role="separator" aria-label="Unread messages">
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                  <span className="text-[10px] font-bold tracking-widest text-emerald-600 dark:text-emerald-400">
                    UNREAD
                  </span>
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                </div>
              ) : (
                <MessageRow
                  key={item.key}
                  message={item.message}
                  head={item.head}
                  mine={item.message.senderId === me.id}
                  isGroup={isGroup}
                  readMs={othersMaxReadMs}
                  myId={me.id}
                  onPress={setSelected}
                  onStartLongPress={startLongPress}
                  onEndLongPress={clearLongPress}
                  onToggleReaction={handleToggleReaction}
                />
              ),
            )}

            <AnimatePresence>
              {typers.length > 0 ? (
                <motion.div
                  key="typing-bubble"
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: 4 }}
                  transition={{ duration: 0.15 }}
                  className="mt-1.5 flex items-end gap-1.5"
                >
                  {isGroup ? (
                    (() => {
                      const typer =
                        typers.find((t) => detailData?.members.some((m) => m.id === t.userId)) ??
                        typers[0]
                      return (
                        <UserAvatar
                          name={typer.userName}
                          color={
                            detailData?.members.find((m) => m.id === typer.userId)?.color ?? 'emerald'
                          }
                          size={28}
                        />
                      )
                    })()
                  ) : other ? (
                    <UserAvatar name={other.name} color={other.color} size={28} />
                  ) : null}
                  <div className="rounded-2xl rounded-bl-md border border-zinc-100 bg-white px-3 py-2.5 shadow-sm dark:border-zinc-700 dark:bg-zinc-800">
                    <TypingDots />
                  </div>
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>
        )}

        <AnimatePresence>
          {showJump ? (
            <motion.button
              type="button"
              aria-label="Jump to newest messages"
              initial={{ opacity: 0, y: 10, scale: 0.85 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.9 }}
              transition={{ type: 'spring', stiffness: 420, damping: 22 }}
              onClick={() => {
                setShowJump(false)
                haptic(8)
                scrollToBottom(true)
              }}
              className="sticky bottom-1 z-10 ml-auto mr-1 mt-2 flex items-center gap-1.5 rounded-full bg-emerald-500 py-2 pr-3.5 pl-3 text-xs font-semibold text-white shadow-lg shadow-emerald-600/30 active:scale-95"
            >
              New messages
              <ArrowDown className="size-3.5" aria-hidden />
            </motion.button>
          ) : null}
        </AnimatePresence>
      </div>

      {/* composer */}
      <div className="shrink-0 border-t border-zinc-200 bg-white p-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] dark:border-zinc-800 dark:bg-zinc-900">
        <AnimatePresence initial={false}>
          {replyTo ? (
            <motion.div
              key="reply-bar"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-start gap-2 rounded-xl border-l-4 border-emerald-500 bg-zinc-100 py-2 pr-2 pl-2.5 dark:bg-zinc-800">
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-bold text-emerald-600 dark:text-emerald-400">
                    Replying to {replyTo.sender.id === me.id ? 'yourself' : replyTo.sender.name}
                  </p>
                  <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">
                    {replyTo.deletedAt
                      ? 'Deleted message'
                      : replyTo.content.replace(/\s+/g, ' ').slice(0, 120)}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Cancel reply"
                  onClick={() => setReplyTo(null)}
                  className="rounded-full p-1.5 text-zinc-400 outline-none transition-colors hover:bg-zinc-200 hover:text-zinc-600 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
                >
                  <X className="size-4" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <div className="flex items-end gap-2">
          <Popover>
            <PopoverTrigger asChild>
              <button
                type="button"
                aria-label="Insert emoji"
                className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-amber-500 active:scale-90 dark:hover:bg-zinc-800"
              >
                <Smile className="size-6" aria-hidden />
              </button>
            </PopoverTrigger>
            <PopoverContent
              side="top"
              align="start"
              sideOffset={10}
              className="w-[272px] rounded-2xl p-2 dark:bg-zinc-800"
            >
              <div className="grid grid-cols-8 gap-0.5">
                {EMOJI_PICKER_CHOICES.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    aria-label={`Insert ${emoji}`}
                    onClick={() => {
                      setInput((prev) => prev + emoji)
                      requestAnimationFrame(() => {
                        autosize()
                        textareaRef.current?.focus()
                      })
                    }}
                    className="rounded-lg py-1 text-xl outline-none transition-transform hover:bg-zinc-100 hover:scale-125 active:scale-95 dark:hover:bg-zinc-700"
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </PopoverContent>
          </Popover>
          <textarea
            ref={textareaRef}
            value={input}
            rows={1}
            aria-label="Message input"
            placeholder="Type a message"
            maxLength={2000}
            enterKeyHint="send"
            onChange={(e) => handleInputChange(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={stopTyping}
            className="pulse-scroll max-h-[120px] flex-1 resize-none rounded-3xl border border-zinc-200 bg-zinc-50 px-4 py-2.5 text-sm leading-snug text-zinc-900 outline-none transition-colors focus:border-emerald-400 focus:bg-white dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100 dark:focus:border-emerald-500/70"
          />
          <button
            type="button"
            aria-label="Send message"
            disabled={input.trim().length === 0 || sendMessage.isPending}
            onClick={submit}
            className={cn(
              'flex size-11 shrink-0 items-center justify-center rounded-full transition-all active:scale-90',
              input.trim().length > 0
                ? 'bg-emerald-500 text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90'
                : 'bg-zinc-200 text-zinc-400 dark:bg-zinc-700 dark:text-zinc-500',
            )}
          >
            <SendHorizontal className="size-5" aria-hidden />
          </button>
        </div>
      </div>

      {/* message actions */}
      <Dialog open={selected !== null} onOpenChange={(open) => !open && setSelected(null)}>
        <DialogContent className="max-w-[300px] gap-3 rounded-2xl p-4 sm:left-1/2 sm:translate-x-[-50%] dark:bg-zinc-900">
          <DialogHeader className="text-left">
            <DialogTitle className="text-sm font-bold tracking-tight">Message options</DialogTitle>
            <DialogDescription className="text-xs leading-relaxed">
              {selected?.deletedAt
                ? 'This message was deleted.'
                : selected?.content.replace(/\s+/g, ' ').slice(0, 140)}
            </DialogDescription>
          </DialogHeader>
          {selected && !selected.deletedAt ? (
            <div className="flex items-center justify-between gap-0.5" role="group" aria-label="React with an emoji">
              {REACTION_CHOICES.map((emoji) => (
                <button
                  key={emoji}
                  type="button"
                  aria-label={`React with ${emoji}`}
                  onClick={() => {
                    handleToggleReaction(selected.id, emoji)
                    setSelected(null)
                  }}
                  className="flex size-10 items-center justify-center rounded-full text-xl outline-none transition-transform hover:scale-125 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
                >
                  {emoji}
                </button>
              ))}
            </div>
          ) : null}
          <div className="flex flex-col gap-1.5">
            <Button
              variant="outline"
              onClick={() => {
                if (!selected) return
                setReplyTo(selected)
                setSelected(null)
                requestAnimationFrame(() => textareaRef.current?.focus())
              }}
              disabled={!!selected?.deletedAt}
              className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
            >
              <Reply className="size-4" aria-hidden />
              Reply
            </Button>
            <Button
              variant="outline"
              onClick={copySelected}
              disabled={!!selected?.deletedAt}
              className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
            >
              <Copy className="size-4" aria-hidden />
              Copy text
            </Button>
            <Button
              variant="outline"
              disabled={!canDeleteSelected}
              onClick={() => setConfirmingDelete(true)}
              className="h-10 justify-start gap-2 rounded-xl border-destructive/40 text-sm font-medium text-destructive hover:bg-destructive/10 hover:text-destructive"
            >
              <Trash2 className="size-4" aria-hidden />
              Delete for everyone
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={confirmingDelete} onOpenChange={setConfirmingDelete}>
        <AlertDialogContent className="max-w-[320px] rounded-2xl bg-white dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
          <AlertDialogHeader>
            <AlertDialogTitle className="tracking-tight">Delete this message?</AlertDialogTitle>
            <AlertDialogDescription className="text-[13px] leading-relaxed">
              It will be replaced with a tombstone for everyone here. This can&apos;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="gap-2">
            <AlertDialogCancel className="rounded-xl">Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={deleteMessage.isPending}
              onClick={() => {
                if (selected) deleteMessage.mutate(selected.id)
              }}
              className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* info dialog */}
      <InfoDialog
        open={infoOpen}
        onOpenChange={setInfoOpen}
        detail={detailData ?? null}
        me={me}
        onlineIds={realtime.onlineIds}
      />
    </motion.div>
  )
}

// ── pieces ───────────────────────────────────────────────────

function TypingDots() {
  return (
    <span className="inline-flex items-center gap-1 py-0.5" aria-hidden>
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          animate={{ y: [0, -3, 0], opacity: [0.5, 1, 0.5] }}
          transition={{ repeat: Infinity, duration: 0.9, delay: i * 0.15, ease: 'easeInOut' }}
          className="size-1.5 rounded-full bg-zinc-400 dark:bg-zinc-500"
        />
      ))}
    </span>
  )
}

interface MessageRowProps {
  message: ChatMessage
  head: boolean
  mine: boolean
  isGroup: boolean
  readMs: number
  myId: string
  onPress: (message: ChatMessage) => void
  onStartLongPress: (message: ChatMessage) => void
  onEndLongPress: () => void
  onToggleReaction: (messageId: string, emoji: string) => void
}

/** Bubble body text with URL auto-linking (safe anchors, no HTML injection). */
function BubbleText({ content, mine }: { content: string; mine: boolean }) {
  const segments = splitUrlSegments(content)
  return (
    <p
      className={cn(
        'text-[14px] leading-snug break-words whitespace-pre-wrap',
        mine ? 'text-white' : 'text-zinc-900 dark:text-zinc-100',
      )}
    >
      {segments.map((seg, i) =>
        seg.kind === 'url' ? (
          <a
            key={i}
            href={seg.value.startsWith('www.') ? `https://${seg.value}` : seg.value}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => e.stopPropagation()}
            className={cn(
              'underline underline-offset-2',
              mine
                ? 'text-white decoration-white/60 hover:decoration-white'
                : 'text-emerald-700 decoration-emerald-400/60 hover:decoration-emerald-600 dark:text-emerald-400',
            )}
          >
            {seg.value}
          </a>
        ) : (
          <span key={i}>{seg.value}</span>
        ),
      )}
    </p>
  )
}

const MessageRow = memo(function MessageRow({
  message,
  head,
  mine,
  isGroup,
  readMs,
  myId,
  onPress,
  onStartLongPress,
  onEndLongPress,
  onToggleReaction,
}: MessageRowProps) {
  const deleted = message.deletedAt !== null
  const pending = message.id.startsWith('temp-')
  const createdMs = Date.parse(message.createdAt)
  const isRead = !Number.isNaN(createdMs) && createdMs <= readMs
  const interactive = !deleted && !pending
  const jumbo = !deleted && isJumboEmoji(message.content)
  const hasReactions = message.reactions.length > 0

  return (
    <div
      className={cn(
        'flex w-full',
        mine ? 'justify-end' : 'justify-start',
        head ? 'mt-2.5' : 'mt-0.5',
      )}
    >
      {!mine && isGroup ? (
        head ? (
          <div className="mr-1.5 flex shrink-0 items-end pb-5">
            <UserAvatar name={message.sender.name} color={message.sender.color} size={28} />
          </div>
        ) : (
          <span className="mr-1.5 block w-7 shrink-0" aria-hidden />
        )
      ) : null}

      <div className={cn('flex max-w-[78%] flex-col', mine ? 'items-end' : 'items-start')}>
        {!mine && isGroup && head && !deleted ? (
          <span className="mb-0.5 ml-1 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
            {message.sender.name}
          </span>
        ) : null}

        <motion.div
          initial={{ opacity: 0, y: 8, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 480, damping: 32, mass: 0.7 }}
          onClick={() => interactive && onPress(message)}
          onPointerDown={() => interactive && onStartLongPress(message)}
          onPointerUp={onEndLongPress}
          onPointerLeave={onEndLongPress}
          onDoubleClick={() => {
            if (interactive) {
              onToggleReaction(message.id, '❤️')
            }
          }}
          role={interactive ? 'button' : undefined}
          tabIndex={interactive ? 0 : undefined}
          onKeyDown={(event) => {
            if (interactive && event.key === 'Enter') onPress(message)
          }}
          className={cn(
            'relative select-none',
            jumbo
              ? cn(
                  'px-1 py-0.5',
                  deleted && 'rounded-2xl',
                )
              : 'rounded-2xl px-3 py-2 shadow-sm',
            deleted &&
              cn(
                'border border-dashed italic',
                'rounded-2xl border-zinc-300 bg-transparent text-zinc-400 dark:border-zinc-600 dark:text-zinc-500',
                mine ? 'rounded-br-md opacity-80' : 'rounded-bl-md',
              ),
            !deleted && !jumbo && (mine
              ? 'rounded-2xl rounded-br-md bg-emerald-500 text-white'
              : 'rounded-2xl rounded-bl-md border border-zinc-100 bg-white text-zinc-900 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100'),
            interactive
              ? cn(
                  'cursor-pointer focus-visible:ring-2 focus-visible:ring-emerald-500/50 active:brightness-95',
                  jumbo && 'rounded-2xl',
                )
              : '',
          )}
        >
          {deleted ? (
            <p className="text-[13px] leading-snug">This message was deleted</p>
          ) : (
            <>
              {message.replyTo ? (
                <div
                  className={cn(
                    'mb-1 rounded-md border-l-[3px] px-2 py-1',
                    mine
                      ? 'border-white/70 bg-black/10'
                      : 'border-emerald-400 bg-zinc-100 dark:border-emerald-500/80 dark:bg-zinc-700/60',
                  )}
                >
                  <p
                    className={cn(
                      'text-[11px] font-bold',
                      mine ? 'text-white/90' : 'text-emerald-700 dark:text-emerald-400',
                    )}
                  >
                    {message.replyTo.deleted
                      ? 'Deleted message'
                      : message.replyTo.senderName === message.sender.name
                        ? message.replyTo.senderName
                        : message.replyTo.senderName || 'Unknown'}
                  </p>
                  <p
                    className={cn(
                      'truncate text-[12px] leading-snug',
                      mine ? 'text-white/75' : 'text-zinc-500 dark:text-zinc-400',
                    )}
                  >
                    {message.replyTo.deleted
                      ? 'This message was deleted'
                      : message.replyTo.content.replace(/\s+/g, ' ').slice(0, 120)}
                  </p>
                </div>
              ) : null}
              {jumbo ? (
                <p className="text-[34px] leading-[1.2] break-words">{message.content}</p>
              ) : (
                <BubbleText content={message.content} mine={mine} />
              )}
            </>
          )}
          <div
            className={cn(
              'mt-0.5 flex items-center justify-end gap-1 text-[10px]',
              deleted
                ? 'text-zinc-400 dark:text-zinc-500'
                : mine && !jumbo
                  ? 'text-white/80'
                  : 'text-zinc-400 dark:text-zinc-500',
            )}
          >
            <span className={jumbo ? 'opacity-70' : undefined}>{formatTime(message.createdAt)}</span>
            {mine && !deleted ? (
              pending ? (
                <Clock className="size-3 opacity-90" aria-label="sending…" />
              ) : isRead ? (
                <CheckCheck className="size-3.5 text-white/90" aria-label="read" />
              ) : (
                <Check className="size-3 text-white/60" aria-label="sent" />
              )
            ) : null}
          </div>
        </motion.div>

        {hasReactions && !deleted ? (
          <motion.div
            initial={{ opacity: 0, scale: 0.7, y: -2 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            transition={{ type: 'spring', stiffness: 500, damping: 24 }}
            className={cn(
              '-mt-1.5 z-10 flex flex-wrap gap-1',
              mine ? 'mr-2 justify-end' : 'ml-2 justify-start',
            )}
          >
            {message.reactions.map((group) => {
              const iReacted = group.userIds.includes(myId)
              return (
                <button
                  key={group.emoji}
                  type="button"
                  aria-label={`${group.emoji} ${group.count} — tap to toggle`}
                  onClick={() => onToggleReaction(message.id, group.emoji)}
                  className={cn(
                    'flex items-center gap-0.5 rounded-full border px-1.5 py-0.5 text-[11px] shadow-sm backdrop-blur transition-transform active:scale-90',
                    iReacted
                      ? 'border-emerald-400 bg-emerald-50 dark:border-emerald-500/70 dark:bg-emerald-500/15'
                      : 'border-zinc-200 bg-white/95 dark:border-zinc-600 dark:bg-zinc-800/95',
                  )}
                >
                  <span className="text-xs leading-none">{group.emoji}</span>
                  {group.count > 1 ? (
                    <span
                      className={cn(
                        'font-semibold',
                        iReacted
                          ? 'text-emerald-700 dark:text-emerald-300'
                          : 'text-zinc-500 dark:text-zinc-300',
                      )}
                    >
                      {group.count}
                    </span>
                  ) : null}
                </button>
              )
            })}
          </motion.div>
        ) : null}
      </div>
    </div>
  )
}, rowsEqual)

function rowsEqual(prev: MessageRowProps, next: MessageRowProps): boolean {
  return (
    prev.message === next.message &&
    prev.head === next.head &&
    prev.mine === next.mine &&
    prev.isGroup === next.isGroup &&
    prev.readMs === next.readMs &&
    prev.myId === next.myId &&
    prev.onPress === next.onPress &&
    prev.onStartLongPress === next.onStartLongPress &&
    prev.onEndLongPress === next.onEndLongPress &&
    prev.onToggleReaction === next.onToggleReaction
  )
}

function InfoDialog({
  open,
  onOpenChange,
  detail,
  me,
  onlineIds,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: ConversationDetail | null
  me: AppUser
  onlineIds: ReadonlySet<string>
}) {
  if (!detail) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-[320px] rounded-2xl sm:left-1/2 sm:translate-x-[-50%]" />
      </Dialog>
    )
  }
  const title = detail.isGroup
    ? detail.name?.trim() || 'Group'
    : otherMemberOf(detail, me.id)?.name ?? 'Direct message'

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[340px] gap-4 rounded-2xl p-4 sm:left-1/2 sm:translate-x-[-50%] dark:bg-zinc-900">
        <DialogHeader>
          <div className="flex items-center gap-3">
            {detail.isGroup ? (
              <GroupAvatar title={title} id={detail.id} size={44} />
            ) : (
              (() => {
                const o = otherMemberOf(detail, me.id)
                return (
                  <UserAvatar
                    name={o?.name ?? title}
                    color={o?.color}
                    size={44}
                    showPresence
                    online={o ? onlineIds.has(o.id) : false}
                  />
                )
              })()
            )}
            <div className="min-w-0 text-left">
              <DialogTitle className="truncate text-base font-bold tracking-tight">{title}</DialogTitle>
              <DialogDescription className="text-xs">
                {detail.isGroup ? `${detail.members.length} members` : 'Direct conversation'}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>
        <ul className="pulse-scroll max-h-64 space-y-1 overflow-y-auto pr-1">
          {detail.members.map((member) => {
            const isMe = member.id === me.id
            const online = onlineIds.has(member.id)
            return (
              <li
                key={member.id}
                className="flex items-center gap-3 rounded-xl bg-zinc-50 p-2.5 dark:bg-zinc-800/60"
              >
                <UserAvatar name={member.name} color={member.color} size={38} showPresence online={online} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                    {member.name}
                    {isMe ? <span className="ml-1 text-xs font-normal text-zinc-400">(you)</span> : null}
                  </p>
                  <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">{member.about}</p>
                </div>
                <span className="shrink-0 text-right text-[10px] leading-tight text-zinc-400 dark:text-zinc-500">
                  {isMe ? (
                    <>
                      all caught up<br />
                      <span className="font-semibold text-zinc-500 dark:text-zinc-400">
                        read {formatListStamp(member.lastReadAt)}
                      </span>
                    </>
                  ) : (
                    <>
                      last read<br />
                      <span className="font-semibold text-zinc-500 dark:text-zinc-400">
                        {formatListStamp(member.lastReadAt)}
                      </span>
                    </>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      </DialogContent>
    </Dialog>
  )
}

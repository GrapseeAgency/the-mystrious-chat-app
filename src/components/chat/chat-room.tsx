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
  useLayoutEffect,
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
  ChevronUp,
  Clock,
  Copy,
  EllipsisVertical,
  ImagePlus,
  LoaderCircle,
  LogOut,
  Mic,
  Pause,
  Pencil,
  Play,
  Plus,
  Reply,
  SendHorizontal,
  Smile,
  Trash2,
  UserPlus,
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
  compressImageToDataUrl,
  conversationDisplayName,
  formatDayChip,
  formatListStamp,
  formatTime,
  hashString,
  isJumboEmoji,
  isSameDayIso,
  otherMemberOf,
  REACTION_CHOICES,
  EMOJI_PICKER_CHOICES,
  splitUrlSegments,
  uid,
} from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
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
import { Drawer, DrawerContent } from '@/components/ui/drawer'
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
  hasMore?: boolean
  total?: number
}
interface SendResponse {
  message: ChatMessage
}
interface DeleteResponse {
  message: ChatMessage
}

const CLUSTER_WINDOW_MS = 5 * 60 * 1000
const NEAR_BOTTOM_PX = 160
const OLDER_PAGE_SIZE = 40
const MESSAGES_PAGE_SIZE = 200
const MIN_VOICE_MS = 600
/** "1:23" (minutes:seconds) for voice notes + record timer. */
function formatVoicems(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

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

  // restore persisted draft once per opened conversation
  const [input, setInput] = useState(() => pulseDraftsStore.getState().drafts[conversationId] ?? '')
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  const [selected, setSelected] = useState<ChatMessage | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [showJump, setShowJump] = useState(false)
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null)
  /** {message, emoji} → who-reacted sheet */
  const [reactionInfo, setReactionInfo] = useState<{ message: ChatMessage; emoji: string } | null>(null)
  const [sendingImage, setSendingImage] = useState(false)
  const [hasMoreHistory, setHasMoreHistory] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [recording, setRecording] = useState(false)
  const [recordMs, setRecordMs] = useState(0)
  const [sendingVoice, setSendingVoice] = useState(false)

  const viewportRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const nearBottomRef = useRef(true)
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** flipped (inside rAF) after the first history fetch so render stays ref-free */
  const [historyLoaded, setHistoryLoaded] = useState(false)
  /** pending scroll-anchor restore after prepending an older page */
  const scrollRestoreRef = useRef<{ prevHeight: number; prevTop: number } | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const recordChunksRef = useRef<Blob[]>([])
  const recordStartedAtRef = useRef(0)
  const recordTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const recordCancelRef = useRef(false)

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
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${MESSAGES_PAGE_SIZE}`,
      )
      // Merge with whatever is cached (older pages loaded via "Load older")
      // so a background refetch never amputates already-loaded history.
      const previous = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
      if (!previous || previous.length === 0) {
        setHasMoreHistory(res.total !== undefined ? res.messages.length < res.total : res.hasMore === true)
        return res.messages
      }
      const byId = new Map(previous.map((m) => [m.id, m]))
      for (const m of res.messages) byId.set(m.id, m)
      setHasMoreHistory(res.total !== undefined ? byId.size < res.total : res.hasMore === true)
      return [...byId.values()].sort(
        (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
      )
    },
    staleTime: 15_000,
    // safety net: messages still arrive within seconds even without websocket realtime
    refetchInterval: 3_500,
  })

  /** Fetch the next page of history and prepend it, keeping scroll anchored. */
  const loadOlder = useCallback(async () => {
    const list = messages.data ?? []
    if (loadingOlder || list.length === 0) return
    const oldest = list[0]
    const el = viewportRef.current
    const prevHeight = el?.scrollHeight ?? 0
    const prevTop = el?.scrollTop ?? 0
    setLoadingOlder(true)
    try {
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${OLDER_PAGE_SIZE}&before=${encodeURIComponent(oldest.createdAt)}`,
      )
      // Arm the anchor only now — the very next render (data applied) restores it.
      scrollRestoreRef.current = { prevHeight, prevTop }
      let cachedCount = res.messages.length
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
        if (!old || old.length === 0) return res.messages
        const known = new Set(old.map((m) => m.id))
        const additions = res.messages.filter((m) => !known.has(m.id))
        cachedCount = old.length + additions.length
        return [...additions, ...old]
      })
      setHasMoreHistory(res.total !== undefined ? cachedCount < res.total : res.hasMore === true)
      haptic(6)
    } catch {
      toast.error('Could not load older messages')
    } finally {
      setLoadingOlder(false)
    }
  }, [messages.data, loadingOlder, conversationId, queryClient])

  // scroll anchor: after prepending, keep the viewport pinned to the same content
  useLayoutEffect(() => {
    const pending = scrollRestoreRef.current
    const el = viewportRef.current
    if (!pending || !el) return
    scrollRestoreRef.current = null
    el.scrollTop = el.scrollHeight - pending.prevHeight + pending.prevTop
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
        haptic(10)
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
      imagePath,
      audioPath,
      durationMs,
    }: {
      clientId: string
      content: string
      replyToId?: string
      imagePath?: string
      audioPath?: string
      durationMs?: number
    }) => {
      const res = await apiJson<SendResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            senderId: me.id,
            content,
            ...(replyToId ? { replyToId } : {}),
            ...(imagePath ? { imagePath } : {}),
            ...(audioPath ? { audioPath, ...(durationMs ? { durationMs } : {}) } : {}),
          }),
        },
      )
      return { res, clientId }
    },
    onMutate: async ({ clientId, content, replyToId, imagePath, audioPath, durationMs }) => {
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
        imagePath: imagePath ?? null,
        audioPath: audioPath ?? null,
        durationMs: durationMs ?? null,
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
    pulseDraftsStore.getState().clearDraft(conversationId)
    requestAnimationFrame(autosize)
    sendMessage.mutate({
      clientId: uid(),
      content,
      ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
    })
  }, [input, sendMessage, stopTyping, autosize, replyTo, conversationId])

  const handleInputChange = (value: string) => {
    setInput(value)
    autosize()
    if (draftTimerRef.current !== null) clearTimeout(draftTimerRef.current)
    draftTimerRef.current = setTimeout(() => {
      draftTimerRef.current = null
      pulseDraftsStore.getState().setDraft(conversationId, value)
    }, 300)
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

  /** Pick → compress → upload → send as an image message. */
  const handleImagePicked = async (file: File | undefined) => {
    if (!file || sendingImage) return
    setSendingImage(true)
    try {
      const dataUrl = await compressImageToDataUrl(file)
      const up = await apiJson<{ imagePath: string }>('/api/uploads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dataUrl }),
      })
      haptic(12)
      sendMessage.mutate({
        clientId: uid(),
        content: '',
        imagePath: up.imagePath,
        ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not send the image')
    } finally {
      setSendingImage(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
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

  // ── voice notes ────────────────────────────────────────────

  const teardownRecorder = useCallback(() => {
    if (recordTimerRef.current !== null) {
      clearInterval(recordTimerRef.current)
      recordTimerRef.current = null
    }
    recorderRef.current = null
    setRecording(false)
    setRecordMs(0)
  }, [])

  /** stop = false → cancel (discard); stop = true → upload + send. */
  const finishRecording = useCallback(
    (send: boolean) => {
      const rec = recorderRef.current
      if (!rec) return
      recordCancelRef.current = !send
      try {
        rec.stop()
      } catch {
        teardownRecorder()
      }
    },
    [teardownRecorder],
  )

  const startRecording = useCallback(async () => {
    if (recorderRef.current || sendingVoice) return
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      toast.error('Voice notes are not supported in this browser')
      return
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      toast.error('Microphone access was denied — check browser permissions')
      return
    }
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
    const mimeType = candidates.find((t) => MediaRecorder.isTypeSupported(t))
    const rec = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
    recordChunksRef.current = []
    recordCancelRef.current = false
    rec.ondataavailable = (event) => {
      if (event.data.size > 0) recordChunksRef.current.push(event.data)
    }
    rec.onstop = async () => {
      stream.getTracks().forEach((track) => track.stop())
      const elapsed = Date.now() - recordStartedAtRef.current
      const cancelled = recordCancelRef.current
      const chunks = recordChunksRef.current
      const type = rec.mimeType || 'audio/webm'
      teardownRecorder()
      if (cancelled || elapsed < MIN_VOICE_MS || chunks.length === 0) {
        if (!cancelled && elapsed < MIN_VOICE_MS) toast.info('Hold too short — voice note discarded')
        return
      }
      setSendingVoice(true)
      try {
        const buffer = await new Blob(chunks, { type }).arrayBuffer()
        const bytes = new Uint8Array(buffer)
        const CHUNK = 0x8000
        let binary = ''
        for (let i = 0; i < bytes.length; i += CHUNK) {
          binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
        }
        const up = await apiJson<{ filePath: string }>('/api/uploads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ dataUrl: `data:${type};base64,${btoa(binary)}` }),
        })
        haptic(12)
        sendMessage.mutate({
          clientId: uid(),
          content: '',
          audioPath: up.filePath,
          durationMs: Math.max(1, Math.round(elapsed / 100) * 100),
          ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
        })
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Could not send the voice note')
      } finally {
        setSendingVoice(false)
      }
    }
    recorderRef.current = rec
    recordStartedAtRef.current = Date.now()
    setRecording(true)
    setRecordMs(0)
    rec.start(250)
    recordTimerRef.current = setInterval(() => {
      setRecordMs(Date.now() - recordStartedAtRef.current)
    }, 200)
    haptic(14)
  }, [sendMessage, replyTo, sendingVoice, teardownRecorder])

  // safety: leaving the room (or tab) mid-recording discards the note
  useEffect(
    () => () => {
      const rec = recorderRef.current
      if (rec) {
        recordCancelRef.current = true
        try {
          rec.stop()
        } catch {
          // already stopped
        }
      }
    },
    [],
  )

  // ── group management ───────────────────────────────────────

  const renameGroup = useMutation({
    mutationFn: async (name: string) => {
      return apiJson<DetailResponse>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id, name }),
      })
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Group name updated')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not rename the group')
    },
  })

  const addMembers = useMutation({
    mutationFn: async (userIds: string[]) => {
      return apiJson<DetailResponse & { added: string[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id, userIds }),
        },
      )
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(res.added.length === 1 ? '1 member added' : `${res.added.length} members added`)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not add members')
    },
  })

  const leaveGroup = useMutation({
    mutationFn: async () => {
      return apiJson<{ ok: boolean }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id }),
        },
      )
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('You left the group')
      setInfoOpen(false)
      stopTyping()
      onClose()
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not leave the group')
    },
  })

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
          <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
            <div
              aria-hidden
              className="flex size-16 items-center justify-center rounded-3xl bg-gradient-to-br from-emerald-400/15 to-emerald-600/10 text-emerald-500 dark:from-emerald-400/10 dark:to-emerald-600/5"
            >
              <SendHorizontal className="size-7 -rotate-45" />
            </div>
            <div>
              <p className="text-sm font-semibold text-zinc-600 dark:text-zinc-300">No messages yet</p>
              <p className="mt-1 text-xs text-zinc-400 dark:text-zinc-500">
                Say hello — your words travel in real time.
              </p>
            </div>
          </div>
        ) : (
          <div className="flex flex-col">
            {hasMoreHistory ? (
              <div className="flex justify-center pb-3">
                <button
                  type="button"
                  disabled={loadingOlder}
                  onClick={() => void loadOlder()}
                  className="flex items-center gap-1.5 rounded-full border border-zinc-200 bg-white/90 px-3.5 py-1.5 text-xs font-semibold text-zinc-500 shadow-sm outline-none backdrop-blur transition-colors hover:border-emerald-300 hover:text-emerald-600 active:scale-95 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-800/90 dark:text-zinc-400 dark:hover:border-emerald-500/50 dark:hover:text-emerald-400"
                >
                  {loadingOlder ? (
                    <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                  ) : (
                    <ChevronUp className="size-3.5" aria-hidden />
                  )}
                  {loadingOlder ? 'Loading…' : 'Load older messages'}
                </button>
              </div>
            ) : null}
            {items.map((item) =>
              item.kind === 'day' ? (
                <div key={item.key} className="my-3 flex justify-center">
                  <span className="rounded-full bg-zinc-200/70 px-3 py-1 text-[11px] font-medium text-zinc-600 shadow-sm ring-1 ring-black/5 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-white/5">
                    {item.label}
                  </span>
                </div>
              ) : item.kind === 'unread' ? (
                <div key={item.key} className="my-3 flex items-center gap-2 px-1" role="separator" aria-label="Unread messages">
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                  <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold tracking-widest text-emerald-600 dark:text-emerald-400">
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
                  onReply={(m) => {
                    setReplyTo(m)
                    requestAnimationFrame(() => textareaRef.current?.focus())
                  }}
                  onReactionInfo={(m, emoji) => setReactionInfo({ message: m, emoji })}
                  onOpenImage={setLightboxSrc}
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
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            aria-hidden
            tabIndex={-1}
            onChange={(e) => void handleImagePicked(e.target.files?.[0])}
          />
          {recording ? (
            <>
              <button
                type="button"
                aria-label="Cancel recording"
                onClick={() => finishRecording(false)}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 outline-none transition-transform hover:bg-rose-200 active:scale-90 dark:bg-rose-500/15 dark:text-rose-400 dark:hover:bg-rose-500/25"
              >
                <X className="size-5" aria-hidden />
              </button>
              <div
                role="status"
                aria-label="Recording voice note"
                className="flex h-11 flex-1 items-center gap-2.5 rounded-full border border-rose-200 bg-rose-50 px-4 dark:border-rose-500/30 dark:bg-rose-500/10"
              >
                <span className="relative flex size-2.5 shrink-0" aria-hidden>
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-rose-400 opacity-75" />
                  <span className="relative inline-flex size-2.5 rounded-full bg-rose-500" />
                </span>
                <span className="text-sm font-semibold tabular-nums text-rose-600 dark:text-rose-400">
                  {formatVoicems(recordMs)}
                </span>
                <span className="ml-auto truncate text-xs text-zinc-400 dark:text-zinc-500">
                  Recording voice note…
                </span>
              </div>
              <button
                type="button"
                aria-label="Stop and send voice note"
                disabled={sendingVoice}
                onClick={() => finishRecording(true)}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-90 disabled:opacity-60"
              >
                {sendingVoice ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <SendHorizontal className="size-5" aria-hidden />
                )}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                aria-label="Send a photo"
                disabled={sendingImage}
                onClick={() => fileInputRef.current?.click()}
                className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 active:scale-90 disabled:opacity-50 dark:hover:bg-zinc-800"
              >
                {sendingImage ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <ImagePlus className="size-[22px]" aria-hidden />
                )}
              </button>
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
              {input.trim().length === 0 ? (
                <button
                  type="button"
                  aria-label="Record voice note"
                  onClick={() => void startRecording()}
                  className="flex size-11 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 outline-none transition-all hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-90 dark:bg-zinc-800 dark:text-zinc-400 dark:hover:text-emerald-400"
                >
                  <Mic className="size-5" aria-hidden />
                </button>
              ) : (
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
              )}
            </>
          )}
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

      {/* photo lightbox */}
      <AnimatePresence>
        {lightboxSrc ? (
          <motion.div
            key="lightbox"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.18 }}
            role="dialog"
            aria-label="Photo viewer"
            className="absolute inset-0 z-50 flex items-center justify-center bg-black/85 backdrop-blur-sm"
            onClick={() => setLightboxSrc(null)}
          >
            <motion.img
              src={lightboxSrc}
              alt="Shared photo enlarged"
              initial={{ scale: 0.88, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.94, opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 28 }}
              className="max-h-[74%] max-w-[92%] rounded-2xl shadow-2xl"
              onClick={(e) => e.stopPropagation()}
            />
            <button
              type="button"
              aria-label="Close photo viewer"
              onClick={() => setLightboxSrc(null)}
              className="absolute right-3 top-3 rounded-full bg-white/10 p-2 text-white outline-none transition-colors hover:bg-white/20"
            >
              <X className="size-5" aria-hidden />
            </button>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* who-reacted sheet */}
      <Drawer open={reactionInfo !== null} onOpenChange={(open) => !open && setReactionInfo(null)}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          {reactionInfo ? (
            <div className="pb-2">
              <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
                <span className="text-lg leading-none">{reactionInfo.emoji}</span>
                {(() => {
                  const group = reactionInfo.message.reactions.find((g) => g.emoji === reactionInfo.emoji)
                  const n = group?.count ?? 0
                  return n === 1 ? '1 reaction' : `${n} reactions`
                })()}
              </p>
              <ul className="pulse-scroll max-h-56 overflow-y-auto py-1">
                {(reactionInfo.message.reactions
                  .find((g) => g.emoji === reactionInfo.emoji)
                  ?.userIds.map((userId) => ({
                    id: userId,
                    member: detailData?.members.find((m) => m.id === userId),
                  })) ?? [])
                  .map(({ id, member }) => (
                    <li key={id} className="flex items-center gap-3 rounded-xl px-2 py-2">
                      <UserAvatar
                        name={member?.name ?? 'Unknown'}
                        color={member?.color ?? 'emerald'}
                        size={34}
                      />
                      <span className="flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                        {member?.name ?? 'Unknown'}
                        {id === me.id ? <span className="ml-1 text-xs text-zinc-400">(you)</span> : null}
                      </span>
                    </li>
                  ))}
              </ul>
              <button
                type="button"
                disabled={toggleReaction.isPending}
                onClick={() => {
                  handleToggleReaction(reactionInfo.message.id, reactionInfo.emoji)
                  setReactionInfo(null)
                }}
                className="mt-1 flex h-11 w-full items-center justify-center gap-2 rounded-2xl bg-emerald-500 text-sm font-bold text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-60"
              >
                <span className="text-base leading-none">{reactionInfo.emoji}</span>
                {reactionInfo.message.reactions
                  .find((g) => g.emoji === reactionInfo.emoji)
                  ?.userIds.includes(me.id)
                  ? 'Remove your reaction'
                  : `React ${reactionInfo.emoji}`}
              </button>
            </div>
          ) : null}
        </DrawerContent>
      </Drawer>

      {/* info dialog */}
      <InfoDialog
        open={infoOpen}
        onOpenChange={setInfoOpen}
        detail={detailData ?? null}
        me={me}
        onlineIds={realtime.onlineIds}
        renamePending={renameGroup.isPending}
        onRename={(name) => renameGroup.mutate(name)}
        addMembersPending={addMembers.isPending}
        onAddMembers={(userIds) => addMembers.mutate(userIds)}
        leavePending={leaveGroup.isPending}
        onLeave={() => leaveGroup.mutate()}
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

/** Deterministic decorative waveform bars derived from the message id. */
function voiceBars(seed: string, count = 26): number[] {
  let h = hashString(seed)
  const bars: number[] = []
  for (let i = 0; i < count; i += 1) {
    h = (h * 1103515245 + 12345) % 2147483648
    const v = Math.abs(h) / 2147483648
    bars.push(Math.round(28 + v * 72))
  }
  return bars
}

/** Voice-note bubble: play/pause + pseudo waveform + duration + progress. */
function VoiceBubble({
  src,
  durationMs,
  mine,
  seed,
}: {
  src: string
  durationMs: number | null
  mine: boolean
  seed: string
}) {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  const bars = useMemo(() => voiceBars(seed), [seed])

  const toggle = (event: React.SyntheticEvent) => {
    event.stopPropagation()
    const audio = audioRef.current
    if (!audio) return
    if (playing) {
      audio.pause()
    } else {
      void audio.play().catch(() => {
        toast.error('Could not play this voice note')
      })
    }
  }

  return (
    <div className="flex min-w-[196px] items-center gap-2.5 py-0.5">
      <button
        type="button"
        aria-label={playing ? 'Pause voice note' : 'Play voice note'}
        onClick={toggle}
        className={cn(
          'flex size-9 shrink-0 items-center justify-center rounded-full outline-none transition-transform active:scale-90',
          mine ? 'bg-white/20 text-white hover:bg-white/30' : 'bg-emerald-500 text-white hover:bg-emerald-500/90',
        )}
      >
        {playing ? <Pause className="size-4" aria-hidden /> : <Play className="size-4 translate-x-[1px]" aria-hidden />}
      </button>
      <div className="flex h-7 min-w-0 flex-1 items-center gap-[2.5px]" aria-hidden>
        {bars.map((height, i) => {
            const played = i / bars.length <= progress
            return (
              <span
                key={i}
                style={{ height: `${height}%` }}
                className={cn(
                  'w-[3px] shrink-0 rounded-full transition-colors',
                  played
                    ? mine
                      ? 'bg-white'
                      : 'bg-emerald-500'
                    : mine
                      ? 'bg-white/35'
                      : 'bg-zinc-300 dark:bg-zinc-600',
                )}
              />
            )
          })}
      </div>
      <span
        className={cn(
          'shrink-0 text-[10px] font-semibold tabular-nums',
          mine ? 'text-white/85' : 'text-zinc-400 dark:text-zinc-500',
        )}
      >
        {durationMs !== null ? formatVoicems(durationMs) : '--:--'}
      </span>
      <audio
        ref={audioRef}
        src={src}
        preload="none"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false)
          setProgress(0)
        }}
        onTimeUpdate={() => {
          const audio = audioRef.current
          if (!audio || !Number.isFinite(audio.duration) || audio.duration <= 0) return
          setProgress(Math.min(1, audio.currentTime / audio.duration))
        }}
        className="hidden"
      />
    </div>
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
  onReply: (message: ChatMessage) => void
  /** long-press a chip → who-reacted sheet */
  onReactionInfo: (message: ChatMessage, emoji: string) => void
  onOpenImage: (src: string) => void
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
  onReply,
  onReactionInfo,
  onOpenImage,
}: MessageRowProps) {
  const deleted = message.deletedAt !== null
  const pending = message.id.startsWith('temp-')
  const createdMs = Date.parse(message.createdAt)
  const isRead = !Number.isNaN(createdMs) && createdMs <= readMs
  const interactive = !deleted && !pending
  const jumbo = !deleted && !message.imagePath && !message.audioPath && isJumboEmoji(message.content)
  const hasReactions = message.reactions.length > 0
  const isImage = !deleted && message.imagePath !== null
  const isVoice = !deleted && !isImage && message.audioPath !== null
  const dragMovedRef = useRef(false)
  const chipPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const chipFiredRef = useRef(false)

  const beginReply = () => {
    haptic(12)
    onReply(message)
  }

  const openReactionInfo = (emoji: string) => {
    haptic(10)
    onReactionInfo(message, emoji)
  }

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

        <div className="relative flex w-full">
          {!mine ? (
            <motion.span
              aria-hidden
              initial={false}
              className="absolute left-0 top-1/2 -translate-y-1/2 text-emerald-500 opacity-0"
              style={{ pointerEvents: 'none' }}
            >
              <Reply className="size-4" />
            </motion.span>
          ) : (
            <motion.span
              aria-hidden
              initial={false}
              className="absolute right-0 top-1/2 -translate-y-1/2 text-emerald-500 opacity-0"
              style={{ pointerEvents: 'none' }}
            >
              <Reply className="size-4" />
            </motion.span>
          )}
          <motion.div
          drag="x"
          dragConstraints={{ left: 0, right: 0 }}
          dragElastic={0.24}
          dragSnapToOrigin
          onDragStart={() => {
            dragMovedRef.current = false
            onEndLongPress()
          }}
          onDragEnd={(_e, info) => {
            const toward = mine ? -info.offset.x : info.offset.x
            if (toward > 52) {
              dragMovedRef.current = true
              beginReply()
            }
          }}
          onClick={() => {
            if (dragMovedRef.current) {
              dragMovedRef.current = false
              return
            }
            if (interactive && !isImage) onPress(message)
          }}
          onPointerDown={() => interactive && onStartLongPress(message)}
          onPointerUp={onEndLongPress}
          onPointerLeave={onEndLongPress}
          onDoubleClick={() => {
            if (interactive) {
              onToggleReaction(message.id, '❤️')
            }
          }}
          role={interactive && !isImage ? 'button' : undefined}
          tabIndex={interactive && !isImage ? 0 : undefined}
          onKeyDown={(event) => {
            if (interactive && !isImage && event.key === 'Enter') onPress(message)
          }}
          className={cn(
            'relative select-none',
            jumbo
              ? 'px-1 py-0.5'
              : isImage
                ? 'rounded-2xl p-1 shadow-sm'
                : isVoice
                  ? 'rounded-2xl px-2.5 py-2 shadow-sm'
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
              {isImage ? (
                <button
                  type="button"
                  aria-label="Open photo"
                  onClick={(e) => {
                    e.stopPropagation()
                    if (message.imagePath) {
                      onOpenImage(`/api/uploads/${encodeURIComponent(message.imagePath)}`)
                    }
                  }}
                  className={cn(
                    'block overflow-hidden rounded-xl outline-none',
                    mine ? '' : '',
                  )}
                >
                  <img
                    src={`/api/uploads/${encodeURIComponent(message.imagePath as string)}`}
                    alt="Shared photo"
                    loading="lazy"
                    className={cn(
                      'block max-h-[300px] w-auto max-w-full rounded-xl object-cover transition-transform active:scale-[0.985]',
                      pending && 'opacity-80',
                    )}
                  />
                </button>
              ) : isVoice && message.audioPath ? (
                <VoiceBubble
                  src={`/api/uploads/${encodeURIComponent(message.audioPath)}`}
                  durationMs={message.durationMs}
                  mine={mine}
                  seed={message.id}
                />
              ) : jumbo ? (
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
        </div>

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
                  aria-label={`${group.emoji} ${group.count} — tap to toggle, hold for details`}
                  onClick={() => {
                    if (!chipFiredRef.current) onToggleReaction(message.id, group.emoji)
                    chipFiredRef.current = false
                  }}
                  onPointerDown={() => {
                    if (chipPressRef.current !== null) clearTimeout(chipPressRef.current)
                    chipFiredRef.current = false
                    chipPressRef.current = setTimeout(() => {
                      chipFiredRef.current = true
                      chipPressRef.current = null
                      openReactionInfo(group.emoji)
                    }, 380)
                  }}
                  onPointerUp={() => {
                    if (chipPressRef.current !== null) {
                      clearTimeout(chipPressRef.current)
                      chipPressRef.current = null
                    }
                  }}
                  onPointerLeave={() => {
                    if (chipPressRef.current !== null) {
                      clearTimeout(chipPressRef.current)
                      chipPressRef.current = null
                    }
                  }}
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
    prev.onToggleReaction === next.onToggleReaction &&
    prev.onReply === next.onReply &&
    prev.onReactionInfo === next.onReactionInfo &&
    prev.onOpenImage === next.onOpenImage
  )
}

function InfoDialog({
  open,
  onOpenChange,
  detail,
  me,
  onlineIds,
  renamePending,
  onRename,
  addMembersPending,
  onAddMembers,
  leavePending,
  onLeave,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: ConversationDetail | null
  me: AppUser
  onlineIds: ReadonlySet<string>
  renamePending: boolean
  onRename: (name: string) => void
  addMembersPending: boolean
  onAddMembers: (userIds: string[]) => void
  leavePending: boolean
  onLeave: () => void
}) {
  // group-management local state (all resets happen in event handlers)
  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickedIds, setPickedIds] = useState<string[]>([])
  const [confirmingLeave, setConfirmingLeave] = useState(false)

  const usersQuery = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<{ users: AppUser[] }>('/api/users')
      return res.users
    },
    enabled: open && pickerOpen,
    staleTime: 10_000,
  })

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

  const memberIds = new Set(detail.members.map((m) => m.id))
  const addableUsers = (usersQuery.data ?? []).filter((u) => !memberIds.has(u.id))

  const closeDialog = () => {
    onOpenChange(false)
    setEditingName(false)
    setPickerOpen(false)
    setPickedIds([])
    setConfirmingLeave(false)
  }

  return (
    <>
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : closeDialog())}>
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
            <div className="min-w-0 flex-1 text-left">
              {detail.isGroup && editingName ? (
                <div className="flex items-center gap-1.5">
                  <input
                    autoFocus
                    value={nameDraft}
                    maxLength={48}
                    aria-label="Group name"
                    onChange={(e) => setNameDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && nameDraft.trim().length > 0 && !renamePending) {
                        onRename(nameDraft.trim())
                        setEditingName(false)
                      }
                      if (e.key === 'Escape') setEditingName(false)
                    }}
                    className="h-8 min-w-0 flex-1 rounded-lg border border-emerald-300 bg-white px-2 text-sm font-semibold outline-none focus:border-emerald-500 dark:border-emerald-500/50 dark:bg-zinc-800 dark:text-zinc-100"
                  />
                  <button
                    type="button"
                    aria-label="Save group name"
                    disabled={nameDraft.trim().length === 0 || renamePending}
                    onClick={() => {
                      onRename(nameDraft.trim())
                      setEditingName(false)
                    }}
                    className="rounded-lg bg-emerald-500 p-1.5 text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-90 disabled:opacity-50"
                  >
                    <Check className="size-3.5" aria-hidden />
                  </button>
                  <button
                    type="button"
                    aria-label="Cancel renaming"
                    onClick={() => setEditingName(false)}
                    className="rounded-lg p-1.5 text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800"
                  >
                    <X className="size-3.5" aria-hidden />
                  </button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  <DialogTitle className="truncate text-base font-bold tracking-tight">{title}</DialogTitle>
                  {detail.isGroup && !pickerOpen ? (
                    <button
                      type="button"
                      aria-label="Rename group"
                      onClick={() => {
                        setNameDraft(detail.name?.trim() ?? '')
                        setEditingName(true)
                      }}
                      className="rounded-md p-1 text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 active:scale-90 dark:hover:bg-zinc-800 dark:hover:text-emerald-400"
                    >
                      <Pencil className="size-3.5" aria-hidden />
                    </button>
                  ) : null}
                </div>
              )}
              <DialogDescription className="text-xs">
                {detail.isGroup ? `${detail.members.length} members` : 'Direct conversation'}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        {detail.isGroup && pickerOpen ? (
          <div className="space-y-2">
            <p className="px-0.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
              Tap people to add them to {title}.
            </p>
            <ul className="pulse-scroll max-h-64 space-y-1 overflow-y-auto pr-1">
              {usersQuery.isPending ? (
                <li className="flex justify-center py-6">
                  <LoaderCircle className="size-5 animate-spin text-zinc-400" aria-hidden />
                </li>
              ) : addableUsers.length === 0 ? (
                <li className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                  Everyone on Pulse is already here.
                </li>
              ) : (
                addableUsers.map((user) => {
                  const picked = pickedIds.includes(user.id)
                  return (
                    <li key={user.id}>
                      <button
                        type="button"
                        role="checkbox"
                        aria-checked={picked}
                        onClick={() =>
                          setPickedIds((prev) =>
                            prev.includes(user.id)
                              ? prev.filter((id) => id !== user.id)
                              : [...prev, user.id],
                          )
                        }
                        className={cn(
                          'flex w-full items-center gap-3 rounded-xl p-2.5 text-left outline-none transition-colors',
                          picked
                            ? 'bg-emerald-500/10 ring-1 ring-emerald-400/60'
                            : 'bg-zinc-50 hover:bg-zinc-100 dark:bg-zinc-800/60 dark:hover:bg-zinc-800',
                        )}
                      >
                        <UserAvatar name={user.name} color={user.color} size={38} />
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                          {user.name}
                        </span>
                        <span
                          className={cn(
                            'flex size-5 shrink-0 items-center justify-center rounded-full border-2 transition-colors',
                            picked
                              ? 'border-emerald-500 bg-emerald-500 text-white'
                              : 'border-zinc-300 dark:border-zinc-600',
                          )}
                          aria-hidden
                        >
                          {picked ? <Check className="size-3" /> : null}
                        </span>
                      </button>
                    </li>
                  )
                })
              )}
            </ul>
            <div className="flex gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setPickerOpen(false)
                  setPickedIds([])
                }}
                className="h-10 flex-1 rounded-xl text-sm font-medium"
              >
                Cancel
              </Button>
              <Button
                disabled={pickedIds.length === 0 || addMembersPending}
                onClick={() => {
                  onAddMembers(pickedIds)
                  setPickerOpen(false)
                  setPickedIds([])
                }}
                className="h-10 flex-1 gap-1.5 rounded-xl bg-emerald-500 text-sm font-semibold text-white hover:bg-emerald-500/90"
              >
                {addMembersPending ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <UserPlus className="size-4" aria-hidden />
                )}
                Add {pickedIds.length > 0 ? pickedIds.length : ''}
              </Button>
            </div>
          </div>
        ) : (
          <>
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
            {detail.isGroup ? (
              <div className="flex flex-col gap-1.5">
                <Button
                  variant="outline"
                  onClick={() => {
                    setPickedIds([])
                    setPickerOpen(true)
                  }}
                  className="h-10 justify-start gap-2 rounded-xl border-emerald-500/40 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 dark:text-emerald-400"
                >
                  <UserPlus className="size-4" aria-hidden />
                  Add members
                </Button>
                <Button
                  variant="outline"
                  disabled={leavePending}
                  onClick={() => setConfirmingLeave(true)}
                  className="h-10 justify-start gap-2 rounded-xl border-destructive/40 text-sm font-medium text-destructive hover:bg-destructive/10 hover:text-destructive"
                >
                  <LogOut className="size-4" aria-hidden />
                  Leave group
                </Button>
              </div>
            ) : null}
          </>
        )}
      </DialogContent>
    </Dialog>

    <AlertDialog open={confirmingLeave} onOpenChange={setConfirmingLeave}>
      <AlertDialogContent className="max-w-[320px] rounded-2xl bg-white dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
        <AlertDialogHeader>
          <AlertDialogTitle className="tracking-tight">Leave “{title}”?</AlertDialogTitle>
          <AlertDialogDescription className="text-[13px] leading-relaxed">
            You won&apos;t receive new messages from this group. You can always be added back later.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className="gap-2">
          <AlertDialogCancel className="rounded-xl">Stay</AlertDialogCancel>
          <AlertDialogAction
            disabled={leavePending}
            onClick={() => {
              setConfirmingLeave(false)
              onLeave()
            }}
            className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
          >
            Leave group
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    </>
  )
}

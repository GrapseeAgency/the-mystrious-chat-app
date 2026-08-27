// ─────────────────────────────────────────────────────────────
// Pulse Chat — socket.io realtime layer.
// Connects per logged-in user, maintains presence + typing state,
// streams incoming events straight into the TanStack Query cache.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { io, type Socket } from 'socket.io-client'
import { useQueryClient } from '@tanstack/react-query'
import type {
  ChatMessage,
  ConversationDetail,
  ConversationSummary,
  PresenceSnapshot,
  ReadEvent,
  SocketMessageEvent,
  TypingEvent,
} from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import { haptic, playIncomingPing, primeSound } from '@/lib/pulse-settings'
import {
  PulseRealtimeContext,
  type PulseRealtimeValue,
  type TypingEntry,
  type TypingSignalOptions,
} from '@/hooks/use-pulse-socket'

interface TypingTrackerState {
  userName: string
  expires: number
}

interface ConversationTypingManager {
  lastEmit: number
  active: boolean
  idleTimer: ReturnType<typeof setTimeout> | null
}

const TYPING_EMIT_INTERVAL_MS = 1500
const TYPING_IDLE_STOP_MS = 1200
const TYPING_REMOTE_TTL_MS = 4000
const READ_DEBOUNCE_MS = 600

// ── payload validation (relayed from our own backend, light checks) ──

function asPresenceSnapshot(raw: unknown): PresenceSnapshot | null {
  if (raw === null || typeof raw !== 'object') return null
  const ids = (raw as Partial<PresenceSnapshot>).onlineUserIds
  if (!Array.isArray(ids)) return null
  return { onlineUserIds: ids.filter((v): v is string => typeof v === 'string') }
}

function asTypingEvent(raw: unknown): TypingEvent | null {
  if (raw === null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.conversationId !== 'string' || typeof r.userId !== 'string') return null
  return {
    conversationId: r.conversationId,
    userId: r.userId,
    userName: typeof r.userName === 'string' ? r.userName : '',
    isTyping: r.isTyping === true,
  }
}

function asMessageEvent(raw: unknown): SocketMessageEvent | null {
  if (raw === null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const m = r.message
  if (m === null || typeof m !== 'object') return null
  const msg = m as Record<string, unknown>
  const sender = (msg.sender ?? null) as Record<string, unknown> | null
  if (
    typeof msg.id !== 'string' ||
    typeof msg.conversationId !== 'string' ||
    typeof msg.senderId !== 'string' ||
    typeof msg.content !== 'string' ||
    typeof msg.createdAt !== 'string'
  ) {
    return null
  }
  const deletedAt = typeof msg.deletedAt === 'string' ? msg.deletedAt : null
  const reactions = Array.isArray(msg.reactions)
    ? msg.reactions.flatMap((entry) => {
        const r = entry as Record<string, unknown>
        if (typeof r.emoji !== 'string' || !Array.isArray(r.userIds)) return []
        const userIds = r.userIds.filter((u): u is string => typeof u === 'string')
        return [{ emoji: r.emoji, userIds, count: userIds.length }]
      })
    : []
  const replyRaw = (msg.replyTo ?? null) as Record<string, unknown> | null
  const replyTo =
    replyRaw && typeof replyRaw.id === 'string' && typeof replyRaw.content === 'string'
      ? {
          id: replyRaw.id,
          content: replyRaw.content,
          senderName: typeof replyRaw.senderName === 'string' ? replyRaw.senderName : '',
          deleted: replyRaw.deleted === true,
        }
      : null
  const imagePath = typeof msg.imagePath === 'string' ? msg.imagePath : null
  const audioPath = typeof msg.audioPath === 'string' ? msg.audioPath : null
  const durationMs = typeof msg.durationMs === 'number' && Number.isFinite(msg.durationMs) ? msg.durationMs : null
  return {
    type:
      r.type === 'message:deleted'
        ? 'message:deleted'
        : r.type === 'message:react'
          ? 'message:react'
          : 'message:new',
    message: {
      id: msg.id,
      conversationId: msg.conversationId,
      senderId: msg.senderId,
      content: msg.content,
      deletedAt,
      createdAt: msg.createdAt,
      sender:
        sender && typeof sender.id === 'string' && typeof sender.name === 'string'
          ? {
              id: sender.id,
              name: sender.name,
              color: typeof sender.color === 'string' ? sender.color : 'emerald',
            }
          : { id: msg.senderId, name: 'Unknown', color: 'emerald' },
      reactions,
      replyTo,
      imagePath,
      audioPath,
      durationMs,
    },
    recipientIds: [],
    conversationId: msg.conversationId,
  }
}

function asReadEvent(raw: unknown): ReadEvent | null {
  if (raw === null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (
    typeof r.conversationId !== 'string' ||
    typeof r.userId !== 'string' ||
    typeof r.lastReadAt !== 'string'
  ) {
    return null
  }
  return { conversationId: r.conversationId, userId: r.userId, lastReadAt: r.lastReadAt }
}

export function PulseRealtimeProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const user = usePulseSession((s) => s.user)
  const myId = user?.id ?? null

  const [onlineIds, setOnlineIds] = useState<ReadonlySet<string>>(new Set())
  const [isConnected, setIsConnected] = useState(false)
  const [typingMap, setTypingMap] = useState<Record<string, Record<string, TypingTrackerState>>>({})

  const socketRef = useRef<Socket | null>(null)
  const activeConvRef = useRef<string | null>(null)
  const typingManagersRef = useRef<Map<string, ConversationTypingManager>>(new Map())
  const readTimersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())

  // ── helpers ────────────────────────────────────────────────

  const setActiveConversation = useCallback((conversationId: string | null) => {
    activeConvRef.current = conversationId
  }, [])

  const patchReadWatermark = useCallback(
    (conversationId: string, userId: string, lastReadAt: string) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old
          ? {
              ...old,
              members: old.members.map((m) => (m.id === userId ? { ...m, lastReadAt } : m)),
            }
          : old,
      )
      queryClient.setQueryData<ConversationSummary[]>(
        ['conversations', myId],
        (old) =>
          old?.map((c) =>
            c.id === conversationId
              ? {
                  ...c,
                  unreadCount: userId === myId ? 0 : c.unreadCount,
                  members: c.members.map((m) =>
                    m.id === userId ? { ...m, lastReadAt } : m,
                  ),
                }
              : c,
          ) ?? old,
      )
    },
    [queryClient, myId],
  )

  /** Debounced "I read this conversation" POST → optimistic local patch. */
  const scheduleRead = useCallback(
    (conversationId: string) => {
      if (!myId) return
      const pending = readTimersRef.current.get(conversationId)
      if (pending) clearTimeout(pending)
      const timer = setTimeout(async () => {
        readTimersRef.current.delete(conversationId)
        try {
          await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/read`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: myId }),
          })
          patchReadWatermark(conversationId, myId, new Date().toISOString())
        } catch {
          // offline — the next event or window focus retries naturally
        }
      }, READ_DEBOUNCE_MS)
      readTimersRef.current.set(conversationId, timer)
    },
    [myId, patchReadWatermark],
  )

  const mergeIncomingMessage = useCallback(
    (message: ChatMessage) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', message.conversationId], (old) => {
        if (!old || old.length === 0) return [message]
        if (old.some((m) => m.id === message.id)) return old
        // drop any optimistic temps this real message supersedes
        const cleaned = old.filter(
          (m) =>
            !(m.id.startsWith('temp-') &&
              m.senderId === message.senderId &&
              m.content === message.content),
        )
        return [...cleaned, message]
      })
      queryClient.invalidateQueries({ queryKey: ['conversations', myId] })
    },
    [queryClient, myId],
  )

  // ── typing signals ─────────────────────────────────────────

  const emitTyping = useCallback(
    (
      conversationId: string,
      options: { viewerId: string; userName?: string; recipients: string[]; isTyping: boolean },
    ) => {
      const sock = socketRef.current
      if (!sock || options.recipients.length === 0) return
      const payload: TypingEvent = {
        conversationId,
        userId: options.viewerId,
        userName: options.userName ?? '',
        isTyping: options.isTyping,
      }
      sock.emit('typing', { recipients: options.recipients, ...payload })
    },
    [],
  )

  const signalTyping = useCallback(
    (conversationId: string, options: TypingSignalOptions) => {
      const manager =
        typingManagersRef.current.get(conversationId) ??
        ({ lastEmit: 0, active: false, idleTimer: null } satisfies ConversationTypingManager)
      typingManagersRef.current.set(conversationId, manager)

      const now = Date.now()
      if (!manager.active || now - manager.lastEmit > TYPING_EMIT_INTERVAL_MS) {
        manager.active = true
        manager.lastEmit = now
        emitTyping(conversationId, { ...options, isTyping: true })
      }
      if (manager.idleTimer) clearTimeout(manager.idleTimer)
      manager.idleTimer = setTimeout(() => {
        manager.active = false
        emitTyping(conversationId, {
          viewerId: options.viewerId,
          recipients: options.recipients,
          isTyping: false,
        })
      }, TYPING_IDLE_STOP_MS)
    },
    [emitTyping],
  )

  const cancelTyping = useCallback(
    (conversationId: string, options: Omit<TypingSignalOptions, 'userName'>) => {
      const manager = typingManagersRef.current.get(conversationId)
      if (manager?.idleTimer) clearTimeout(manager.idleTimer)
      if (manager && manager.active) {
        manager.active = false
        emitTyping(conversationId, { ...options, isTyping: false })
      }
    },
    [emitTyping],
  )

  // ── expired typer sweep ────────────────────────────────────
  useEffect(() => {
    const sweep = () => {
      setTypingMap((prev) => {
        const now = Date.now()
        let changed = false
        const next: Record<string, Record<string, TypingTrackerState>> = {}
        for (const [convId, users] of Object.entries(prev)) {
          for (const [userId, state] of Object.entries(users)) {
            if (state.expires > now) {
              next[convId] = next[convId] ?? {}
              next[convId][userId] = state
            } else {
              changed = true
            }
          }
        }
        return changed ? next : prev
      })
    }
    const interval = setInterval(sweep, 1000)
    return () => clearInterval(interval)
  }, [])

  // ── socket lifecycle ───────────────────────────────────────
  useEffect(() => {
    if (!myId) return undefined

    const sock = io('/?XTransformPort=3003', {
      path: '/',
      // polling first — guaranteed through the gateway; upgrades to ws when available
      transports: ['polling', 'websocket'],
      upgrade: true,
      reconnection: true,
      reconnectionDelay: 800,
      reconnectionDelayMax: 5_000,
    })
    socketRef.current = sock

    const applySnapshot = (raw: unknown) => {
      const snap = asPresenceSnapshot(raw)
      if (snap) setOnlineIds(new Set(snap.onlineUserIds))
    }

    const onConnect = () => {
      setIsConnected(true)
      sock.emit('join', { userId: myId })
    }

    const onDisconnect = () => {
      setIsConnected(false)
      setOnlineIds(new Set())
      setTypingMap({})
    }

    const onTyping = (raw: unknown) => {
      const ev = asTypingEvent(raw)
      if (!ev || ev.userId === myId) return
      setTypingMap((prev) => {
        const conv = { ...(prev[ev.conversationId] ?? {}) }
        if (ev.isTyping) {
          conv[ev.userId] = {
            userName: ev.userName.length > 0 ? ev.userName : 'Someone',
            expires: Date.now() + TYPING_REMOTE_TTL_MS,
          }
        } else {
          delete conv[ev.userId]
        }
        return { ...prev, [ev.conversationId]: conv }
      })
    }

    const onMessageNew = (raw: unknown) => {
      const evt = asMessageEvent(raw)
      if (!evt) return
      mergeIncomingMessage(evt.message)
      const viewing =
        activeConvRef.current === evt.message.conversationId &&
        typeof document !== 'undefined' &&
        document.visibilityState === 'visible'
      if (viewing) {
        scheduleRead(evt.message.conversationId)
      } else {
        // attention: gentle ping + buzz while backgrounded / elsewhere
        // (respected per-conversation mute watermark — synced by the 6s list poll)
        const summaries = queryClient.getQueryData<ConversationSummary[]>(['conversations', myId])
        const until = summaries?.find((c) => c.id === evt.message.conversationId)?.mutedUntil
        const muted = typeof until === 'string' && Date.parse(until) > Date.now()
        if (!muted) {
          playIncomingPing()
          haptic(20)
        }
      }
    }

    const onMessageReact = (raw: unknown) => {
      const evt = asMessageEvent(raw)
      if (!evt) return
      const fresh = evt.message
      queryClient.setQueryData<ChatMessage[]>(['messages', fresh.conversationId], (old) =>
        old ? old.map((m) => (m.id === fresh.id ? { ...m, reactions: fresh.reactions } : m)) : old,
      )
    }

    const onMessageDeleted = (raw: unknown) => {
      const evt = asMessageEvent(raw)
      if (!evt) return
      const tombstone = evt.message
      queryClient.setQueryData<ChatMessage[]>(['messages', tombstone.conversationId], (old) => {
        if (!old) return old
        const exists = old.some((m) => m.id === tombstone.id)
        return exists ? old.map((m) => (m.id === tombstone.id ? tombstone : m)) : [...old, tombstone]
      })
      queryClient.invalidateQueries({ queryKey: ['conversations', myId] })
    }

    const onMessageRead = (raw: unknown) => {
      const ev = asReadEvent(raw)
      if (!ev) return
      patchReadWatermark(ev.conversationId, ev.userId, ev.lastReadAt)
    }

    const onConversationUpdated = (raw: unknown) => {
      if (raw === null || typeof raw !== 'object') return
      const r = raw as Record<string, unknown>
      if (typeof r.conversationId !== 'string') return
      queryClient.invalidateQueries({ queryKey: ['conversation', r.conversationId] })
      queryClient.invalidateQueries({ queryKey: ['conversations', myId] })
    }

    sock.on('connect', onConnect)
    sock.on('disconnect', onDisconnect)
    sock.on('joined', applySnapshot)
    sock.on('presence:snapshot', applySnapshot)
    sock.on('typing', onTyping)
    sock.on('message:new', onMessageNew)
    sock.on('message:deleted', onMessageDeleted)
    sock.on('message:react', onMessageReact)
    sock.on('message:read', onMessageRead)
    sock.on('conversation:updated', onConversationUpdated)

    // Warm audio during the first gesture so later pings can play unmuted.
    const warm = () => primeSound()
    window.addEventListener('pointerdown', warm, { once: true, passive: true })

    return () => {
      window.removeEventListener('pointerdown', warm)
      sock.off()
      sock.disconnect()
      socketRef.current = null
      setIsConnected(false)
    }
  }, [myId, mergeIncomingMessage, patchReadWatermark, scheduleRead, queryClient])

  // ── context value ──────────────────────────────────────────

  const typersIn = useCallback(
    (conversationId: string, excludeUserId?: string): TypingEntry[] => {
      const users = typingMap[conversationId] ?? {}
      return Object.entries(users)
        .filter(([userId]) => userId !== excludeUserId)
        .map(([userId, state]) => ({ userId, userName: state.userName }))
    },
    [typingMap],
  )

  const value = useMemo<PulseRealtimeValue>(
    () => ({
      onlineIds,
      isConnected,
      typersIn,
      setActiveConversation,
      signalTyping,
      cancelTyping,
    }),
    [onlineIds, isConnected, typersIn, setActiveConversation, signalTyping, cancelTyping],
  )

  return <PulseRealtimeContext.Provider value={value}>{children}</PulseRealtimeContext.Provider>
}

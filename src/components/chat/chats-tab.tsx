// ─────────────────────────────────────────────────────────────
// Pulse Chat — Chats tab: conversation list, search, empty state.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback, useMemo, useState } from 'react'
import Image from 'next/image'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Search, SquarePen, X } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import {
  apiJson,
  conversationDisplayName,
  conversationPreview,
  conversationPreviewPrefix,
  formatListStamp,
  otherMemberOf,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { ThemeToggleButton } from '@/components/chat/theme-toggle'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

interface ConversationRowProps {
  id: string
  isGroup: boolean
  name: string
  time: string
  preview: string
  previewPrefix: string
  previewDeleted: boolean
  unreadCount: number
  // avatar inputs
  dmName: string | null
  dmColor: string
  groupTitle: string
  online: boolean
  onPress: (id: string) => void
}

const ConversationRow = memo(function ConversationRow({
  id,
  isGroup,
  name,
  time,
  preview,
  previewPrefix,
  previewDeleted,
  unreadCount,
  dmName,
  dmColor,
  groupTitle,
  online,
  onPress,
}: ConversationRowProps) {
  const hasUnread = unreadCount > 0
  return (
    <div className="px-2">
      <button
        type="button"
        onClick={() => onPress(id)}
        className="flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none transition-colors active:bg-zinc-100 dark:active:bg-zinc-800"
      >
        {isGroup ? (
          <GroupAvatar title={groupTitle} id={id} size={48} />
        ) : (
          <UserAvatar name={dmName ?? name} color={dmColor} size={48} showPresence online={online} />
        )}

        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span
              className={cn(
                'truncate text-[15px] tracking-tight',
                hasUnread
                  ? 'font-semibold text-zinc-900 dark:text-zinc-50'
                  : 'font-medium text-zinc-900 dark:text-zinc-100',
              )}
            >
              {name}
            </span>
            <span
              className={cn(
                'shrink-0 text-[11px]',
                hasUnread
                  ? 'font-semibold text-emerald-600 dark:text-emerald-400'
                  : 'text-zinc-400 dark:text-zinc-500',
              )}
            >
              {time}
            </span>
          </div>
          <div className="mt-0.5 flex items-center justify-between gap-2">
            <p
              className={cn(
                'truncate text-[13px]',
                hasUnread
                  ? 'font-medium text-zinc-600 dark:text-zinc-300'
                  : 'text-zinc-500 dark:text-zinc-400',
              )}
            >
              {previewPrefix ? <span className="text-zinc-400 dark:text-zinc-500">{previewPrefix}</span> : null}
              <span className={previewDeleted ? 'italic' : undefined}>{preview}</span>
            </p>
            {hasUnread ? (
              <motion.span
                initial={{ scale: 0 }}
                animate={{ scale: 1 }}
                transition={{ type: 'spring', stiffness: 520, damping: 20 }}
                className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-emerald-500 px-1.5 text-[10px] font-bold text-white"
              >
                {unreadCount > 99 ? '99+' : unreadCount}
              </motion.span>
            ) : null}
          </div>
        </div>
      </button>
      <div aria-hidden className="ml-[64px] h-px bg-zinc-100 dark:bg-zinc-800" />
    </div>
  )
})

function RowSkeleton() {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Skeleton className="size-12 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-3 w-2/3" />
      </div>
    </div>
  )
}

export function ChatsTab({
  me,
  onOpenConversation,
  onOpenContacts,
  onRequestNewChat,
  onGoProfile,
}: {
  me: AppUser
  onOpenConversation: (conversationId: string) => void
  onOpenContacts: () => void
  onRequestNewChat: () => void
  onGoProfile: () => void
}) {
  const realtime = usePulseRealtime()
  const [searching, setSearching] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')

  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 6_000,
  })

  const rows = useMemo(() => {
    return (conversations.data ?? []).map((conv) => {
      const previewInfo = conversationPreview(conv, me.id)
      const other = conv.isGroup ? null : otherMemberOf(conv, me.id)
      const groupName =
        conv.name?.trim() || conv.members.filter((m) => m.id !== me.id).map((m) => m.name).join(', ') || 'Group'
      const displayName = conversationDisplayName(conv, me.id)
      return {
        conv,
        props: {
          id: conv.id,
          isGroup: conv.isGroup,
          name: displayName,
          time: conv.lastMessage
            ? formatListStamp(conv.lastMessage.createdAt)
            : formatListStamp(conv.updatedAt),
          preview: previewInfo.text,
          previewPrefix: conversationPreviewPrefix(previewInfo, conv.isGroup),
          previewDeleted: previewInfo.deleted,
          unreadCount: conv.unreadCount,
          dmName: other?.name ?? null,
          dmColor: other?.color ?? 'emerald',
          groupTitle: groupName,
          online: !conv.isGroup && other !== null && realtime.onlineIds.has(other.id),
        },
      }
    })
  }, [conversations.data, me.id, realtime.onlineIds])

  const filteredRows = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return rows
    return rows.filter(({ conv, props }) => {
      if (props.name.toLowerCase().includes(q)) return true
      if (conv.lastMessage && !conv.lastMessage.deletedAt && conv.lastMessage.content.toLowerCase().includes(q)) return true
      return false
    })
  }, [rows, searchQuery])

  const handlePress = useCallback(
    (id: string) => onOpenConversation(id),
    [onOpenConversation],
  )

  const closeSearch = useCallback(() => {
    setSearching(false)
    setSearchQuery('')
  }, [])

  const data = conversations.data ?? []

  return (
    <div className="absolute inset-0 flex flex-col bg-white dark:bg-zinc-900">
      {/* header */}
      <header className="shrink-0 border-b border-zinc-200 pt-[max(0px,env(safe-area-inset-top))] dark:border-zinc-800">
        {searching ? (
          <div className="flex items-center gap-2 px-3 py-2.5">
            <Input
              autoFocus
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search chats and messages…"
              aria-label="Search conversations"
              className="h-10 flex-1 rounded-full border-zinc-200 bg-zinc-100 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
            />
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close search"
              onClick={closeSearch}
              className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
            >
              <X className="size-5" aria-hidden />
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-2 px-3 py-2.5">
            <button
              type="button"
              aria-label="Open my profile"
              onClick={onGoProfile}
              className="rounded-full outline-none transition-transform active:scale-95"
            >
              <UserAvatar name={me.name} color={me.color} size={36} />
            </button>
            <h1 className="mr-auto flex items-center gap-1.5 pl-1 text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
              Pulse
              <span aria-hidden className="inline-block size-1.5 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600" />
            </h1>
            <Button
              variant="ghost"
              size="icon"
              aria-label="New chat"
              onClick={onRequestNewChat}
              className="size-10 rounded-full text-zinc-500 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:hover:text-emerald-400"
            >
              <SquarePen className="size-[19px]" aria-hidden />
            </Button>
            <ThemeToggleButton />
          </div>
        )}
      </header>

      {/* list */}
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-4">
        {conversations.isPending ? (
          <div role="status" aria-label="Loading conversations" className="pt-2">
            <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
          </div>
        ) : searching ? (
          filteredRows.length > 0 ? (
            <div className="py-1">
              {filteredRows.map(({ props }) => (
                <ConversationRow key={props.id} {...props} onPress={handlePress} />
              ))}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center gap-2 px-8 pt-24 text-center">
              <Search className="size-8 text-zinc-300 dark:text-zinc-600" aria-hidden />
              <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">No matches</p>
              <p className="text-xs text-zinc-400 dark:text-zinc-500">
                Nothing here for “{searchQuery}”.
              </p>
            </div>
          )
        ) : data.length === 0 ? (
          <EmptyChats onSayHi={onOpenContacts} />
        ) : (
          <div className="py-1">
            {rows.map(({ props }) => (
              <ConversationRow key={props.id} {...props} onPress={handlePress} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function EmptyChats({ onSayHi }: { onSayHi: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 px-8 pt-16 text-center">
      <Image
        src="/empty-chats.png"
        alt="No conversations illustration"
        width={168}
        height={168}
        className="rounded-3xl shadow-md shadow-zinc-200/70 dark:shadow-none"
      />
      <div>
        <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
          No conversations yet
        </h2>
        <p className="mx-auto mt-1 max-w-[240px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
          Your next great chat is one tap away. Find someone and break the ice.
        </p>
      </div>
      <Button
        onClick={onSayHi}
        variant="outline"
        className="h-10 gap-1.5 rounded-full border-emerald-500/40 px-5 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 dark:text-emerald-400 dark:hover:text-emerald-400 active:scale-[0.98]"
      >
        Say hi to someone
        <ArrowRight className="size-4" aria-hidden />
      </Button>
    </div>
  )
}

// Pulse Chat - Chats tab: conversation list, search, empty state.
// R54-b artboard rebuild: the home surface speaks the reference ember
// language only - art-scene horizon glow, bare header (Chats + search /
// camera / kebab), circular story discs, single chip rail, flat rows with
// the signal-red art-badge. Every feature the old glass home had stays
// alive: the extra header icons and the list cards (Note to Self,
// Mentions, Channels, Archived, folders entry) relocated into the kebab
// menu; search opens from the header icon; row pin/archive live in the
// long-press sheet + multi-select bar. rows live in chats-row.tsx (room +
// archived pages still share it); #/chats/archived is a REAL hash
// sub-page (chats-archived-page.tsx). R34-a Telegram multi-select
// (long-press -> check rows -> floating Archive / Mute-8h / Mark-read
// bar) + #/calls sub-page entry preserved.
'use client'

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Image from 'next/image'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useStore } from 'zustand'
import { useTheme } from 'next-themes'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Archive,
  ArrowRight,
  AtSign,
  BellOff,
  Bookmark,
  BookUser,
  Camera,
  Check,
  CheckCheck,
  CircleDashed,
  Flame,
  FolderPlus,
  Hourglass,
  LoaderCircle,
  MoreVertical,
  Moon,
  NotebookPen,
  PencilLine,
  Phone,
  Pin,
  Plus,
  Radio,
  Search,
  Settings,
  Sun,
  Ticket,
  Users,
  X,
} from 'lucide-react'
import { FOLDER_ICON_GLYPHS, PulseCompose, PulseFolderPlus, PulseSearch } from '@/components/ui/icons'
import { folderIconId } from '@/lib/icon-ids'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, FolderSummary, SearchResultMessage } from '@/lib/types'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { useMounted } from '@/hooks/use-mounted'
import { gradientFor, 
  apiJson,
  conversationDisplayName,
  conversationPreview,
  conversationPreviewPrefix,
  formatListStamp,
  otherMemberOf,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import type { ChatsListFilter } from '@/lib/pulse-settings'
import { pulseSettingsStore } from '@/lib/pulse-settings'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useHashNav } from '@/lib/hash-router'
import { UserAvatar, GroupAvatar } from '@/components/chat/user-avatar'
import {
  StoriesSheet,
  storiesQueryKey,
} from '@/components/chat/stories-sheet'
import type { StoryGroup, StoriesResponse } from '@/components/chat/stories-sheet'
import { StoryComposerSheet } from '@/components/chat/story-composer-sheet'
import { FoldersSheet } from '@/components/chat/folders-sheet'
import { NewChatSheet } from '@/components/chat/new-chat-sheet'
import { JoinGroupSheet } from '@/components/chat/join-sheet'
import type { ConversationRowData } from '@/components/chat/chats-row'

import { RowSkeleton } from '@/components/chat/chats-skeleton'
import {
  ChatOptionsSheet,
  downloadTranscript,
  fetchFullHistory,
} from '@/components/chat/chats-actions'
import { ChatsArchivedPage } from '@/components/chat/chats-archived-page'
import { ChannelsPage } from '@/components/chat/channels-page'
import { CallsPage } from '@/components/chat/calls-page'
import {
  MentionsPage,
  fetchMentions,
  type MentionsResponse,
} from '@/components/chat/mentions-page'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

interface SearchResponse {
  messages: SearchResultMessage[]
  total: number
}

// R54-b artboard shared classes -------------------------------------------------

/** Bare header icon button: no circle chrome at rest, 44px touch target. */
const HEADER_ICON_CLS =
  'flex size-11 shrink-0 items-center justify-center rounded-full text-[var(--art-text)] outline-none transition-colors hover:bg-white/5 active:bg-white/10 focus-visible:ring-2 focus-visible:ring-[var(--art-accent)]/50'

/** Filter chip language from the artboard: active = lifted ink, inactive = dim. */
const ART_CHIP_CLS =
  'relative flex h-[30px] shrink-0 items-center rounded-full px-3.5 text-[13px] font-medium outline-none transition-colors'
const ART_CHIP_ACTIVE = 'bg-[var(--art-chip-active)] text-[var(--art-text)]'
const ART_CHIP_IDLE = 'bg-[var(--art-chip)] text-[var(--art-dim)] hover:text-[var(--art-text-soft)]'

/** Kebab menu (dark ember dropdown) shared classes. */
const KEBAB_CONTENT_CLS =
  'max-h-[min(70vh,560px)] min-w-[240px] overflow-y-auto overscroll-contain rounded-2xl border border-[var(--art-hairline)] bg-[#1c1610]/95 p-1.5 text-[var(--art-text)] shadow-2xl shadow-black/50 backdrop-blur-xl'
const KEBAB_ITEM_CLS =
  'gap-3 rounded-xl px-3 py-2.5 text-[13.5px] font-medium text-[var(--art-text)] outline-none transition-colors focus:bg-white/[0.07] focus:text-[var(--art-text)] data-[highlighted]:bg-white/[0.07] data-[highlighted]:text-[var(--art-text)]'
const KEBAB_LABEL_CLS =
  'px-3 pb-1 pt-2 text-[10px] font-bold uppercase tracking-[0.14em] text-[var(--art-faint)]'

/** Dim trailing count/hint inside a kebab row. */
function KebabTrailing({ children }: { children: React.ReactNode }) {
  return (
    <span className="ml-auto pl-3 text-[11px] font-semibold tabular-nums text-[var(--art-faint)]">
      {children}
    </span>
  )
}

/**
 * Snippet with the first match highlighted - clips a ≤64-char window
 * around the hit so long messages stay one tidy line.
 */
function SearchSnippet({ content, query }: { content: string; query: string }) {
  const lower = content.toLowerCase()
  const q = query.toLowerCase()
  const idx = q.length > 0 ? lower.indexOf(q) : -1
  let from = 0
  let clippedHead = false
  if (idx > 28) {
    from = idx - 24
    clippedHead = true
  }
  const end = Math.min(content.length, idx + q.length + 28)
  const clippedTail = end < content.length
  const body = content.slice(from, end)
  const localIdx = idx - from
  return (
    <p className="truncate text-[13px] text-[var(--art-dim)]">
      {clippedHead ? <span className="text-[var(--art-faint)]">…</span> : null}
      {idx >= 0 ? (
        <>
          {body.slice(0, localIdx)}
          <mark className="rounded bg-[var(--art-accent)]/20 px-0.5 font-semibold text-[var(--art-accent-2)]">
            {body.slice(localIdx, localIdx + q.length)}
          </mark>
          {body.slice(localIdx + q.length)}
        </>
      ) : (
        body
      )}
      {clippedTail ? <span className="text-[var(--art-faint)]">…</span> : null}
    </p>
  )
}

/** One server-side message hit - sender avatar, chat title, highlighted snippet. */
const SearchMessageRow = memo(function SearchMessageRow({
  hit,
  query,
  onPress,
}: {
  hit: SearchResultMessage
  query: string
  onPress: (hit: SearchResultMessage) => void
}) {
  const deleted = hit.deletedAt !== null
  // R41 - document hits: when the caption is empty or is not itself the match,
  // show "Document - <fileName>" so the row explains why it matched.
  const isFileHit = hit.filePath !== null
  const captionMatches = hit.content.length > 0 && hit.content.toLowerCase().includes(query.toLowerCase())
  const fileSnippet =
    hit.fileName !== null ? `Document - ${hit.fileName}` : hit.content.length > 0 ? hit.content : 'Document'
  return (
    <motion.button
      type="button"
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.16 }}
      whileTap={{ scale: 0.975 }}
      onClick={() => onPress(hit)}
      className="flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none transition-colors active:bg-white/5"
    >
      <span className="relative shrink-0">
        <UserAvatar name={hit.sender.name} color={hit.sender.color} size={40} />
        {hit.isGroup ? (
          <span className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full bg-[#1c1610] ring-2 ring-[var(--art-bg)]">
            <Users className="size-2.5 text-[var(--art-dim)]" aria-hidden />
          </span>
        ) : null}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline justify-between gap-2">
          <span className="min-w-0 truncate text-[13px] font-semibold text-[var(--art-text)]">
            {hit.conversationName}
          </span>
          <span className="shrink-0 text-[11px] text-[var(--art-faint)]">
            {formatListStamp(hit.createdAt)}
          </span>
        </span>
        {deleted ? (
          <p className="truncate text-[13px] italic text-[var(--art-faint)]">Deleted message</p>
        ) : hit.imagePath && hit.content.length === 0 ? (
          <p className="truncate text-[13px] text-[var(--art-dim)]">Photo</p>
        ) : isFileHit && !captionMatches ? (
          <SearchSnippet content={fileSnippet} query={query} />
        ) : (
          <SearchSnippet content={hit.content} query={query} />
        )}
      </span>
    </motion.button>
  )
})

/** Tiny uppercase section header with an ember count chip. */
function SearchSection({ label, count }: { label: string; count: number }) {
  return (
    <div className="flex items-center gap-2 px-3 pb-0.5 pt-3">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-[var(--art-faint)]">
        {label}
      </span>
      <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-[var(--art-accent)]/15 px-1.5 text-[10px] font-bold text-[var(--art-accent-2)]">
        {count > 99 ? '99+' : count}
      </span>
      <span aria-hidden className="h-px flex-1 bg-[var(--art-hairline)]" />
    </div>
  )
}

// 24h status stories

/** Artboard photo-card dimensions (rounded rect, not a disc). */
const STORY_CARD_W = 46
const STORY_CARD_H = 60

/**
 * One cell in the artboard stories row: a rounded-rect PHOTO CARD with a
 * corner count badge (unseen = amber ring, seen = hairline, "You" = dark
 * tile with a centered plus). Text stories fall back to their background
 * gradient, image stories show the real frame. Tap targets and data
 * unchanged from the R27 row.
 */
const StoryRingCell = memo(function StoryRingCell({
  name,
  color,
  ring,
  label,
  image,
  background,
  badge,
  onPress,
  index,
}: {
  name: string
  color: string
  ring: 'unseen' | 'seen' | 'none'
  label: string
  image: string | null
  background: string | null
  badge: number | null
  onPress: () => void
  index: number
}) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.button
      type="button"
      initial={reducedMotion ? false : 'hidden'}
      animate="shown"
      whileTap={reducedMotion ? undefined : 'tap'}
      variants={{
        hidden: {
          opacity: 0,
          y: 10,
          transition: { duration: 0.28, ease: ease.out, delay: stagger(index, 0.03, 10) },
        },
        shown: { opacity: 1, y: 0, scale: 1, transition: spring.bouncy },
        tap: { opacity: 1, y: 0, scale: 0.92, transition: spring.bouncy },
      }}
      onClick={onPress}
      aria-label={ring === 'unseen' ? `${label} - new status` : label}
      className="flex w-14 shrink-0 snap-start flex-col items-center gap-1 rounded-2xl pb-1 pt-0.5 outline-none"
    >
      <span
        className={cn(
          'relative block overflow-hidden rounded-[14px]',
          ring === 'unseen'
            ? 'ring-2 ring-[var(--art-accent)]/85'
            : ring === 'seen'
              ? 'ring-1 ring-[var(--art-hairline)]'
              : 'ring-1 ring-transparent',
        )}
        style={{ width: STORY_CARD_W, height: STORY_CARD_H }}
      >
        {ring !== 'none' ? (
          image ? (
            <img
              src={`/api/uploads/${encodeURIComponent(image)}`}
              alt=""
              aria-hidden
              loading="lazy"
              className="absolute inset-0 size-full object-cover"
            />
          ) : background ? (
            <span aria-hidden className={cn('absolute inset-0 bg-gradient-to-br', gradientFor(background))} />
          ) : (
            <span aria-hidden className="absolute inset-0 flex items-center justify-center bg-[#17110c]">
              <UserAvatar name={name} color={color} size={30} />
            </span>
          )
        ) : (
          <span
            aria-hidden
            className="absolute inset-0 flex items-center justify-center bg-white/[0.06]"
          >
            <Plus className="size-5 text-[var(--art-dim)]" strokeWidth={2.2} aria-hidden />
          </span>
        )}
        {badge !== null && badge > 0 ? (
          <span className="art-badge absolute right-1 top-1 flex h-[15px] min-w-[15px] items-center justify-center rounded-full px-1 text-[9px] font-bold leading-none shadow">
            {badge > 9 ? '9+' : badge}
          </span>
        ) : null}
      </span>
      <span className="w-full truncate text-center text-[10.5px] leading-tight text-[var(--art-dim)]">
        {label}
      </span>
    </motion.button>
  )
})

/**
 * Artboard conversation row (R54-b): flat on the ember scene - no glass
 * card, no divider, whitespace-only separation. 50px circular avatar,
 * name 15px semibold in --art-text, preview 13px in --art-dim, time 11px
 * in --art-faint top-right and the signal-red art-badge bottom-right.
 * Press opens the room; long-press enters multi-select; the hover/focus
 * dots (and the long-press sheet behind them) keep pin / archive / mute
 * / clear / export one tap away. Draft / typing / streak / presence
 * affordances from the old row all carry over in the ember palette.
 */
const ArtConversationRow = memo(function ArtConversationRow({
  data,
  selectMode = false,
  selected = false,
  entranceIndex,
  onPress,
  onLongPress,
  onToggleSelect,
  onOptions,
}: {
  data: ConversationRowData
  selectMode?: boolean
  selected?: boolean
  entranceIndex: number | null
  onPress: () => void
  onLongPress: () => void
  onToggleSelect?: () => void
  onOptions?: () => void
}) {
  const {
    id,
    isGroup,
    name,
    time,
    preview,
    previewPrefix,
    previewDeleted,
    draft,
    unreadCount,
    manualUnread = false,
    dmName,
    dmColor,
    groupTitle,
    online,
    pinned,
    muted,
    typing,
    streakCount = 0,
    streakAtRisk = null,
    streakLost = null,
    photo = null,
  } = data
  const reducedMotion = useReducedMotion()
  const hasUnread = unreadCount > 0 || manualUnread
  const entrance = entranceIndex !== null && !reducedMotion
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const longPressFiredRef = useRef(false)

  const clearLongPress = useCallback(() => {
    if (longPressRef.current !== null) {
      clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }, [])

  const startLongPress = useCallback(() => {
    if (selectMode) return // long-press is inert while multi-select owns the list
    clearLongPress()
    longPressFiredRef.current = false
    longPressRef.current = setTimeout(() => {
      longPressFiredRef.current = true
      longPressRef.current = null
      haptic(15)
      onLongPress()
    }, 450)
  }, [clearLongPress, onLongPress, selectMode])

  const handleClick = useCallback(() => {
    // release after a fired long-press must not also open the room
    if (longPressFiredRef.current) {
      longPressFiredRef.current = false
      return
    }
    if (selectMode) {
      haptic(8)
      onToggleSelect?.()
      return
    }
    onPress()
  }, [onPress, onToggleSelect, selectMode])

  return (
    <motion.div
      initial={entrance ? { opacity: 0, y: 14 } : false}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: 0.32,
        ease: ease.out,
        delay: entrance ? stagger(entranceIndex ?? 0, 0.028, 12) : 0,
      }}
      className="group relative px-1.5"
    >
      <button
        type="button"
        role={selectMode ? 'checkbox' : undefined}
        aria-checked={selectMode ? selected : undefined}
        onClick={handleClick}
        onPointerDown={startLongPress}
        onPointerUp={clearLongPress}
        onPointerLeave={clearLongPress}
        onContextMenu={(e) => e.preventDefault()}
        className="relative flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none transition-colors duration-150 hover:bg-white/5 focus-visible:bg-white/5 active:bg-white/[0.07]"
      >
        <span className="relative shrink-0">
          {isGroup ? (
            <span className="block size-[50px]">
              <GroupAvatar title={groupTitle} id={id} size={50} photo={photo} />
            </span>
          ) : (
            <span className="block size-[50px]">
              <UserAvatar name={dmName ?? name} color={dmColor} size={50} showPresence online={online} />
            </span>
          )}
          {selectMode ? (
            // R34-a Telegram-style check circle over the avatar
            <motion.span
              initial={reducedMotion ? false : { scale: 0, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={spring.bouncy}
              className="absolute inset-0 z-10 flex items-center justify-center"
            >
              <span
                aria-hidden
                className={cn(
                  'flex size-6 items-center justify-center rounded-full ring-2 backdrop-blur-sm transition-colors',
                  selected
                    ? 'bg-[var(--art-accent)] ring-white/70'
                    : 'bg-black/40 ring-white/50',
                )}
              >
                {selected ? <Check className="size-4 text-white" strokeWidth={3} aria-hidden /> : null}
              </span>
            </motion.span>
          ) : null}
        </span>

        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="flex min-w-0 items-center gap-1">
              <span className="truncate text-[15px] font-semibold leading-snug tracking-tight text-[var(--art-text)]">
                {name}
              </span>
            </span>
            <span className="flex shrink-0 items-center gap-1.5">
              {/* streak affordances (R31-a / R33-b / R37) in the ember palette */}
              {streakAtRisk ? (
                <span
                  aria-label={`${streakAtRisk.count}-day streak ends tonight - send a message to keep it`}
                  className="flex items-center gap-1 rounded-full bg-white/[0.06] px-1.5 py-0.5 text-[10px] font-bold text-[var(--art-dim)] ring-1 ring-[var(--art-hairline)]"
                >
                  <Hourglass className="size-3" aria-hidden />
                  ends tonight
                </span>
              ) : streakCount > 0 ? (
                <span
                  aria-label={`${streakCount}-day streak`}
                  className="flex items-center gap-0.5 rounded-full bg-white/[0.06] px-1.5 py-0.5 text-[10px] font-bold text-[var(--art-dim)] ring-1 ring-[var(--art-hairline)]"
                >
                  <Flame className="size-3" aria-hidden />
                  {streakCount}
                </span>
              ) : streakLost ? (
                <span
                  aria-label={`${streakLost.count}-day streak lost`}
                  className="flex items-center gap-1 rounded-full bg-white/[0.06] px-1.5 py-0.5 text-[10px] font-bold text-[var(--art-dim)] ring-1 ring-[var(--art-hairline)]"
                >
                  <Flame className="size-3" aria-hidden />
                  lost
                </span>
              ) : null}
              <motion.span
                key={time}
                initial={reducedMotion ? false : { opacity: 0, scale: 0.7 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={spring.bouncy}
                className={cn(
                  'shrink-0 text-[11px] tabular-nums',
                  hasUnread ? 'font-semibold text-[var(--art-text-soft)]' : 'text-[var(--art-faint)]',
                )}
              >
                {time}
              </motion.span>
            </span>
          </div>
          <div className="mt-0.5 flex items-center justify-between gap-2">
            {typing ? (
              <p className="flex min-w-0 items-center gap-1.5 text-[13px] font-medium italic text-[var(--art-accent-2)]">
                <span className="inline-flex items-center gap-0.5" aria-hidden>
                  {[0, 1, 2].map((i) => (
                    <motion.span
                      key={i}
                      animate={{ y: [0, -2.5, 0], opacity: [0.45, 1, 0.45] }}
                      transition={{ repeat: Infinity, duration: 0.9, delay: i * 0.15, ease: 'easeInOut' }}
                      className="size-[3.5px] rounded-full bg-[var(--art-accent)]"
                    />
                  ))}
                </span>
                typing…
              </p>
            ) : draft ? (
              <p className="flex min-w-0 items-center gap-1 truncate text-[13px]">
                <PencilLine className="size-3 shrink-0 text-[var(--art-accent)]" aria-hidden />
                <span className="shrink-0 font-semibold text-[var(--art-accent-2)]">Draft:</span>
                <span className="truncate italic text-[var(--art-dim)]">{draft}</span>
              </p>
            ) : (
              <p className={cn('truncate text-[13px] text-[var(--art-dim)]', previewDeleted && 'italic')}>
                {previewPrefix ? <span className="text-[var(--art-faint)]">{previewPrefix}</span> : null}
                <span>{preview}</span>
              </p>
            )}
            {pinned ? (
              <span aria-label="Pinned" className="mr-0.5 flex shrink-0 items-center">
                <Pin className="size-3 rotate-45 fill-[var(--art-faint)] text-[var(--art-faint)]" />
              </span>
            ) : null}
            {hasUnread && !muted ? (
              unreadCount > 0 ? (
                // R54-b: signal-red badge replaces the old amber pill
                <motion.span
                  key={unreadCount}
                  initial={reducedMotion ? false : { scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={spring.bouncy}
                  className="art-badge flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full px-1.5 text-[11px] font-bold"
                >
                  {unreadCount > 99 ? '99+' : unreadCount}
                </motion.span>
              ) : (
                <motion.span
                  key="manual-unread-dot"
                  initial={reducedMotion ? false : { scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={spring.bouncy}
                  aria-label="Marked as unread"
                  className="art-badge mx-[3px] flex size-2.5 shrink-0 rounded-full"
                >
                  <span className="sr-only">Marked as unread</span>
                </motion.span>
              )
            ) : muted ? (
              <motion.span
                key={unreadCount}
                initial={reducedMotion ? false : { scale: 0.6, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                transition={spring.bouncy}
                aria-label={hasUnread ? `Muted - ${unreadCount} unread` : 'Muted'}
                className={cn(
                  'flex h-[18px] shrink-0 items-center gap-1 rounded-full px-1 text-[10px] font-bold',
                  hasUnread ? 'bg-white/[0.08] text-[var(--art-text-soft)]' : 'text-[var(--art-faint)]',
                )}
              >
                <BellOff className="size-3" aria-hidden />
                {hasUnread ? <span>{unreadCount > 99 ? '99+' : unreadCount}</span> : null}
              </motion.span>
            ) : null}
          </div>
        </div>
      </button>
      {/* overflow affordance kept for accessibility (screen readers + keyboard);
          opens the same option sheet as the archived page rows; hidden while
          multi-select owns the list (the bar replaces it) */}
      {!selectMode ? (
        <button
          type="button"
          aria-label={`Options for ${name}`}
          onClick={(e) => {
            e.stopPropagation()
            ;(onOptions ?? onLongPress)()
          }}
          className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded-full bg-white/10 p-1.5 text-[var(--art-dim)] opacity-0 outline-none backdrop-blur transition-opacity hover:text-[var(--art-text)] focus-visible:opacity-100 group-hover:opacity-100"
        >
          <MoreVertical className="size-4" aria-hidden />
        </button>
      ) : null}
    </motion.div>
  )
})

export function ChatsTab({
  me,
  onOpenConversation,
  onOpenContacts,
  onRequestNewChat,
  onGoProfile,
}: {
  me: AppUser
  onOpenConversation: (conversationId: string, unreadAnchorMs: number | null, jumpMessageId?: string) => void
  onOpenContacts: () => void
  onRequestNewChat: () => void
  onGoProfile: () => void
}) {
  const realtime = usePulseRealtime()
  const typersIn = realtime.typersIn
  const onlineIds = realtime.onlineIds

  // #/chats/archived + #/chats/channels sub-pages - same internal hash
  // pattern the settings tree uses: open = push, back = pop to '/'.
  const { path, navigate, back } = useHashNav()
  const archivedPageOpen = path === '/chats/archived'
  const openArchivedPage = useCallback(() => {
    haptic(6)
    navigate('/chats/archived')
  }, [navigate])
  const channelsPageOpen = path === '/chats/channels'
  const openChannelsPage = useCallback(() => {
    haptic(6)
    navigate('/chats/channels')
  }, [navigate])
  // R34-a - #/calls: WhatsApp 'Calls' paradigm, same hash sub-page anatomy
  const callsPageOpen = path === '/calls'
  const openCallsPage = useCallback(() => {
    haptic(6)
    navigate('/calls')
  }, [navigate])
  // R35-b - #/mentions: Discord mobile 'Mentions' tab, same hash sub-page anatomy
  const mentionsPageOpen = path === '/mentions'
  const openMentionsPage = useCallback(() => {
    haptic(6)
    navigate('/mentions')
  }, [navigate])

  // Real mention count for the kebab entry - same query key the sub-page
  // uses, so the cache is warm the moment the page opens.
  const mentions = useQuery({
    queryKey: ['mentions', me.id],
    queryFn: () => fetchMentions(me.id),
    refetchInterval: 30_000,
    staleTime: 15_000,
  })
  const mentionCount = mentions.data?.items.length ?? 0

  const [searching, setSearching] = useState(false)
  const [searchFocused, setSearchFocused] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  /** debounced mirror of searchQuery feeding the server message search */
  const [deferredQuery, setDeferredQuery] = useState('')
  const deferredTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

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

  /**
   * Entrance stagger plays ONLY on the tab's first list render - the flag flips
   * right after the first commit that has rows, so refetches/edits never re-animate.
   * (setState is deferred off the effect body to keep the commit clean.)
   */
  const [entranceOn, setEntranceOn] = useState(true)
  useEffect(() => {
    if ((conversations.data ?? []).length > 0) {
      const t = setTimeout(() => setEntranceOn(false), 0)
      return () => clearTimeout(t)
    }
  })

  // live drafts → "Draft: …" previews in the list (zustand external store)
  const allDrafts = useStore(pulseDraftsStore, (s) => s.drafts)

  const rows = useMemo<Array<{ conv: ConversationSummary; props: ConversationRowData }>>(() => {
    // R24-a: Note-to-Self chats never render as a "DM with myself" row in
    // the regular list - the kebab menu opens them instead.
    return (conversations.data ?? []).filter((conv) => !conv.isSelf).map((conv) => {
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
          draft: allDrafts[conv.id] ?? conv.myDraft ?? null, // R45: server draft fills in cross-device
          unreadCount: conv.unreadCount,
          manualUnread: conv.myManualUnread, // R44: mark-as-unread dot
          dmName: other?.name ?? null,
          dmColor: other?.color ?? 'emerald',
          groupTitle: groupName,
          online: !conv.isGroup && other !== null && onlineIds.has(other.id),
          pinned: conv.pinnedAt !== null,
          muted: conv.mutedUntil !== null && Date.parse(conv.mutedUntil) > Date.now(),
          typing: typersIn(conv.id, me.id).length > 0,
          streakCount: conv.myStreak?.count ?? 0, // R31-a: live-streak chip
          // R33-b: at-risk nudge + channel/group photo straight from the summary
          streakAtRisk: conv.deadStreak ?? null,
          // R37: honest end-state chip (never beside a live/at-risk streak)
          streakLost: conv.lostStreak ?? null,
          photo: conv.photo ?? null,
          archived: conv.archivedAt !== null,
        },
      }
    })
  }, [conversations.data, me.id, onlineIds, typersIn, allDrafts])

  const filteredRows = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return rows
    return rows.filter(({ conv, props }) => {
      if (props.name.toLowerCase().includes(q)) return true
      if (props.draft?.toLowerCase().includes(q)) return true
      if (conv.lastMessage && !conv.lastMessage.deletedAt && conv.lastMessage.content.toLowerCase().includes(q)) return true
      return false
    })
  }, [rows, searchQuery])

  /** main-list rows exclude the viewer's archived chats */
  const activeRows = useMemo(() => rows.filter(({ conv }) => conv.archivedAt === null), [rows])
  const archivedRows = useMemo(() => rows.filter(({ conv }) => conv.archivedAt !== null), [rows])

  // Telegram-style folder filter (persisted preference)
  const listFilter = useStore(pulseSettingsStore, (s) => s.listFilter)
  const setListFilter = useStore(pulseSettingsStore, (s) => s.setListFilter)
  const unreadTotal = useMemo(
    () => activeRows.reduce((sum, { conv }) => sum + conv.unreadCount, 0),
    [activeRows],
  )
  const folderFiltered = useMemo(() => {
    if (listFilter === 'unread') return activeRows.filter(({ conv }) => conv.unreadCount > 0)
    if (listFilter === 'groups') return activeRows.filter(({ conv }) => conv.isGroup)
    return activeRows
  }, [activeRows, listFilter])
  const archivedUnread = useMemo(
    () => archivedRows.reduce((sum, { conv }) => sum + conv.unreadCount, 0),
    [archivedRows],
  )
  /** R30-c - channels the viewer is subscribed to (participant row = subscription) */
  const subscribedChannelCount = useMemo(
    () => rows.filter(({ conv }) => conv.isGroup && conv.broadcastMode).length,
    [rows],
  )

  /** Freeze "where was I" from the list summary AT TAP TIME (pre-read watermark). */
  const handlePress = useCallback(
    (conv: ConversationSummary) => {
      const mine = conv.members.find((m) => m.id === me.id) as
        | (AppUser & { lastReadAt?: string })
        | undefined
      const wm = mine?.lastReadAt ? Date.parse(mine.lastReadAt) : Number.NaN
      const anchor = conv.unreadCount > 0 && !Number.isNaN(wm) ? wm : null
      onOpenConversation(conv.id, anchor)
    },
    [onOpenConversation, me.id],
  )

  const closeSearch = useCallback(() => {
    if (deferredTimerRef.current !== null) {
      clearTimeout(deferredTimerRef.current)
      deferredTimerRef.current = null
    }
    setSearching(false)
    setSearchFocused(false)
    setSearchQuery('')
    setDeferredQuery('')
  }, [])

  /** typing in the search field: instant local filter + 250ms-debounced server search */
  const handleSearchInput = useCallback((value: string) => {
    setSearchQuery(value)
    if (deferredTimerRef.current !== null) clearTimeout(deferredTimerRef.current)
    const v = value.trim()
    deferredTimerRef.current = setTimeout(() => {
      deferredTimerRef.current = null
      setDeferredQuery(v)
    }, 250)
  }, [])

  const serverSearch = useQuery({
    queryKey: ['global-search', me.id, deferredQuery],
    enabled: searching && deferredQuery.length >= 2,
    staleTime: 15_000,
    queryFn: async (): Promise<SearchResponse> =>
      apiJson<SearchResponse>(
        `/api/search?userId=${encodeURIComponent(me.id)}&q=${encodeURIComponent(deferredQuery)}`,
      ),
  })
  const serverHits = searching && deferredQuery.length >= 2 ? (serverSearch.data?.messages ?? []) : []

  // row long-press action sheet (pin/unpin) 
  const [sheetConv, setSheetConv] = useState<ConversationSummary | null>(null)
  const queryClient = useQueryClient()

  // 24h status stories (ring row + viewer/composer sheets) 
  const storiesQ = useQuery({
    queryKey: storiesQueryKey(me.id),
    queryFn: async (): Promise<StoriesResponse> =>
      apiJson<StoriesResponse>(`/api/stories?requesterId=${encodeURIComponent(me.id)}`),
    staleTime: 15_000,
    refetchInterval: 60_000,
  })
  const storyGroups = useMemo(() => storiesQ.data?.groups ?? [], [storiesQ.data])
  const myStoryGroup = useMemo(() => storyGroups.find((g) => g.mine) ?? null, [storyGroups])
  const otherStoryGroups = useMemo(() => storyGroups.filter((g) => !g.mine), [storyGroups])

  /** open viewer at a specific user's first story (null = closed) */
  const [viewerStart, setViewerStart] = useState<{ userId: string; storyId: string } | null>(null)
  const [composerOpen, setComposerOpen] = useState(false)

  const openMyStatus = useCallback(() => {
    haptic(6)
    if (myStoryGroup && myStoryGroup.stories.length > 0) {
      setViewerStart({ userId: myStoryGroup.user.id, storyId: myStoryGroup.stories[0].id })
    } else {
      setComposerOpen(true)
    }
  }, [myStoryGroup])

  const openStoryGroup = useCallback((group: StoryGroup) => {
    haptic(6)
    setViewerStart({ userId: group.user.id, storyId: group.stories[0].id })
  }, [])

  const togglePin = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      return apiJson<{ ok: boolean; pinned: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/pin`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id }),
        },
      )
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(data.pinned ? 'Pinned to top' : 'Unpinned')
      setSheetConv(null)
    },
    onError: () => {
      toast.error('Could not update the pin')
    },
  })

  // R44 - mark as unread/read: flips the viewer's manualUnread flag
  // (PATCH /mark-unread); the row shows the dot until the room is opened.
  const toggleMarkUnread = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const on = !conv.myManualUnread
      return apiJson<{ ok: boolean; manualUnread: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/mark-unread`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, on }),
        },
      )
    },
    onMutate: async (conv) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<Array<ConversationSummary>>(['conversations', me.id])
      queryClient.setQueryData<Array<ConversationSummary>>(['conversations', me.id], (old) =>
        old
          ? old.map((c) => (c.id === conv.id ? { ...c, myManualUnread: !c.myManualUnread } : c))
          : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      toast.success(data.manualUnread ? 'Marked as unread' : 'Marked as read')
      setSheetConv(null)
    },
    onError: (_error, _conv, context) => {
      if (context?.previous) {
        queryClient.setQueryData<Array<ConversationSummary>>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the unread flag')
    },
  })

  /** per-user notification mute - '8h' | '1w' | 'always' | null (PATCH /mute) */
  const toggleMute = useMutation({
    mutationFn: async ({ conv, until }: { conv: ConversationSummary; until: '8h' | '1w' | 'always' | null }) => {
      return apiJson<{ ok: boolean; mutedUntil: string | null }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/mute`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, until }),
        },
      )
    },
    // optimistic - the BellOff chip flips before the round-trip lands
    onMutate: async ({ conv, until }) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const offsetsMs: Record<'8h' | '1w' | 'always', number> = {
          '8h': 8 * 60 * 60 * 1000,
          '1w': 7 * 24 * 60 * 60 * 1000,
          // mirror of the route's 'always' preset (+50y)
          always: 50 * 365 * 24 * 60 * 60 * 1000,
        }
        const mutedUntil = until === null ? null : new Date(Date.now() + offsetsMs[until]).toISOString()
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (c.id === conv.id ? { ...c, mutedUntil } : c)),
        )
      }
      return { previous }
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(
        data.mutedUntil === null
          ? 'Notifications unmuted'
          : data.mutedUntil !== null && Date.parse(data.mutedUntil) - Date.now() > 20 * 365 * 24 * 3600 * 1000
            ? 'Muted - always'
            : `Muted until ${formatListStamp(data.mutedUntil as string)}`,
      )
      setSheetConv(null)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the mute')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /** archive / unarchive - optimistic so the row moves instantly */
  const toggleArchive = useMutation({
    mutationFn: async ({ conv, archived }: { conv: ConversationSummary; archived: boolean }) => {
      return apiJson<{ ok: boolean; archived: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/archive`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, archived }),
        },
      )
    },
    onMutate: async ({ conv, archived }) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) =>
            c.id === conv.id ? { ...c, archivedAt: archived ? new Date().toISOString() : null } : c,
          ),
        )
      }
      return { previous }
    },
    onSuccess: (data) => {
      haptic(10)
      toast.success(data.archived ? 'Chat archived' : 'Chat unarchived')
      setSheetConv(null)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the archive')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /**
   * Clear chat - soft-delete MY OWN messages only (DELETE /api/messages/[id]
   * is sender-gated server-side, so other people's messages honestly stay).
   * Runs sequentially through the room's full real history.
   */
  const clearChat = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const history = await fetchFullHistory(conv.id)
      const mine = history.filter((m) => m.senderId === me.id && m.deletedAt === null)
      let cleared = 0
      for (const message of mine) {
        try {
          await apiJson(`/api/messages/${encodeURIComponent(message.id)}`, {
            method: 'DELETE',
            body: JSON.stringify({ requesterId: me.id }),
          })
          cleared += 1
        } catch {
          // keep going - clear as many of my own messages as the server allows
        }
      }
      return { cleared, total: mine.length }
    },
    onSuccess: ({ cleared }) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      if (cleared === 0) {
        toast.info('Nothing to clear - none of your messages are left in this chat.')
      } else {
        toast.success(`Cleared ${cleared} ${cleared === 1 ? 'message' : 'messages'}`, {
          description: 'Your messages were deleted for everyone.',
        })
      }
      setSheetConv(null)
    },
    onError: () => {
      toast.error('Could not clear this chat')
    },
  })

  /** Export chat - real paginated history → pulse-<room>-<date>.txt download. */
  const exportChat = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const history = await fetchFullHistory(conv.id)
      return downloadTranscript(conv, me.id, history)
    },
    onSuccess: (fileName) => {
      toast.success('Chat exported', { description: `Saved ${fileName}` })
    },
    onError: () => {
      toast.error('Could not export this chat')
    },
  })

  const openSheetFor = useCallback((conv: ConversationSummary) => setSheetConv(conv), [])

  // R34-a Telegram-style multi-select 
  // Long-press a row → select mode with that row checked; more taps
  // toggle; a floating glass bar runs Archive / Mute 8h / Mark read
  // over ALL selected rows through the SAME endpoints the row sheet
  // and swipe chips use (PATCH archive · PATCH mute · POST read).
  const [selectMode, setSelectMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set())

  const enterSelect = useCallback((conversationId: string) => {
    haptic(15)
    setSelectMode(true)
    setSelectedIds(new Set([conversationId]))
  }, [])

  const exitSelect = useCallback(() => {
    setSelectMode(false)
    setSelectedIds(new Set())
  }, [])

  const toggleSelect = useCallback((conversationId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(conversationId)) next.delete(conversationId)
      else next.add(conversationId)
      return next
    })
  }, [])

  // empty selection collapses the mode; opening search leaves it too
  useEffect(() => {
    if (selectMode && selectedIds.size === 0) setSelectMode(false)
  }, [selectMode, selectedIds])
  useEffect(() => {
    if (searching && selectMode) exitSelect()
  }, [searching, selectMode, exitSelect])

  /** the selected rows (active list only - select mode lives on the main list) */
  const selectedConvs = useMemo(
    () => activeRows.filter(({ conv }) => selectedIds.has(conv.id)).map(({ conv }) => conv),
    [activeRows, selectedIds],
  )

  /** Archive every selected chat - same PATCH /archive the sheet + swipe use. */
  const batchArchive = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let archived = 0
      for (const conv of convs) {
        await apiJson<{ ok: boolean; archived: boolean }>(
          `/api/conversations/${encodeURIComponent(conv.id)}/archive`,
          {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: me.id, archived: conv.archivedAt === null }),
          },
        )
        archived += 1
      }
      return { archived }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, archivedAt: new Date().toISOString() } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ archived }) => {
      haptic(10)
      toast.success(`Archived ${archived} ${archived === 1 ? 'chat' : 'chats'}`)
      exitSelect()
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not archive the selected chats')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /** Mute every selected chat for 8 hours - same PATCH /mute preset the sheet uses. */
  const batchMute8h = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let muted = 0
      for (const conv of convs) {
        await apiJson<{ ok: boolean; mutedUntil: string | null }>(
          `/api/conversations/${encodeURIComponent(conv.id)}/mute`,
          {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: me.id, until: '8h' }),
          },
        )
        muted += 1
      }
      return { muted }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        const mutedUntil = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString()
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, mutedUntil } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ muted }) => {
      haptic(10)
      toast.success(`Muted ${muted} ${muted === 1 ? 'chat' : 'chats'} for 8 hours`)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not mute the selected chats')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /**
   * Mark every selected chat read - POST /read (the same endpoint the room
   * uses on open); optimistically zeroes unreadCount so the pills drop live.
   * Per-chat failures are counted honestly: full success toasts + clears the
   * selection, a partial failure toasts the misses and keeps the mode so the
   * remaining rows can be retried.
   */
  const batchMarkRead = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let read = 0
      let failed = 0
      for (const conv of convs) {
        try {
          await apiJson<{ ok: boolean }>(
            `/api/conversations/${encodeURIComponent(conv.id)}/read`,
            {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ userId: me.id }),
            },
          )
          read += 1
        } catch {
          failed += 1 // keep going - mark as many as the server allows
        }
      }
      return { read, failed }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, unreadCount: 0 } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ read, failed }) => {
      haptic(10)
      if (read > 0) toast.success(`${read} ${read === 1 ? 'chat' : 'chats'} marked as read`)
      if (failed > 0) {
        toast.error(
          `${failed} ${failed === 1 ? 'chat' : 'chats'} could not be marked read - try again`,
        )
      } else {
        exitSelect()
      }
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not mark the selected chats read')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  // R24-a Signal-style chat folders + Note to Self 
  const reducedMotion = useReducedMotion()
  const [foldersOpen, setFoldersOpen] = useState(false)
  /** active rail folder (null = All) */
  const [activeFolderId, setActiveFolderId] = useState<string | null>(null)

  const foldersQ = useQuery({
    queryKey: ['folders', me.id],
    queryFn: async (): Promise<FolderSummary[]> => {
      const res = await apiJson<{ folders: FolderSummary[] }>(
        `/api/folders?userId=${encodeURIComponent(me.id)}`,
      )
      return res.folders
    },
    staleTime: 10_000,
  })
  const folders = useMemo(() => foldersQ.data ?? [], [foldersQ.data])

  // folder deleted (or lost) elsewhere → fall back to All
  useEffect(() => {
    if (activeFolderId !== null && !folders.some((f) => f.id === activeFolderId)) {
      setActiveFolderId(null)
    }
  }, [activeFolderId, folders])

  /** per-folder rail badge: its chats present in the active (non-archived) list */
  const folderCounts = useMemo(() => {
    const activeIds = new Set(activeRows.map(({ conv }) => conv.id))
    const counts = new Map<string, number>()
    for (const folder of folders) {
      counts.set(folder.id, folder.conversationIds.reduce((n, id) => (activeIds.has(id) ? n + 1 : n), 0))
    }
    return counts
  }, [folders, activeRows])

  /** rows of the currently active folder (rail filter on top of the Telegram filter) */
  const visibleRows = useMemo(() => {
    if (activeFolderId === null) return folderFiltered
    const ids = new Set(folders.find((f) => f.id === activeFolderId)?.conversationIds ?? [])
    return folderFiltered.filter(({ conv }) => ids.has(conv.id))
  }, [folderFiltered, activeFolderId, folders])

  /** the viewer's private Note to Self chat (null = not created yet) */
  const selfConv = useMemo(
    () => (conversations.data ?? []).find((conv) => conv.isSelf) ?? null,
    [conversations.data],
  )

  const createSelfChat = useMutation({
    mutationFn: async () =>
      apiJson<{ conversation: ConversationSummary }>('/api/conversations/self', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      }),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      onOpenConversation(res.conversation.id, null)
    },
    onError: () => {
      toast.error('Could not open Note to Self')
    },
  })

  const handleSelfPress = useCallback(() => {
    haptic(6)
    if (selfConv) handlePress(selfConv)
    else createSelfChat.mutate()
  }, [selfConv, handlePress, createSelfChat])

  // R54-b kebab relocations - "New group" opens the REAL group composer
  // (NewChatSheet in group mode, remounted per open session like main-shell
  // does so local state is fresh every time).
  const [groupSheetGeneration, setGroupSheetGeneration] = useState(0)
  const [groupSheetMounted, setGroupSheetMounted] = useState(false)
  const [groupSheetOpen, setGroupSheetOpen] = useState(false)

  const openGroupSheet = useCallback(() => {
    setGroupSheetGeneration((g) => g + 1)
    setGroupSheetMounted(true)
    setGroupSheetOpen(false)
    // render the fresh sheet closed for a frame so vaul plays its slide-up
    window.setTimeout(() => setGroupSheetOpen(true), 30)
  }, [])

  // R54-b kebab relocations - "Join with code" asks for the code, then the
  // REAL JoinGroupSheet previews + joins through /api/invite/[code].
  const [joinDialogOpen, setJoinDialogOpen] = useState(false)
  const [joinCodeInput, setJoinCodeInput] = useState('')
  const [pendingJoinCode, setPendingJoinCode] = useState<string | null>(null)

  const openJoinDialog = useCallback(() => {
    setJoinCodeInput('')
    setJoinDialogOpen(true)
  }, [])

  const submitJoinCode = useCallback(() => {
    const code = joinCodeInput.trim().toUpperCase()
    if (code.length < 4) {
      toast.error('Enter the invite code you were given')
      return
    }
    setJoinDialogOpen(false)
    setPendingJoinCode(code)
  }, [joinCodeInput])

  // R54-b kebab relocations - "Appearance" carries the old header sun
  // button's exact behavior (ThemeToggleButton's next-themes toggle).
  const { resolvedTheme, setTheme } = useTheme()
  const mounted = useMounted()
  const isDarkTheme = mounted && resolvedTheme === 'dark'
  const toggleTheme = useCallback(() => {
    setTheme(isDarkTheme ? 'light' : 'dark')
  }, [isDarkTheme, setTheme])

  const data = conversations.data ?? []

  // main list order: pinned chats ride on top (stable partition, artboard
  // shows no section headers - the pin glyph on the row keeps the state legible)
  const orderedRows = useMemo(() => {
    const head = visibleRows.filter(({ props }) => props.pinned)
    const tail = visibleRows.filter(({ props }) => !props.pinned)
    return [...head, ...tail]
  }, [visibleRows])

  return (
    <div className="art-scene absolute inset-0 isolate flex flex-col">
      {/* header - artboard: bold title + exactly three bare icons */}
      <header className="relative z-10 shrink-0 px-3 pt-[max(10px,env(safe-area-inset-top))]">
        {searching ? (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="mx-auto flex w-full max-w-[560px] items-center gap-2 py-2"
          >
            {/* dark pill - the search icon expands into the full input */}
            <motion.div
              initial={{ opacity: 0, scale: 0.96 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="relative flex h-10 min-w-0 flex-1 items-center gap-2.5 rounded-full bg-white/[0.07] px-4 ring-1 ring-[var(--art-hairline)] backdrop-blur-xl"
            >
              <motion.span
                aria-hidden
                animate={{ opacity: searchFocused ? 1 : 0 }}
                transition={spring.soft}
                className="pointer-events-none absolute inset-0 rounded-full shadow-[0_0_20px_rgba(255,122,61,0.25)] ring-2 ring-[var(--art-accent)]/50"
              />
              <PulseSearch className="size-4 shrink-0 text-[var(--art-faint)]" aria-hidden />
              <motion.div
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.2, ease: ease.out }}
                className="min-w-0 flex-1"
              >
                <Input
                  autoFocus
                  value={searchQuery}
                  onChange={(e) => handleSearchInput(e.target.value)}
                  onFocus={() => setSearchFocused(true)}
                  onBlur={() => setSearchFocused(false)}
                  placeholder="Search chats and messages…"
                  aria-label="Search conversations"
                  className="h-full border-0 bg-transparent p-0 text-sm text-[var(--art-text)] shadow-none placeholder:text-[var(--art-faint)] focus-visible:ring-0 dark:bg-transparent"
                />
              </motion.div>
              <AnimatePresence initial={false}>
                {searchQuery ? (
                  <motion.button
                    key="clear-search"
                    type="button"
                    initial={{ scale: 0, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={{ scale: 0, opacity: 0 }}
                    transition={spring.bouncy}
                    onClick={() => handleSearchInput('')}
                    aria-label="Clear search"
                    className="flex size-6 shrink-0 items-center justify-center rounded-full bg-white/10 text-[var(--art-dim)] outline-none"
                  >
                    <X className="size-3.5" aria-hidden />
                  </motion.button>
                ) : null}
              </AnimatePresence>
            </motion.div>
            <AnimatePresence initial={false} mode="popLayout">
              <motion.span
                key={`close-search-${searchFocused && searchQuery ? 'hot' : 'idle'}`}
                initial={{ opacity: 0, scale: 0.6 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.6 }}
                transition={spring.bouncy}
              >
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Close search"
                  onClick={closeSearch}
                  className={cn(
                    'size-10 shrink-0 rounded-full text-[var(--art-dim)] hover:bg-white/5 hover:text-[var(--art-text)] active:scale-95',
                    searchFocused && searchQuery && 'text-[var(--art-accent-2)]',
                  )}
                >
                  <X className="size-5" aria-hidden />
                </Button>
              </motion.span>
            </AnimatePresence>
          </motion.div>
        ) : (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="mx-auto flex w-full max-w-[560px] items-center gap-0.5 py-2"
          >
            <h1 className="mr-auto pl-1 text-[26px] font-bold leading-tight tracking-tight text-[var(--art-text)]">
              Chats
            </h1>
            <motion.button
              type="button"
              aria-label="Search chats and messages"
              onClick={() => {
                haptic(6)
                setSearching(true)
              }}
              whileTap={{ scale: 0.92 }}
              transition={pressSpring}
              className={HEADER_ICON_CLS}
            >
              <Search className="size-[22px]" aria-hidden />
            </motion.button>
            <motion.button
              type="button"
              aria-label="Open story camera"
              onClick={() => {
                haptic(6)
                setComposerOpen(true)
              }}
              whileTap={{ scale: 0.92 }}
              transition={pressSpring}
              className={HEADER_ICON_CLS}
            >
              <Camera className="size-[22px]" aria-hidden />
            </motion.button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button type="button" aria-label="More options" className={HEADER_ICON_CLS}>
                  <MoreVertical className="size-[22px]" aria-hidden />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" sideOffset={6} className={KEBAB_CONTENT_CLS}>
                <DropdownMenuLabel className={KEBAB_LABEL_CLS}>Actions</DropdownMenuLabel>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    setSearching(true)
                  }}
                >
                  <Search className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Search
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    onRequestNewChat()
                  }}
                >
                  <PulseCompose className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  New chat
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openGroupSheet()
                  }}
                >
                  <Users className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  New group
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openJoinDialog()
                  }}
                >
                  <Ticket className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Join with code
                </DropdownMenuItem>
                <DropdownMenuSeparator className="bg-[var(--art-hairline)]" />
                <DropdownMenuLabel className={KEBAB_LABEL_CLS}>Browse</DropdownMenuLabel>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    onOpenContacts()
                  }}
                >
                  <BookUser className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Contacts
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openCallsPage()
                  }}
                >
                  <Phone className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Calls
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openArchivedPage()
                  }}
                >
                  <Archive className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Archived
                  <KebabTrailing>
                    {archivedUnread > 0 ? `${archivedUnread} unread` : archivedRows.length}
                  </KebabTrailing>
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    handleSelfPress()
                  }}
                >
                  <NotebookPen className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Note to Self
                  {createSelfChat.isPending ? (
                    <LoaderCircle className="ml-auto size-3.5 animate-spin text-[var(--art-dim)]" aria-hidden />
                  ) : (
                    <KebabTrailing>{selfConv ? 'Open' : 'New'}</KebabTrailing>
                  )}
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openMentionsPage()
                  }}
                >
                  <AtSign className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Mentions
                  {mentionCount > 0 ? <KebabTrailing>{mentionCount > 99 ? '99+' : mentionCount}</KebabTrailing> : null}
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openChannelsPage()
                  }}
                >
                  <Radio className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Channels
                  <KebabTrailing>{subscribedChannelCount}</KebabTrailing>
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    setFoldersOpen(true)
                  }}
                >
                  <FolderPlus className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Folders
                </DropdownMenuItem>
                <DropdownMenuSeparator className="bg-[var(--art-hairline)]" />
                <DropdownMenuLabel className={KEBAB_LABEL_CLS}>System</DropdownMenuLabel>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    onGoProfile()
                  }}
                >
                  <Bookmark className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Saved
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    openMyStatus()
                  }}
                >
                  <CircleDashed className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Stories
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    navigate('/settings')
                  }}
                >
                  <Settings className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  Settings
                </DropdownMenuItem>
                <DropdownMenuItem
                  className={KEBAB_ITEM_CLS}
                  onSelect={() => {
                    haptic(6)
                    toggleTheme()
                  }}
                >
                  {isDarkTheme ? (
                    <Sun className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  ) : (
                    <Moon className="size-[18px] text-[var(--art-dim)]" aria-hidden />
                  )}
                  Appearance
                  <KebabTrailing>{isDarkTheme ? 'Dark' : 'Light'}</KebabTrailing>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </motion.div>
        )}
      </header>

      {/* 24h status stories row - artboard photo cards with corner badges */}
      {!searching ? (
        <div className="relative z-10 shrink-0 pb-1">
          <h2 className="sr-only">Status</h2>
          <div className="no-scrollbar mx-auto flex w-full max-w-[560px] snap-x snap-mandatory items-start gap-3 overflow-x-auto px-3 pt-1.5 [mask-image:linear-gradient(to_right,transparent_0,black_12px,black_calc(100%-12px),transparent_100%)]">
            <StoryRingCell
              name={me.name}
              color={me.color}
              ring={myStoryGroup ? 'unseen' : 'none'}
              label="You"
              image={myStoryGroup?.stories.find((s) => s.kind === 'image')?.imagePath ?? null}
              background={myStoryGroup?.stories[0]?.kind === 'text' ? myStoryGroup.stories[0].background : null}
              badge={null}
              onPress={openMyStatus}
              index={0}
            />
            {otherStoryGroups.map((group, i) => (
              <StoryRingCell
                key={group.user.id}
                name={group.user.name}
                color={group.user.color}
                ring={group.allSeen ? 'seen' : 'unseen'}
                label={group.user.name}
                image={group.stories.find((s) => s.kind === 'image')?.imagePath ?? null}
                background={group.stories[0]?.kind === 'text' ? group.stories[0].background : null}
                badge={group.allSeen ? null : group.stories.length}
                onPress={() => openStoryGroup(group)}
                index={i + 1}
              />
            ))}
          </div>
        </div>
      ) : null}

      {/* filter chips - ONE rail: the real All/Unread/Groups filters + the
          viewer's Telegram folders + the folder manager, all in the
          artboard chip language, horizontal scroll, no wrap */}
      {!searching ? (
        <div className="relative z-10 shrink-0 pb-2 pt-1.5">
          <div
            className="no-scrollbar mx-auto flex w-full max-w-[560px] items-center gap-1.5 overflow-x-auto px-3"
            role="tablist"
            aria-label="Chat filters"
          >
            {([
              { key: 'all', label: 'All' },
              { key: 'unread', label: 'Unread' },
              { key: 'groups', label: 'Groups' },
            ] as Array<{ key: ChatsListFilter; label: string }>).map((f) => {
              const active = listFilter === f.key
              return (
                <motion.button
                  key={f.key}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => {
                    haptic(6)
                    setListFilter(f.key)
                  }}
                  whileTap={reducedMotion ? undefined : pressTap}
                  transition={pressSpring}
                  className={cn(ART_CHIP_CLS, active ? ART_CHIP_ACTIVE : ART_CHIP_IDLE)}
                >
                  <span className="flex items-center gap-1.5">
                    {f.label === 'Unread' && unreadTotal > 0 && !active ? (
                      <span className="flex h-[15px] min-w-[15px] items-center justify-center rounded-full bg-white/10 px-1 text-[9px] font-bold text-[var(--art-text-soft)]">
                        {unreadTotal > 99 ? '99+' : unreadTotal}
                      </span>
                    ) : null}
                    {f.label}
                  </span>
                </motion.button>
              )
            })}
            {folders.length > 0 ? (
              <span aria-hidden className="mx-0.5 h-4 w-px shrink-0 bg-[var(--art-hairline)]" />
            ) : null}
            {folders.map((folder) => {
              const active = activeFolderId === folder.id
              const count = folderCounts.get(folder.id) ?? 0
              return (
                <motion.button
                  key={folder.id}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  aria-label={`Folder ${folder.name} - ${count} ${count === 1 ? 'chat' : 'chats'}`}
                  onClick={() => {
                    haptic(6)
                    setActiveFolderId(active ? null : folder.id)
                  }}
                  whileTap={reducedMotion ? undefined : pressTap}
                  transition={pressSpring}
                  className={cn(ART_CHIP_CLS, active ? ART_CHIP_ACTIVE : ART_CHIP_IDLE)}
                >
                  <span className="flex items-center gap-1.5">
                    <FolderRailGlyph value={folder.emoji} className="size-3.5" />
                    <span className="max-w-[96px] truncate">{folder.name}</span>
                    {count > 0 ? (
                      <span
                        className={cn(
                          'flex h-[15px] min-w-[15px] items-center justify-center rounded-full px-1 text-[9px] font-bold',
                          active
                            ? 'bg-white/20 text-[var(--art-text)]'
                            : 'bg-white/[0.07] text-[var(--art-dim)]',
                        )}
                      >
                        {count > 99 ? '99+' : count}
                      </span>
                    ) : null}
                  </span>
                </motion.button>
              )
            })}
            <motion.button
              type="button"
              aria-label="Manage chat folders"
              onClick={() => {
                haptic(6)
                setFoldersOpen(true)
              }}
              whileTap={reducedMotion ? undefined : pressTap}
              transition={pressSpring}
              className="flex size-[30px] shrink-0 items-center justify-center rounded-full bg-[var(--art-chip)] text-[var(--art-dim)] outline-none transition-colors hover:text-[var(--art-text)] focus-visible:ring-2 focus-visible:ring-[var(--art-accent)]/50"
            >
              <PulseFolderPlus className="size-[15px]" aria-hidden />
            </motion.button>
          </div>
        </div>
      ) : null}

      {/* list - flat rows on the ember scene, dock-clearing bottom pad */}
      <div className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto w-full max-w-[560px] pb-[calc(108px+env(safe-area-inset-bottom))] pt-1">
          {conversations.isPending ? (
            <div role="status" aria-label="Loading conversations" className="pt-2">
              <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
            </div>
          ) : searching ? (
            <div className="py-1">
              {filteredRows.length > 0 ? (
                <>
                  <SearchSection label="Chats" count={filteredRows.length} />
                  {filteredRows.map(({ conv, props }, i) => (
                    <ArtConversationRow
                      key={props.id}
                      data={props}
                      entranceIndex={entranceOn ? i : null}
                      onPress={() => handlePress(conv)}
                      onLongPress={() => openSheetFor(conv)}
                      onOptions={() => openSheetFor(conv)}
                    />
                  ))}
                </>
              ) : null}

              {deferredQuery.length >= 2 ? (
                serverSearch.isPending ? (
                  <div className="flex items-center justify-center gap-2 py-6" role="status" aria-label="Searching messages">
                    <LoaderCircle className="size-4 animate-spin text-[var(--art-accent)]" aria-hidden />
                    <span className="text-xs font-medium text-[var(--art-faint)]">Searching messages…</span>
                  </div>
                ) : serverHits.length > 0 ? (
                  <>
                    <SearchSection label="Messages" count={serverSearch.data?.total ?? serverHits.length} />
                    {serverHits.map((hit) => (
                      <SearchMessageRow
                        key={hit.id}
                        hit={hit}
                        query={deferredQuery}
                        onPress={(h) => onOpenConversation(h.conversationId, null, h.id)}
                      />
                    ))}
                  </>
                ) : null
              ) : deferredQuery.length > 0 ? (
                <p className="px-4 pt-3 text-center text-xs text-[var(--art-faint)]">
                  Keep typing to search inside messages…
                </p>
              ) : null}

              {filteredRows.length === 0 &&
              (deferredQuery.length < 2 || (!serverSearch.isPending && serverHits.length === 0)) ? (
                <div className="flex flex-col items-center justify-center gap-2 px-8 pt-24 text-center">
                  <Search className="size-8 text-[var(--art-faint)]" aria-hidden />
                  <p className="text-sm font-medium text-[var(--art-text)]">No matches</p>
                  <p className="text-xs text-[var(--art-dim)]">
                    Nothing here for “{searchQuery}”.
                  </p>
                </div>
              ) : null}
            </div>
          ) : rows.length === 0 ? (
            <EmptyChats onSayHi={onOpenContacts} />
          ) : (
            <div className="pb-1">
              {/* folder switch springs the whole list block (R24-a) */}
              <motion.div
                key={activeFolderId ?? 'all'}
                initial={reducedMotion ? false : { opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={spring.soft}
                style={{ willChange: 'transform' }}
              >
                {orderedRows.map(({ conv, props }, i) => (
                  <ArtConversationRow
                    key={props.id}
                    data={props}
                    entranceIndex={entranceOn ? i : null}
                    selectMode={selectMode}
                    selected={selectedIds.has(props.id)}
                    onPress={() => handlePress(conv)}
                    onLongPress={() => enterSelect(props.id)}
                    onToggleSelect={() => toggleSelect(props.id)}
                    onOptions={() => openSheetFor(conv)}
                  />
                ))}
              </motion.div>
              {visibleRows.length === 0 && activeRows.length > 0 ? (
                <p className="px-8 pb-4 pt-10 text-center text-[13px] leading-relaxed text-[var(--art-faint)]">
                  {activeFolderId !== null
                    ? 'This folder is empty - tap the folder button on the rail to add chats.'
                    : listFilter === 'unread'
                      ? 'No unread chats - you are all caught up.'
                      : 'No groups yet - start one from Contacts.'}
                </p>
              ) : null}
              {activeRows.length === 0 && archivedRows.length > 0 ? (
                <p className="px-8 pb-4 pt-10 text-center text-[13px] leading-relaxed text-[var(--art-faint)]">
                  Every chat is archived.
                  <br />
                  New messages bring chats back here.
                </p>
              ) : null}
            </div>
          )}
        </div>
      </div>

      {/* R34-a - Telegram-style floating bar over the selected rows.
          Staggered entrance; Archive exits the mode (rows leave the list),
          Mute 8h / Mark read keep it so the operator can keep working. */}
      <AnimatePresence>
        {selectMode ? (
          <motion.div
            key="multi-select-bar"
            initial={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 28, scale: 0.92 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.94 }}
            transition={spring.snappy}
            className="pointer-events-none absolute inset-x-0 bottom-[86px] z-40 flex justify-center px-3"
          >
            <div
              role="toolbar"
              aria-label={`Actions for ${selectedIds.size} selected ${selectedIds.size === 1 ? 'chat' : 'chats'}`}
              className="art-panel pointer-events-auto flex items-center gap-0.5 rounded-full p-1.5 shadow-xl shadow-black/40"
            >
              <span className="ml-1.5 mr-1 shrink-0 text-[12px] font-bold tabular-nums text-[var(--art-text-soft)]">
                {selectedIds.size} selected
              </span>
              {([
                {
                  key: 'archive',
                  icon: Archive,
                  label: 'Archive selected chats',
                  short: 'Archive',
                  onClick: () => batchArchive.mutate(selectedConvs),
                  pending: batchArchive.isPending,
                },
                {
                  key: 'mute',
                  icon: BellOff,
                  label: 'Mute selected chats for 8 hours',
                  short: 'Mute 8h',
                  onClick: () => batchMute8h.mutate(selectedConvs),
                  pending: batchMute8h.isPending,
                },
                {
                  key: 'read',
                  icon: CheckCheck,
                  label: 'Mark selected chats read',
                  short: 'Mark read',
                  onClick: () => batchMarkRead.mutate(selectedConvs),
                  pending: batchMarkRead.isPending,
                },
              ] as const).map((action, i) => (
                <motion.button
                  key={action.key}
                  type="button"
                  aria-label={action.label}
                  disabled={action.pending || selectedConvs.length === 0}
                  onClick={action.onClick}
                  initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ ...spring.soft, delay: reducedMotion ? 0 : 0.05 + i * 0.045 }}
                  whileTap={reducedMotion ? undefined : pressTap}
                  className="flex size-10 items-center justify-center rounded-full text-[var(--art-text-soft)] outline-none transition-colors hover:bg-white/10 hover:text-[var(--art-text)] disabled:opacity-40"
                >
                  {action.pending ? (
                    <LoaderCircle className="size-[18px] animate-spin" aria-hidden />
                  ) : (
                    <action.icon className="size-[18px]" aria-hidden />
                  )}
                  <span className="sr-only">{action.short}</span>
                </motion.button>
              ))}
              <motion.button
                type="button"
                aria-label="Exit multi-select"
                onClick={exitSelect}
                initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring.soft, delay: reducedMotion ? 0 : 0.2 }}
                whileTap={reducedMotion ? undefined : pressTap}
                className="ml-0.5 flex size-10 items-center justify-center rounded-full text-[var(--art-faint)] outline-none transition-colors hover:bg-white/10 hover:text-[var(--art-text)]"
              >
                <X className="size-[18px]" aria-hidden />
              </motion.button>
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* long-press glass option menu - pin / archive / mute strip / export / clear */}
      <ChatOptionsSheet
        conv={sheetConv}
        me={me}
        pinPending={togglePin.isPending}
        archivePending={toggleArchive.isPending}
        mutePending={toggleMute.isPending}
        clearPending={clearChat.isPending}
        exportPending={exportChat.isPending}
        manualUnread={sheetConv?.myManualUnread ?? false}
        markUnreadPending={toggleMarkUnread.isPending}
        onPin={() => {
          if (sheetConv) togglePin.mutate(sheetConv)
        }}
        onArchive={() => {
          if (sheetConv) toggleArchive.mutate({ conv: sheetConv, archived: sheetConv.archivedAt === null })
        }}
        onMarkUnread={() => {
          if (sheetConv) toggleMarkUnread.mutate(sheetConv)
        }}
        onMute={(until) => {
          if (sheetConv) toggleMute.mutate({ conv: sheetConv, until })
        }}
        onUnmute={() => {
          if (sheetConv) toggleMute.mutate({ conv: sheetConv, until: null })
        }}
        onExport={() => {
          if (sheetConv) exportChat.mutate(sheetConv)
        }}
        onClear={() => {
          if (sheetConv) clearChat.mutate(sheetConv)
        }}
        onClose={() => setSheetConv(null)}
      />

      {/* #/chats/archived - real hash-routed glass sub-page */}
      <ChatsArchivedPage
        open={archivedPageOpen}
        me={me}
        rows={archivedRows}
        loading={conversations.isPending}
        entrance={entranceOn}
        onBack={() => back('/')}
        onPress={handlePress}
        onLongPress={openSheetFor}
        onPin={(conv) => togglePin.mutate(conv)}
        onArchive={(conv) => toggleArchive.mutate({ conv, archived: conv.archivedAt === null })}
      />

      {/* #/chats/channels - R30-c broadcast directory sub-page */}
      <ChannelsPage
        open={channelsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* #/calls - R34-a WhatsApp 'Calls' history sub-page */}
      <CallsPage
        open={callsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* #/mentions - R35-b Discord-style @mention feed sub-page */}
      <MentionsPage
        open={mentionsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* R24-a chat folders manager sheet */}
      <FoldersSheet
        open={foldersOpen}
        onClose={() => setFoldersOpen(false)}
        me={me}
        conversations={data}
      />

      {/* R54-b kebab "New group" - the real group composer in group mode */}
      {groupSheetMounted ? (
        <NewChatSheet
          key={groupSheetGeneration}
          me={me}
          open={groupSheetOpen}
          onOpenChange={setGroupSheetOpen}
          initialMode="group"
          onConversationOpened={(conversationId) => {
            setGroupSheetOpen(false)
            onOpenConversation(conversationId, null)
          }}
        />
      ) : null}

      {/* R54-b kebab "Join with code" - real invite preview + join */}
      {pendingJoinCode !== null ? (
        <JoinGroupSheet
          me={me}
          code={pendingJoinCode}
          open
          onClose={() => setPendingJoinCode(null)}
          onJoined={(conversationId) => {
            setPendingJoinCode(null)
            onOpenConversation(conversationId, null)
          }}
        />
      ) : null}

      {/* R54-b kebab "Join with code" - code entry dialog (dark ember) */}
      <AnimatePresence>
        {joinDialogOpen ? (
          <motion.div
            key="join-dialog"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.16 }}
            className="absolute inset-0 z-[70] flex items-center justify-center bg-black/60 px-6 backdrop-blur-sm"
            onClick={() => setJoinDialogOpen(false)}
          >
            <motion.div
              role="dialog"
              aria-modal="true"
              aria-label="Join with code"
              initial={reducedMotion ? { opacity: 0 } : { opacity: 0, scale: 0.94, y: 10 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={reducedMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96, y: 6 }}
              transition={spring.snappy}
              className="w-full max-w-[340px] rounded-3xl border border-[var(--art-hairline)] bg-[#1c1610]/95 p-5 shadow-2xl shadow-black/60 backdrop-blur-xl"
              onClick={(e) => e.stopPropagation()}
            >
              <h2 className="text-[15px] font-semibold text-[var(--art-text)]">Join with code</h2>
              <p className="mt-1 text-[12.5px] leading-relaxed text-[var(--art-dim)]">
                Enter the group invite code you were given and jump straight in.
              </p>
              <form
                onSubmit={(e) => {
                  e.preventDefault()
                  submitJoinCode()
                }}
                className="mt-4 flex flex-col gap-3"
              >
                <Input
                  autoFocus
                  value={joinCodeInput}
                  onChange={(e) => setJoinCodeInput(e.target.value.toUpperCase())}
                  placeholder="ABCD1234"
                  aria-label="Invite code"
                  className="h-11 rounded-xl border-[var(--art-hairline)] bg-white/[0.06] text-center text-[15px] font-semibold uppercase tracking-[0.2em] text-[var(--art-text)] placeholder:text-[var(--art-faint)] focus-visible:ring-[var(--art-accent)]/50"
                />
                <div className="flex items-center justify-end gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => setJoinDialogOpen(false)}
                    className="h-9 rounded-full px-4 text-[13px] font-semibold text-[var(--art-dim)] hover:bg-white/5 hover:text-[var(--art-text)]"
                  >
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    disabled={joinCodeInput.trim().length < 4}
                    className="h-9 rounded-full border-0 bg-[var(--art-accent)] px-5 text-[13px] font-bold text-white hover:bg-[var(--art-accent)]/90 active:scale-[0.98]"
                  >
                    Join group
                  </Button>
                </div>
              </form>
            </motion.div>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* status story viewer + composer (full-screen overlays) */}
      <AnimatePresence>
        {composerOpen ? (
          <StoryComposerSheet key="story-composer" me={me} onClose={() => setComposerOpen(false)} />
        ) : null}
        {viewerStart !== null && storyGroups.length > 0 ? (
          <StoriesSheet
            key="stories-viewer"
            me={me}
            groups={storyGroups}
            start={viewerStart}
            onClose={() => {
              setViewerStart(null)
              void queryClient.invalidateQueries({ queryKey: storiesQueryKey(me.id) })
            }}
          />
        ) : null}
      </AnimatePresence>
    </div>
  )
}

function EmptyChats({ onSayHi }: { onSayHi: () => void }) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.div
      initial={reducedMotion ? false : { opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: ease.out }}
      className="flex flex-col items-center justify-center px-4 pt-10 text-center"
    >
      {/* R54-b empty state on the ember scene: hairline panel over the
          horizon glow with a soft ember bloom behind the illustration. */}
      <div className="relative flex w-full max-w-[300px] flex-col items-center gap-4 rounded-[28px] border border-[var(--art-hairline)] bg-white/[0.04] px-6 py-8 backdrop-blur-xl">
        <span
          aria-hidden
          className="pointer-events-none absolute -top-10 left-1/2 size-40 -translate-x-1/2 rounded-full bg-[radial-gradient(circle,rgba(255,122,61,0.22),transparent_65%)] blur-md"
        />
        <Image
          src="/empty-chats-ember.png"
          alt="No conversations illustration"
          width={144}
          height={144}
          className="relative rounded-3xl ring-1 ring-white/10"
        />
        <div className="relative">
          <h2 className="text-base font-semibold tracking-tight text-[var(--art-text)]">
            No conversations yet
          </h2>
          <p className="mx-auto mt-1 max-w-[240px] text-[13px] leading-relaxed text-[var(--art-dim)]">
            Your next great chat is one tap away. Find someone and break the ice.
          </p>
        </div>
        <Button
          onClick={onSayHi}
          variant="outline"
          className="relative h-10 gap-1.5 rounded-full border-[var(--art-accent)]/40 bg-transparent px-5 text-sm font-semibold text-[var(--art-accent-2)] hover:bg-[var(--art-accent)]/10 hover:text-[var(--art-accent-2)] active:scale-[0.98]"
        >
          Say hi to someone
          <ArrowRight className="size-4" aria-hidden />
        </Button>
      </div>
    </motion.div>
  )
}

/** Folder chip glyph: persisted id to designed icon, never raw. */
function FolderRailGlyph({ value, className }: { value: string; className?: string }) {
  // module-scope record member access: stable component reference
  const Glyph = FOLDER_ICON_GLYPHS[folderIconId(value)]
  return <Glyph className={className} aria-hidden />
}

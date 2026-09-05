// ─────────────────────────────────────────────────────────────
// Pulse — conversation list row (R27-e: extracted from chats-tab
// so the chats list AND the #/chats/archived sub-page share one
// row implementation — same swipe chips, same presence, same motion).
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback, useRef, useState } from 'react'
import { motion, useReducedMotion, type PanInfo } from 'framer-motion'
import { Archive, ArchiveRestore, BellOff, MoreVertical, PencilLine, Pin, PinOff } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'

export interface ConversationRowProps {
  id: string
  isGroup: boolean
  name: string
  time: string
  preview: string
  previewPrefix: string
  previewDeleted: boolean
  /** unsent composer draft persisted for this conversation (null = none) */
  draft: string | null
  unreadCount: number
  // avatar inputs
  dmName: string | null
  dmColor: string
  groupTitle: string
  online: boolean
  pinned: boolean
  /** viewer muted this conversation (watermark in the future) */
  muted: boolean
  /** someone is typing in this conversation right now */
  typing: boolean
  /** row lives in the archived sub-page (swipe chip flips to Unarchive) */
  archived: boolean
  /** stagger slot for the initial-mount entrance (null = animate nothing) */
  entranceIndex: number | null
  onPress: () => void
  onLongPress: () => void
  /** existing pin/unpin handler — surfaced as a swipe-left chip */
  onPin: () => void
  /** existing archive/unarchive handler — surfaced as a swipe-left chip */
  onArchive: () => void
}

/** Row data without the callbacks/stagger slot — what list builders construct. */
export type ConversationRowData = Omit<
  ConversationRowProps,
  'onPress' | 'onLongPress' | 'onPin' | 'onArchive' | 'entranceIndex'
>

const LONG_PRESS_MS = 450

/** Full reveal width of the swipe action tray (2 glass chips). */
const SWIPE_REVEAL_PX = 112
/** Drag distance that snaps the tray open (one chip width). */
const SWIPE_OPEN_THRESHOLD_PX = 56

/** Pulsing emerald presence halo behind online avatars (spring.gentle loop). */
function PresenceGlow({ reduced }: { reduced: boolean }) {
  if (reduced) {
    return <span aria-hidden className="absolute -inset-[3px] rounded-full ring-2 ring-emerald-400/50" />
  }
  return (
    <motion.span
      aria-hidden
      initial={{ scale: 1, opacity: 0.65 }}
      animate={{ scale: 1.14, opacity: 0.18 }}
      transition={{ ...spring.gentle, repeat: Infinity, repeatType: 'reverse' }}
      className="absolute -inset-[3px] rounded-full ring-2 ring-emerald-400/60 shadow-[0_0_14px_rgba(16,185,129,0.35)]"
    />
  )
}

export const ConversationRow = memo(function ConversationRow({
  id,
  isGroup,
  name,
  time,
  preview,
  previewPrefix,
  previewDeleted,
  draft,
  unreadCount,
  dmName,
  dmColor,
  groupTitle,
  online,
  pinned,
  muted,
  typing,
  archived,
  entranceIndex,
  onPress,
  onLongPress,
  onPin,
  onArchive,
}: ConversationRowProps) {
  const hasUnread = unreadCount > 0
  const reducedMotion = useReducedMotion()
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const longPressFiredRef = useRef(false)
  /** true between dragStart and the click that follows release — swallows the click */
  const draggedRef = useRef(false)
  const [swipeOpen, setSwipeOpen] = useState(false)
  const entrance = entranceIndex !== null && !reducedMotion

  const clearLongPress = useCallback(() => {
    if (longPressRef.current !== null) {
      clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }, [])

  const startLongPress = useCallback(() => {
    draggedRef.current = false
    clearLongPress()
    longPressFiredRef.current = false
    longPressRef.current = setTimeout(() => {
      longPressFiredRef.current = true
      longPressRef.current = null
      haptic(15)
      onLongPress()
    }, LONG_PRESS_MS)
  }, [clearLongPress, onLongPress])

  const handleClick = useCallback(() => {
    if (draggedRef.current) {
      draggedRef.current = false
      return
    }
    if (swipeOpen) {
      setSwipeOpen(false)
      return
    }
    if (!longPressFiredRef.current) onPress()
    longPressFiredRef.current = false
  }, [onPress, swipeOpen])

  const handleDragStart = useCallback(() => {
    draggedRef.current = true
    clearLongPress()
  }, [clearLongPress])

  const handleDragEnd = useCallback(
    (_event: unknown, info: PanInfo) => {
      const from = swipeOpen ? -SWIPE_REVEAL_PX : 0
      setSwipeOpen(from + info.offset.x <= -SWIPE_OPEN_THRESHOLD_PX)
    },
    [swipeOpen],
  )

  return (
    <motion.div
      initial={entrance ? { opacity: 0, y: 14 } : false}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: 0.32,
        ease: ease.out,
        delay: entrance ? stagger(entranceIndex ?? 0, 0.028, 12) : 0,
      }}
      className="group relative overflow-hidden px-2"
    >
      <div className="relative">
        {/* swipe-left glass action chips — the same pin/archive handlers the option menu uses */}
        <div className="absolute inset-y-1 right-2 z-0 flex items-center gap-1.5 pr-1" inert={!swipeOpen}>
          <motion.button
            type="button"
            tabIndex={swipeOpen ? 0 : -1}
            aria-hidden={!swipeOpen}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            onClick={() => {
              setSwipeOpen(false)
              onPin()
            }}
            aria-label={pinned ? `Unpin ${name}` : `Pin ${name}`}
            className="flex size-12 flex-col items-center justify-center gap-0.5 rounded-2xl bg-white/70 shadow-sm outline-none ring-1 ring-white/10 backdrop-blur-xl dark:bg-zinc-900/60 dark:ring-white/10"
          >
            {pinned ? (
              <PinOff className="size-[18px] text-amber-500" aria-hidden />
            ) : (
              <Pin className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
            )}
            <span className="text-[9px] font-semibold text-zinc-500 dark:text-zinc-400">
              {pinned ? 'Unpin' : 'Pin'}
            </span>
          </motion.button>
          <motion.button
            type="button"
            tabIndex={swipeOpen ? 0 : -1}
            aria-hidden={!swipeOpen}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            onClick={() => {
              setSwipeOpen(false)
              onArchive()
            }}
            aria-label={archived ? `Unarchive ${name}` : `Archive ${name}`}
            className="flex size-12 flex-col items-center justify-center gap-0.5 rounded-2xl bg-white/70 shadow-sm outline-none ring-1 ring-white/10 backdrop-blur-xl dark:bg-zinc-900/60 dark:ring-white/10"
          >
            {archived ? (
              <ArchiveRestore className="size-[18px] text-amber-500" aria-hidden />
            ) : (
              <Archive className="size-[18px] text-zinc-500 dark:text-zinc-400" aria-hidden />
            )}
            <span className="text-[9px] font-semibold text-zinc-500 dark:text-zinc-400">
              {archived ? 'Unarchive' : 'Archive'}
            </span>
          </motion.button>
        </div>

        {/* swipeable row body — x-drag with direction lock so vertical scroll never fights */}
        <motion.div
          drag="x"
          dragDirectionLock
          dragConstraints={{ left: -SWIPE_REVEAL_PX, right: 0 }}
          dragElastic={0.05}
          dragMomentum={false}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
          animate={{ x: swipeOpen ? -SWIPE_REVEAL_PX : 0 }}
          transition={spring.snappy}
          whileTap={reducedMotion ? undefined : { scale: 0.975 }}
          style={{ willChange: 'transform' }}
          className="relative z-10"
        >
          <button
            type="button"
            onClick={handleClick}
            onPointerDown={startLongPress}
            onPointerUp={clearLongPress}
            onPointerLeave={clearLongPress}
            onContextMenu={(e) => e.preventDefault()}
            className="relative flex w-full touch-manipulation items-center gap-3 overflow-hidden rounded-2xl bg-white px-2 py-2.5 text-left outline-none dark:bg-zinc-900"
          >
            {pinned ? (
              <span aria-hidden className="pointer-events-none absolute inset-0 rounded-2xl bg-emerald-500/[0.045] dark:bg-emerald-500/[0.06]" />
            ) : null}
            <span
              aria-hidden
              className="pointer-events-none absolute inset-0 rounded-2xl bg-zinc-900/[0.04] opacity-0 transition-opacity duration-100 group-active:opacity-100 dark:bg-white/5"
            />
            <span className="relative shrink-0">
              {!isGroup && online ? <PresenceGlow reduced={reducedMotion === true} /> : null}
              {isGroup ? (
                <GroupAvatar title={groupTitle} id={id} size={48} />
              ) : (
                <UserAvatar name={dmName ?? name} color={dmColor} size={48} showPresence online={online} />
              )}
            </span>

            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-2">
                <span className="flex min-w-0 items-center gap-1">
                  {pinned ? (
                    <Pin className="size-3 shrink-0 fill-emerald-500 text-emerald-500" aria-label="Pinned" />
                  ) : null}
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
                </span>
                <motion.span
                  key={time}
                  initial={reducedMotion ? false : { opacity: 0, scale: 0.7 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={spring.bouncy}
                  className={cn(
                    'shrink-0 text-[11px]',
                    hasUnread
                      ? 'font-semibold text-emerald-600 dark:text-emerald-400'
                      : 'text-zinc-400 dark:text-zinc-500',
                  )}
                >
                  {time}
                </motion.span>
              </div>
              <div className="mt-0.5 flex items-center justify-between gap-2">
                {typing ? (
                  <p className="flex min-w-0 items-center gap-1.5 text-[13px] font-medium italic text-emerald-600 dark:text-emerald-400">
                    <span className="inline-flex items-center gap-0.5" aria-hidden>
                      {[0, 1, 2].map((i) => (
                        <motion.span
                          key={i}
                          animate={{ y: [0, -2.5, 0], opacity: [0.45, 1, 0.45] }}
                          transition={{ repeat: Infinity, duration: 0.9, delay: i * 0.15, ease: 'easeInOut' }}
                          className="size-[3.5px] rounded-full bg-emerald-500"
                        />
                      ))}
                    </span>
                    typing…
                  </p>
                ) : draft ? (
                  <p className="flex min-w-0 items-center gap-1 truncate text-[13px]">
                    <PencilLine className="size-3 shrink-0 text-amber-500" aria-hidden />
                    <span className="shrink-0 font-semibold text-amber-600 dark:text-amber-400">Draft:</span>
                    <span className="truncate italic text-zinc-500 dark:text-zinc-400">{draft}</span>
                  </p>
                ) : (
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
                )}
                {hasUnread && !muted ? (
                  <motion.span
                    key={unreadCount}
                    initial={reducedMotion ? false : { scale: 0 }}
                    animate={{ scale: 1 }}
                    transition={spring.bouncy}
                    className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-emerald-500 px-1.5 text-[10px] font-bold text-white shadow-sm shadow-emerald-600/40 ring-2 ring-white dark:ring-zinc-900"
                  >
                    {unreadCount > 99 ? '99+' : unreadCount}
                  </motion.span>
                ) : muted ? (
                  <motion.span
                    key={unreadCount}
                    initial={reducedMotion ? false : { scale: 0.6, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    transition={spring.bouncy}
                    aria-label={hasUnread ? `Muted — ${unreadCount} unread` : 'Muted'}
                    className={cn(
                      'flex h-[18px] shrink-0 items-center gap-1 rounded-full px-1.5 text-[10px] font-bold ring-2 ring-white dark:ring-zinc-900',
                      hasUnread
                        ? 'bg-zinc-300 text-zinc-600 dark:bg-zinc-700 dark:text-zinc-300'
                        : 'bg-transparent text-zinc-400 ring-0 dark:text-zinc-500',
                    )}
                  >
                    <BellOff className="size-3" aria-hidden />
                    {/* R27-e: explicit "Muted" hint next to the glyph (count wins when unread) */}
                    {hasUnread ? (
                      <span>{unreadCount > 99 ? '99+' : unreadCount}</span>
                    ) : (
                      <span className="font-semibold tracking-wide">Muted</span>
                    )}
                  </motion.span>
                ) : null}
              </div>
            </div>
          </button>
          {/* overflow options — kept for accessibility (screen readers + keyboard) */}
          <button
            type="button"
            aria-label={`Options for ${name}`}
            onClick={(e) => {
              e.stopPropagation()
              onLongPress()
            }}
            className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full bg-white/90 p-1.5 text-zinc-400 opacity-0 shadow-sm outline-none backdrop-blur transition-opacity hover:text-zinc-600 focus-visible:opacity-100 group-hover:opacity-100 dark:bg-zinc-800/90 dark:hover:text-zinc-200"
          >
            <MoreVertical className="size-4" aria-hidden />
          </button>
          <div aria-hidden className="ml-[64px] h-px bg-zinc-100 dark:bg-zinc-800" />
        </motion.div>
      </div>
    </motion.div>
  )
})

// ─────────────────────────────────────────────────────────────
// Pulse — conversation list row (R27-e: extracted from chats-tab
// so the chats list AND the #/chats/archived sub-page share one
// row implementation — same swipe chips, same presence, same motion).
// R34-a shapes/motion pass:
//   • Discord 2026 shapes — group/channel THING avatars wear the
//     .pulse-squircle mask (people stay circular).
//   • Snapchat streak heat ring — a LIVE (not at-risk) myStreak ≥ 2
//     paints .streak-ring around the peer avatar (heat 1/2/3).
//   • Telegram multi-select — selectMode turns the row into a real
//     checkbox (check-circle over the avatar, press toggles, drag off).
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback, useRef, useState } from 'react'
import { motion, useReducedMotion, type PanInfo } from 'framer-motion'
import { Archive, ArchiveRestore, BellOff, Check, Flame, Hourglass, MoreVertical, PencilLine, Pin, PinOff } from 'lucide-react'
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
  /** R31-a: viewer's LIVE chat-streak count for this row (0/undefined = no chip) */
  streakCount?: number
  /** R33-b: live-but-dies-tonight streak (lastDay = yesterday UTC, count >= 2)
   *  — replaces the plain flame chip with the amber "ends tonight" nudge */
  streakAtRisk?: { count: number; lastDay: string } | null
  /** R37: honestly lost streak (lastDay older than yesterday UTC, count >= 2)
   *  — muted rose chip; only shown when NO live streak and NO at-risk chip */
  streakLost?: { count: number; best: number; lastDay: string } | null
  /** R33-b: channel/group photo path — circular image above the palette tile */
  photo?: string | null
  /** row lives in the archived sub-page (swipe chip flips to Unarchive) */
  archived: boolean
  /** R34-a: multi-select mode — the row becomes a checkbox (press toggles) */
  selectMode?: boolean
  /** R34-a: checked state inside multi-select mode */
  selected?: boolean
  /** R34-a: toggle this row's selection (select mode press) */
  onToggleSelect?: () => void
  /** R34-a: explicit handler for the ⋮ overflow button — defaults to
   *  onLongPress so legacy call sites keep their exact behavior */
  onOptions?: () => void
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

/**
 * R34-a — Snapchat heat level for a LIVE streak: 2-4 → 1 (warm),
 * 5-9 → 2 (hot), 10+ → 3 (blazing). At-risk streaks ring nothing
 * (they keep their amber "ends tonight" chip instead).
 */
export function streakHeatLevel(count: number): 1 | 2 | 3 {
  return count >= 10 ? 3 : count >= 5 ? 2 : 1
}

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
  streakCount = 0,
  streakAtRisk = null,
  streakLost = null,
  photo = null,
  archived,
  selectMode = false,
  selected = false,
  onToggleSelect,
  onOptions,
  entranceIndex,
  onPress,
  onLongPress,
  onPin,
  onArchive,
}: ConversationRowProps) {
  const hasUnread = unreadCount > 0
  const reducedMotion = useReducedMotion()
  /** R34-a: heat ring level for a live peer streak (null = no ring) */
  const streakHeat =
    !isGroup && streakAtRisk === null && streakCount >= 2 ? streakHeatLevel(streakCount) : null
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
    if (selectMode) return // long-press is inert while multi-select owns the list
    draggedRef.current = false
    clearLongPress()
    longPressFiredRef.current = false
    longPressRef.current = setTimeout(() => {
      longPressFiredRef.current = true
      longPressRef.current = null
      haptic(15)
      onLongPress()
    }, LONG_PRESS_MS)
  }, [clearLongPress, onLongPress, selectMode])

  const handleClick = useCallback(() => {
    if (draggedRef.current) {
      draggedRef.current = false
      return
    }
    if (selectMode) {
      if (!longPressFiredRef.current) {
        haptic(8)
        onToggleSelect?.()
      }
      longPressFiredRef.current = false
      return
    }
    if (swipeOpen) {
      setSwipeOpen(false)
      return
    }
    if (!longPressFiredRef.current) onPress()
    longPressFiredRef.current = false
  }, [onPress, onToggleSelect, selectMode, swipeOpen])

  const handleDragStart = useCallback(() => {
    draggedRef.current = true
    clearLongPress()
  }, [clearLongPress])

  const handleDragEnd = useCallback(
    (_event: unknown, info: PanInfo) => {
      if (selectMode) return // swipe reveal disabled while multi-select is up
      const from = swipeOpen ? -SWIPE_REVEAL_PX : 0
      setSwipeOpen(from + info.offset.x <= -SWIPE_OPEN_THRESHOLD_PX)
    },
    [selectMode, swipeOpen],
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
        {/* swipe-left glass action chips — the same pin/archive handlers the option menu uses.
            R43: visibility+opacity gated on swipeOpen — the chips sat permanently painted
            behind the translucent row, ghosting "Pin/Archive" through the glass (R42 nit). */}
        <div
          className={`absolute inset-y-1 right-2 z-0 flex items-center gap-1.5 pr-1 transition-opacity duration-150 ${
            swipeOpen && !selectMode ? 'visible opacity-100' : 'invisible opacity-0'
          }`}
          inert={!swipeOpen || selectMode}
        >
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
          drag={selectMode ? false : 'x'}
          dragDirectionLock
          dragConstraints={{ left: -SWIPE_REVEAL_PX, right: 0 }}
          dragElastic={0.05}
          dragMomentum={false}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
          animate={{ x: !selectMode && swipeOpen ? -SWIPE_REVEAL_PX : 0, scale: selectMode ? 0.985 : 1 }}
          transition={spring.snappy}
          whileTap={reducedMotion ? undefined : { scale: selectMode ? 0.96 : 0.975 }}
          style={{ willChange: 'transform' }}
          className="relative z-10"
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
            className="relative flex w-full touch-manipulation items-center gap-3 overflow-hidden rounded-2xl bg-white/80 px-2 py-2.5 text-left outline-none ring-1 ring-inset ring-white/40 dark:bg-zinc-900/70 dark:ring-white/[0.06]"
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
                // R34-a shapes system: groups/channels are THINGS → squircle mask
                // (people keep their circles). Mask clips the tile to the
                // superellipse silhouette at the exact avatar size.
                <span className="pulse-squircle block" style={{ width: 48, height: 48 }}>
                  <GroupAvatar title={groupTitle} id={id} size={48} photo={photo} />
                </span>
              ) : (
                <span
                  className={cn('block', streakHeat !== null && 'streak-ring')}
                  data-heat={streakHeat ?? undefined}
                >
                  <UserAvatar name={dmName ?? name} color={dmColor} size={48} showPresence online={online} />
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
                        ? 'bg-emerald-500 ring-white/70 dark:ring-white/25'
                        : 'bg-zinc-900/35 ring-white/60 dark:bg-zinc-950/50 dark:ring-white/30',
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
                <span className="flex shrink-0 items-center gap-1.5">
                  {/* R33-b: streak-at-risk nudge — a 2+ day chain whose lastDay is
                      yesterday dies at tonight's UTC midnight; amber glass chip
                      replaces the plain flame count until a message revives it.
                      R37: a chain whose lastDay already passed is honestly LOST —
                      muted rose chip, strictly last in priority (myStreak >
                      deadStreak > lostStreak), never shown beside the others. */}
                  {streakAtRisk ? (
                    <span
                      aria-label={`${streakAtRisk.count}-day streak ends tonight — send a message to keep it`}
                      className="flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-bold text-amber-600 ring-1 ring-amber-500/25 dark:text-amber-400"
                    >
                      <Hourglass className="size-3" aria-hidden />
                      ends tonight
                    </span>
                  ) : streakCount > 0 ? (
                    <span
                      aria-label={`${streakCount}-day streak`}
                      className="flex items-center gap-0.5 rounded-full bg-amber-500/10 px-1.5 py-0.5 text-[10px] font-bold text-amber-600 ring-1 ring-amber-500/20 dark:text-amber-400"
                    >
                      <Flame className="size-3" aria-hidden />
                      {streakCount}
                    </span>
                  ) : streakLost ? (
                    <span
                      aria-label={`${streakLost.count}-day streak lost`}
                      className="flex items-center gap-1 rounded-full bg-rose-500/[0.07] px-2 py-0.5 text-[10px] font-bold text-rose-400 ring-1 ring-rose-500/20 dark:text-rose-400/80"
                    >
                      <Flame className="size-3" aria-hidden />
                      streak lost
                    </span>
                  ) : null}
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
                </span>
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
          {/* overflow options — kept for accessibility (screen readers + keyboard);
              hidden while multi-select owns the list (the bar replaces it) */}
          {!selectMode ? (
          <button
            type="button"
            aria-label={`Options for ${name}`}
            onClick={(e) => {
              e.stopPropagation()
              ;(onOptions ?? onLongPress)()
            }}
            className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full bg-white/90 p-1.5 text-zinc-400 opacity-0 shadow-sm outline-none backdrop-blur transition-opacity hover:text-zinc-600 focus-visible:opacity-100 group-hover:opacity-100 dark:bg-zinc-800/90 dark:hover:text-zinc-200"
          >
            <MoreVertical className="size-4" aria-hidden />
          </button>
          ) : null}
          <div aria-hidden className="ml-[64px] h-px bg-zinc-100 dark:bg-zinc-800" />
        </motion.div>
      </div>
    </motion.div>
  )
})

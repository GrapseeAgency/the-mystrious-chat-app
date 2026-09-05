// ─────────────────────────────────────────────────────────────
// Pulse — #/chats/archived sub-page (R27-e).
// A real hash-routed glass sub-page INSIDE the chats tab: lists
// the viewer's archived conversations (my archivedAt watermark),
// rows carry the same swipe chips (unarchive) + long-press menu
// as the main list, empty state when nothing is archived.
// Open = navigateHash('/chats/archived') · Back = backHash().
// ─────────────────────────────────────────────────────────────
'use client'

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Archive, ArrowLeft } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { ConversationRow, type ConversationRowData } from '@/components/chat/chats-row'
import { RowSkeleton } from '@/components/chat/chats-skeleton'

export interface ChatsArchivedPageProps {
  /** reactive: hash path === '/chats/archived' */
  open: boolean
  me: AppUser
  /** archived rows from the REAL conversations query (archivedAt !== null) */
  rows: Array<{ conv: ConversationSummary; props: ConversationRowData }>
  loading: boolean
  /** play the one-shot entrance stagger (first list render of the session) */
  entrance: boolean
  onBack: () => void
  onPress: (conv: ConversationSummary) => void
  onLongPress: (conv: ConversationSummary) => void
  onPin: (conv: ConversationSummary) => void
  onArchive: (conv: ConversationSummary) => void
}

export function ChatsArchivedPage({
  open,
  me,
  rows,
  loading,
  entrance,
  onBack,
  onPress,
  onLongPress,
  onPin,
  onArchive,
}: ChatsArchivedPageProps) {
  const reducedMotion = useReducedMotion()
  const count = rows.length

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          key="chats-archived-page"
          initial={reducedMotion ? false : { opacity: 0, x: '7%' }}
          animate={{ opacity: 1, x: 0 }}
          exit={reducedMotion ? { opacity: 0 } : { opacity: 0, x: '7%' }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="absolute inset-0 z-30 flex flex-col bg-white dark:bg-zinc-900"
          role="region"
          aria-label="Archived chats"
        >
          {/* frosted sub-page header — same glass recipe as the settings sections */}
          <header className="glass-deep glass-sheen shrink-0 border-b border-zinc-200/70 pt-[max(0px,env(safe-area-inset-top))] dark:border-white/10">
            <div className="flex items-center gap-2 px-3 py-2.5">
              <motion.button
                type="button"
                aria-label="Back to chats"
                onClick={() => {
                  haptic(8)
                  onBack()
                }}
                whileTap={reducedMotion ? undefined : { scale: 0.9 }}
                transition={spring.snappy}
                className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-colors hover:text-zinc-900 dark:text-zinc-300 dark:hover:text-white"
              >
                <ArrowLeft className="size-5" aria-hidden />
              </motion.button>
              <div className="min-w-0 flex-1">
                <h1 className="flex items-center gap-2 truncate text-[17px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                  Archived
                  <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-emerald-500/15 px-1.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                    {count > 99 ? '99+' : count}
                  </span>
                </h1>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  Muted here — a new message moves a chat back to your inbox
                </p>
              </div>
            </div>
          </header>

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
            {loading ? (
              <div role="status" aria-label="Loading archived chats" className="pt-2">
                <RowSkeleton />
                <RowSkeleton />
                <RowSkeleton />
              </div>
            ) : count === 0 ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center"
              >
                <span
                  aria-hidden
                  className="glass-deep flex size-16 items-center justify-center rounded-3xl"
                >
                  <Archive className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  No archived chats
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  Swipe left on a chat and tap Archive — it waits here. A new message brings it
                  straight back to your inbox.
                </p>
              </motion.div>
            ) : (
              <div className="py-1">
                {rows.map(({ conv, props }, i) => (
                  <ConversationRow
                    key={props.id}
                    {...props}
                    entranceIndex={entrance ? i : null}
                    onPress={() => onPress(conv)}
                    onLongPress={() => onLongPress(conv)}
                    onPin={() => onPin(conv)}
                    onArchive={() => onArchive(conv)}
                  />
                ))}
              </div>
            )}
          </div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

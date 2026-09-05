// ─────────────────────────────────────────────────────────────
// Pulse — compact glass pinned-messages sheet (R27-c).
// Opens from the slim pinned banner under the room header. A
// GlassMenu-based bottom panel (NOT a giant overlay): one hairline
// header, then pin rows with jump + unpin (any participant per the
// real POST /api/messages/[id]/pin toggle contract).
// ─────────────────────────────────────────────────────────────
'use client'

import type { CSSProperties } from 'react'
import { motion } from 'framer-motion'
import { ArrowDown, LoaderCircle, Pin, PinOff } from 'lucide-react'
import type { ChatMessage } from '@/lib/types'
import { formatListStamp } from '@/lib/pulse-utils'
import { spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { GlassMenu, GlassMenuLabel, GlassMenuSeparator } from '@/components/ui/glass-menu'
import { UserAvatar } from '@/components/chat/user-avatar'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

export interface RoomPinsSheetProps {
  onClose: () => void
  /** authoritative pinned list (GET /api/conversations/[id]/pinned) */
  pins: ChatMessage[]
  loading: boolean
  myId: string
  unpinPending: boolean
  /** close + scroll-to-and-flash the pinned message */
  onJump: (messageId: string) => void
  /** real unpin — POST /api/messages/[id]/pin toggle */
  onUnpin: (messageId: string) => void
}

/** One-line preview that survives empty text (photo / voice rows). */
function pinPreview(m: ChatMessage): string {
  const text = m.content.replace(/\s+/g, ' ').trim()
  if (text.length > 0) return text
  if (m.imagePath) return 'Photo'
  if (m.audioPath) return 'Voice note'
  return 'Message'
}

export function RoomPinsSheet({
  onClose,
  pins,
  loading,
  myId,
  unpinPending,
  onJump,
  onUnpin,
}: RoomPinsSheetProps) {
  return (
    <>
      {/* backdrop — tap anywhere outside to dismiss */}
      <motion.button
        type="button"
        aria-hidden
        tabIndex={-1}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.16 }}
        onClick={onClose}
        className="fixed inset-0 z-[62] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
      />
      <GlassMenu
        aria-label={
          pins.length === 1 ? '1 pinned message' : `${pins.length} pinned messages`
        }
        className={cn(
          'glass-menu-panel fixed bottom-[max(0.9rem,env(safe-area-inset-bottom))] left-1/2 z-[63] flex max-h-[56dvh] w-[min(420px,calc(100vw-16px))] translate-x-[-50%]',
          'flex-col rounded-2xl',
        )}
        style={{ '--menu-origin': 'bottom center' } as CSSProperties}
      >
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex items-center gap-1.5 px-2 pt-1">
            <span
              className="flex size-6 shrink-0 items-center justify-center rounded-full bg-emerald-500/10"
              aria-hidden
            >
              <Pin className="size-3 rotate-45 text-emerald-500" />
            </span>
            <GlassMenuLabel className="flex-1 px-1 pb-0 pt-1.5">
              {pins.length === 1 ? '1 pinned message' : `${pins.length} pinned messages`}
            </GlassMenuLabel>
          </div>
          <GlassMenuSeparator className="mb-0.5" />
          {loading ? (
            <div className="space-y-2 px-2 pb-2" role="status" aria-label="Loading pinned messages">
              <Skeleton className="h-14 w-full rounded-xl" />
              <Skeleton className="h-14 w-5/6 rounded-xl" />
            </div>
          ) : pins.length === 0 ? (
            <p className="px-4 pb-3 pt-1 text-center text-xs text-zinc-400 dark:text-zinc-500">
              Nothing pinned yet — long-press a message and choose Pin.
            </p>
          ) : (
            <ul className="pulse-scroll min-h-0 space-y-0.5 overflow-y-auto px-1 pb-1.5">
              {pins.map((m, idx) => (
                <motion.li
                  key={m.id}
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ ...spring.soft, delay: Math.min(idx * 0.03, 0.18) }}
                  className="glass-row-hover rounded-xl p-2"
                >
                  <div className="flex items-center gap-2">
                    <UserAvatar name={m.sender.name} color={m.sender.color} size={22} />
                    <span className="truncate text-xs font-semibold text-zinc-700 dark:text-zinc-200">
                      {m.sender.id === myId ? 'You' : m.sender.name}
                    </span>
                    <span className="ml-auto shrink-0 text-[10px] tabular-nums text-zinc-400 dark:text-zinc-500">
                      {formatListStamp(m.createdAt)}
                    </span>
                  </div>
                  <p className="mt-1 line-clamp-2 text-[13px] leading-snug break-words text-zinc-600 dark:text-zinc-300">
                    {pinPreview(m)}
                  </p>
                  <div className="mt-1.5 flex gap-1.5">
                    <button
                      type="button"
                      onClick={() => {
                        haptic(8)
                        onJump(m.id)
                      }}
                      className="flex h-7 items-center gap-1 rounded-full border border-zinc-900/[0.08] bg-white/60 px-3 text-[11px] font-semibold text-zinc-600 outline-none transition-transform hover:text-emerald-600 active:scale-95 dark:border-white/[0.1] dark:bg-white/[0.05] dark:text-zinc-300 dark:hover:text-emerald-400"
                    >
                      <ArrowDown className="size-3" aria-hidden />
                      Jump
                    </button>
                    <button
                      type="button"
                      disabled={unpinPending}
                      onClick={() => {
                        haptic(12)
                        onUnpin(m.id)
                      }}
                      className="flex h-7 items-center gap-1 rounded-full border border-zinc-900/[0.08] bg-white/60 px-3 text-[11px] font-semibold text-zinc-500 outline-none transition-transform hover:border-rose-400/50 hover:text-rose-600 active:scale-95 disabled:opacity-50 dark:border-white/[0.1] dark:bg-white/[0.05] dark:text-zinc-400 dark:hover:text-rose-400"
                    >
                      {unpinPending ? (
                        <LoaderCircle className="size-3 animate-spin" aria-hidden />
                      ) : (
                        <PinOff className="size-3" aria-hidden />
                      )}
                      Unpin
                    </button>
                  </div>
                </motion.li>
              ))}
            </ul>
          )}
        </div>
      </GlassMenu>
    </>
  )
}

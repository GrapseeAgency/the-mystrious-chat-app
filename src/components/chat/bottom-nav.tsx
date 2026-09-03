// ─────────────────────────────────────────────────────────────
// Pulse Chat — bottom navigation (Chats / Hub / Contacts / Profile)
// with a framer-motion sliding pill highlight + total unread.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo } from 'react'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { CircleUserRound, Flame, MessageCircle, Users } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'

export type PulseTab = 'chats' | 'hub' | 'contacts' | 'profile'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

const TABS: Array<{ id: PulseTab; label: string; Icon: typeof MessageCircle }> = [
  { id: 'chats', label: 'Chats', Icon: MessageCircle },
  { id: 'hub', label: 'Hub', Icon: Flame },
  { id: 'contacts', label: 'Contacts', Icon: Users },
  { id: 'profile', label: 'Profile', Icon: CircleUserRound },
]

function UnreadBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <motion.span
      initial={{ scale: 0 }}
      animate={{ scale: 1 }}
      transition={{ type: 'spring', stiffness: 500, damping: 18 }}
      className="absolute -top-1 left-1/2 ml-[9px] flex h-5 min-w-5 items-center justify-center rounded-full bg-emerald-500 px-1.5 text-[10px] font-bold text-white shadow"
    >
      {count > 99 ? '99+' : count}
    </motion.span>
  )
}

export const BottomNav = memo(function BottomNav({
  me,
  active,
  onChange,
}: {
  me: AppUser
  active: PulseTab
  onChange: (tab: PulseTab) => void
}) {
  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 25_000,
  })

  const totalUnread = (conversations.data ?? []).reduce((sum, c) => sum + c.unreadCount, 0)

  return (
    <nav
      aria-label="Main navigation"
      className="mt-auto shrink-0 border-t border-zinc-200 bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur dark:border-zinc-800 dark:bg-zinc-900/95"
    >
      <div className="grid grid-cols-4 px-3 py-1.5">
        {TABS.map(({ id, label, Icon }) => {
          const isActive = active === id
          return (
            <button
              key={id}
              type="button"
              onClick={() => onChange(id)}
              aria-current={isActive ? 'page' : undefined}
              className={cn(
                'relative mx-1 flex touch-manipulation flex-col items-center justify-center gap-0.5 rounded-full py-1.5 text-xs font-medium outline-none transition-colors active:scale-[0.97]',
                isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
              )}
            >
              {isActive ? (
                <motion.span
                  layoutId="pulse-nav-pill"
                  transition={{ type: 'spring', stiffness: 480, damping: 34 }}
                  className="absolute inset-x-2 top-0.5 bottom-0.5 rounded-full bg-emerald-500/10"
                />
              ) : null}
              <span className="relative">
                <Icon className="size-[22px]" strokeWidth={isActive ? 2.2 : 1.8} aria-hidden />
                {id === 'chats' ? (
                  <span className="pointer-events-none absolute -top-1 -right-3">
                    <UnreadBadge count={totalUnread} />
                  </span>
                ) : null}
              </span>
              <span className="relative">{label}</span>
            </button>
          )
        })}
      </div>
    </nav>
  )
})

// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared avatar component (people + groups).
// Gradient keyed by the user's chosen color; presence dot support.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo } from 'react'
import { UsersRound } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { cn } from '@/lib/utils'
import {
  gradientFor,
  groupGradientFor,
  initialsOf,
} from '@/lib/pulse-utils'

export interface UserAvatarProps {
  name: string
  color?: string
  /** diameter px */
  size?: number
  online?: boolean
  showPresence?: boolean
  className?: string
}

function formatDotSize(avatarPx: number): number {
  return Math.max(9, Math.round(avatarPx * 0.26))
}

export const UserAvatar = memo(function UserAvatar({
  name,
  color,
  size = 48,
  online = false,
  showPresence = false,
  className,
}: UserAvatarProps) {
  const gradient = gradientFor(color ?? 'emerald')
  const dot = formatDotSize(size)
  return (
    <div className={cn('relative shrink-0', className)} style={{ width: size, height: size }}>
      <Avatar className="size-full" style={{ width: size, height: size }}>
        <AvatarFallback
          className={cn(
            'bg-gradient-to-br font-semibold text-white select-none',
            gradient,
          )}
          style={{ fontSize: Math.max(11, Math.round(size * 0.36)) }}
        >
          {initialsOf(name)}
        </AvatarFallback>
      </Avatar>
      {showPresence ? (
        <span
          aria-label={online ? 'online' : 'offline'}
          className={cn(
            'absolute right-0 bottom-0 z-10 rounded-full ring-2 ring-white dark:ring-zinc-900 transition-colors',
            online ? 'bg-emerald-500' : 'bg-zinc-300 dark:bg-zinc-600',
          )}
          style={{ width: dot, height: dot }}
        />
      ) : null}
    </div>
  )
})

export interface GroupAvatarProps {
  title: string
  id: string
  size?: number
  className?: string
}

export const GroupAvatar = memo(function GroupAvatar({
  title,
  id,
  size = 48,
  className,
}: GroupAvatarProps) {
  return (
    <div
      aria-hidden
      className={cn(
        'flex shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br text-white shadow-sm',
        groupGradientFor(id),
        className,
      )}
      style={{ width: size, height: size, borderRadius: Math.max(10, size * 0.28) }}
    >
      {title.trim().length > 0 ? (
        <span
          className="font-semibold tracking-tight"
          style={{ fontSize: Math.max(11, Math.round(size * 0.34)) }}
        >
          {initialsOf(title)}
        </span>
      ) : (
        <UsersRound style={{ width: size * 0.5, height: size * 0.5 }} />
      )}
    </div>
  )
})

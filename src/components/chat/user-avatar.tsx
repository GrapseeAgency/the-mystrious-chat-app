// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared avatar component (people + groups).
// Gradient keyed by the user's chosen color; presence dot support.
// R27-d: optional `avatar` photo (path from /api/uploads) renders
// above the palette fallback; a subtle glass rim ring (shared
// --glass-rim token) gives photo + palette a specular edge.
// Backward compatible — callers that pass only name/color keep
// the palette glyph (photo simply absent → falsy avatar).
// R33-b: GroupAvatar gains the same optional `photo` (channels and
// groups) — a circular image above the gradient+initials fallback.
// Callers that never pass it keep the exact palette tile.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo } from 'react'
import { UsersRound } from 'lucide-react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { cn } from '@/lib/utils'
import {
  gradientFor,
  groupGradientFor,
  initialsOf,
} from '@/lib/pulse-utils'

export interface UserAvatarProps {
  name: string
  color?: string
  /** profile photo path ("/api/uploads/<uuid>.<ext>") — null/undefined = palette fallback */
  avatar?: string | null
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
  avatar,
  size = 48,
  online = false,
  showPresence = false,
  className,
}: UserAvatarProps) {
  const gradient = gradientFor(color ?? 'emerald')
  const dot = formatDotSize(size)
  const hasPhoto = typeof avatar === 'string' && avatar.length > 0
  return (
    <div className={cn('relative shrink-0', className)} style={{ width: size, height: size }}>
      <Avatar className="size-full" style={{ width: size, height: size }}>
        {hasPhoto ? (
          <AvatarImage
            src={avatar}
            alt={name}
            className="size-full rounded-full object-cover"
            // photos are immutable uploaded files, but never let a broken
            // path crash the surface — Radix re-shows the palette fallback
            referrerPolicy="no-referrer"
          />
        ) : null}
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
      {/* subtle glass rim — specular edge shared with the design language */}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-0 rounded-full"
        style={{ boxShadow: 'var(--glass-rim)' }}
      />
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
  /** R33-b: channel/group photo path ("/api/uploads/<file>") — null/undefined = palette tile */
  photo?: string | null
}

export const GroupAvatar = memo(function GroupAvatar({
  title,
  id,
  size = 48,
  className,
  photo,
}: GroupAvatarProps) {
  const hasPhoto = typeof photo === 'string' && photo.length > 0
  const gradient = groupGradientFor(id)
  const initials = title.trim().length > 0 ? initialsOf(title) : null
  if (hasPhoto) {
    // R33-b — circular photo with the SAME gradient+initials as fallback
    // (a broken path can never crash the surface: Radix re-shows the tile)
    return (
      <div className={cn('relative shrink-0', className)} style={{ width: size, height: size }}>
        <Avatar className="size-full" style={{ width: size, height: size }}>
          <AvatarImage
            src={photo}
            alt={title || 'Group'}
            className="size-full rounded-full object-cover"
            referrerPolicy="no-referrer"
          />
          <AvatarFallback
            className={cn(
              'flex items-center justify-center rounded-full bg-gradient-to-br font-semibold text-white select-none',
              gradient,
            )}
            style={{ fontSize: Math.max(11, Math.round(size * 0.34)) }}
          >
            {initials ?? <UsersRound style={{ width: size * 0.5, height: size * 0.5 }} />}
          </AvatarFallback>
        </Avatar>
        <span
          aria-hidden
          className="pointer-events-none absolute inset-0 rounded-full"
          style={{ boxShadow: 'var(--glass-rim)' }}
        />
      </div>
    )
  }
  return (
    <div
      aria-hidden
      className={cn(
        'flex shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br text-white shadow-sm',
        gradient,
        className,
      )}
      style={{ width: size, height: size, borderRadius: Math.max(10, size * 0.28) }}
    >
      {initials ? (
        <span
          className="font-semibold tracking-tight"
          style={{ fontSize: Math.max(11, Math.round(size * 0.34)) }}
        >
          {initials}
        </span>
      ) : (
        <UsersRound style={{ width: size * 0.5, height: size * 0.5 }} />
      )}
    </div>
  )
})

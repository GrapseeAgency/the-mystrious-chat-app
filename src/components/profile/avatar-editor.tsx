// ─────────────────────────────────────────────────────────────
// Pulse — profile photo editor (R27-d).
// Lives at the top of the #/profile/edit sub-page (owned by
// profile-tab.tsx). Real upload chain, zero mocks:
//   file → canvas center-crop square + ≤512px downscale →
//   JPEG(0.85) data URL → POST /api/uploads →
//   PATCH /api/users/[id] { avatar: "/api/uploads/<file>" } →
//   session store + ['me'] / ['users'] cache update + invalidate.
// "Remove photo" PATCHes { avatar: "" } (server nulls the column).
// Failures surface as sonner toasts and never leave a broken
// preview (local optimistic preview resets with the error).
// ─────────────────────────────────────────────────────────────
'use client'

import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { motion, useReducedMotion } from 'framer-motion'
import { Camera, LoaderCircle, UserRound, UserRoundX } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { apiJson, gradientFor } from '@/lib/pulse-utils'
import { usePulseSession } from '@/lib/pulse-store'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { pressSpring, pressTap, spring } from '@/lib/motion'
import { UserAvatar } from '@/components/chat/user-avatar'
import { ProfileSection } from '@/components/profile/profile-primitives'

const AVATAR_MAX_EDGE = 512
const AVATAR_JPEG_QUALITY = 0.85

/**
 * Center-crop the picked image to a square, downscale so the edge is
 * ≤512px (never upscale), and re-encode as a JPEG data URL.
 * PNG transparency is flattened onto white (JPEG has no alpha).
 */
async function avatarFileToDataUrl(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('That file is not an image')
  }
  const bitmapUrl = URL.createObjectURL(file)
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image()
      el.onload = () => resolve(el)
      el.onerror = () => reject(new Error('Could not read that image'))
      el.src = bitmapUrl
    })
    const side = Math.min(img.width, img.height)
    if (side < 1) throw new Error('That image is empty')
    const out = Math.min(side, AVATAR_MAX_EDGE)
    const canvas = document.createElement('canvas')
    canvas.width = out
    canvas.height = out
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas is unavailable here')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, out, out)
    ctx.drawImage(
      img,
      (img.width - side) / 2,
      (img.height - side) / 2,
      side,
      side,
      0,
      0,
      out,
      out,
    )
    return canvas.toDataURL('image/jpeg', AVATAR_JPEG_QUALITY)
  } finally {
    URL.revokeObjectURL(bitmapUrl)
  }
}

type PhotoPhase = 'idle' | 'optimizing' | 'uploading' | 'saving'

const PHASE_LABEL: Record<PhotoPhase, string> = {
  idle: '',
  optimizing: 'Optimizing photo…',
  uploading: 'Uploading…',
  saving: 'Saving…',
}

export function AvatarPhotoEditor({ me }: { me: AppUser }) {
  const reducedMotion = useReducedMotion()
  const setUser = usePulseSession((s) => s.setUser)
  const queryClient = useQueryClient()
  const inputRef = useRef<HTMLInputElement | null>(null)
  /** local compressed data URL shown while the network round-trip runs */
  const [pendingPreview, setPendingPreview] = useState<string | null>(null)
  const [phase, setPhase] = useState<PhotoPhase>('idle')
  const busy = phase !== 'idle'

  const previewSrc = pendingPreview ?? me.avatar

  /** shared cache write: session store + ['me'] / ['users'] snapshot + refetch */
  const applyUser = (user: AppUser) => {
    setUser(user)
    queryClient.setQueryData(['me', user.id], user)
    queryClient.setQueryData<AppUser[]>(['users'], (old) =>
      old?.map((u) => (u.id === user.id ? user : u)),
    )
    void queryClient.invalidateQueries({ queryKey: ['me', user.id] })
    void queryClient.invalidateQueries({ queryKey: ['users'] })
  }

  const runSetPhoto = async (file: File) => {
    if (busy) return
    haptic(10)
    setPendingPreview(null)
    try {
      setPhase('optimizing')
      const dataUrl = await avatarFileToDataUrl(file)
      setPendingPreview(dataUrl)

      setPhase('uploading')
      const up = await apiJson<{ filePath: string; imagePath: string }>('/api/uploads', {
        method: 'POST',
        body: JSON.stringify({ dataUrl }),
      })

      setPhase('saving')
      const res = await apiJson<{ user: AppUser }>(
        `/api/users/${encodeURIComponent(me.id)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ avatar: `/api/uploads/${up.filePath}` }),
        },
      )
      applyUser(res.user)
      toast.success('Profile photo updated')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not set your photo')
    } finally {
      setPhase('idle')
      setPendingPreview(null)
    }
  }

  const runRemovePhoto = async () => {
    if (busy) return
    haptic(10)
    setPhase('saving')
    try {
      const res = await apiJson<{ user: AppUser }>(
        `/api/users/${encodeURIComponent(me.id)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ avatar: '' }),
        },
      )
      applyUser(res.user)
      toast.success('Profile photo removed')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not remove your photo')
    } finally {
      setPhase('idle')
      setPendingPreview(null)
    }
  }

  const gradient = gradientFor(me.color)

  return (
    <ProfileSection title="Profile photo" className="mt-0">
      <div className="flex items-center gap-4 p-1.5" aria-busy={busy}>
        {/* circular preview — photo when set, palette glyph otherwise */}
        <motion.div
          key={previewSrc ?? 'palette'}
          initial={reducedMotion ? false : { scale: 0.85, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={spring.bouncy}
          className={cn(
            'relative shrink-0 rounded-full p-[3px] shadow-lg shadow-black/10',
            'bg-gradient-to-br',
            gradient,
          )}
        >
          <div className="rounded-full bg-white p-[2px] dark:bg-zinc-900/90">
            <UserAvatar
              name={me.name}
              color={me.color}
              avatar={previewSrc}
              size={88}
              className="rounded-full"
            />
          </div>
          {busy ? (
            <span className="absolute inset-0 z-10 flex items-center justify-center rounded-full bg-black/45 backdrop-blur-[2px]">
              <LoaderCircle className="size-6 animate-spin text-white" aria-hidden />
            </span>
          ) : null}
        </motion.div>

        <div className="flex min-w-0 flex-1 flex-col items-start gap-2">
          <motion.button
            type="button"
            onClick={() => {
              if (busy) return
              inputRef.current?.click()
            }}
            whileTap={reducedMotion || busy ? undefined : pressTap}
            transition={pressSpring}
            disabled={busy}
            aria-label={me.avatar ? 'Replace profile photo' : 'Set profile photo'}
            className="glass-pill flex h-11 items-center gap-2 rounded-full px-4 text-[13px] font-bold text-[var(--ui-accent,#10b981)] outline-none disabled:opacity-60"
          >
            <Camera className="size-4" aria-hidden />
            {me.avatar ? 'Replace photo' : 'Set photo'}
          </motion.button>

          {me.avatar ? (
            <motion.button
              type="button"
              onClick={runRemovePhoto}
              whileTap={reducedMotion || busy ? undefined : pressTap}
              transition={pressSpring}
              disabled={busy}
              aria-label="Remove profile photo"
              className="glass-pill flex h-11 items-center gap-2 rounded-full px-4 text-[13px] font-bold text-zinc-500 outline-none hover:text-destructive disabled:opacity-60 dark:text-zinc-300"
            >
              <UserRoundX className="size-4" aria-hidden />
              Remove photo
            </motion.button>
          ) : (
            <p className="flex items-center gap-1.5 px-1 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
              <UserRound className="size-3.5" aria-hidden />
              Shows everywhere — chats, lists and your profile
            </p>
          )}

          <p aria-live="polite" className="min-h-[16px] px-1 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
            {busy ? PHASE_LABEL[phase] : 'Square crop · compressed on this device'}
          </p>
        </div>
      </div>

      {/* hidden picker — JPEG/PNG/WebP only, handled by the real chain above */}
      <input
        ref={inputRef}
        id="avatar-photo-input"
        type="file"
        accept="image/*"
        disabled={busy}
        className="sr-only"
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = '' // allow re-picking the same file
          if (file) void runSetPhoto(file)
        }}
      />
    </ProfileSection>
  )
}

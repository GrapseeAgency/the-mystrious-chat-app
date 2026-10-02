// Pulse - profile cover editor (R50-b).
// Lives on the #/profile/edit sub-page (owned by profile-tab.tsx),
// right under the avatar editor. Same real upload chain, zero mocks:
//   file → canvas cover-crop 2:1 + ≤1280px wide downscale →
//   JPEG(0.85) data URL → POST /api/uploads →
//   PATCH /api/users/[id] { cover: "/api/uploads/<file>" } →
//   session store + ['me'] / ['users'] cache update + invalidate.
// "Remove cover" PATCHes { cover: "" } (server nulls the column).
// Failures surface as sonner toasts and never leave a broken
// preview (local optimistic preview resets with the error).
'use client'

import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { motion, useReducedMotion } from 'framer-motion'
import { ImageUp, LoaderCircle, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { apiJson, gradientFor } from '@/lib/pulse-utils'
import { usePulseSession } from '@/lib/pulse-store'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { pressTap, spring } from '@/lib/motion'
import { ProfileSection } from '@/components/profile/profile-primitives'

const COVER_MAX_W = 1280
const COVER_JPEG_QUALITY = 0.85

/**
 * Cover-crop the picked image to a 2:1 landscape band, downscale so the
 * width is ≤1280px (never upscale), and re-encode as a JPEG data URL.
 * PNG transparency is flattened onto the brand-zinc bottom tone.
 */
async function coverFileToDataUrl(file: File): Promise<string> {
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
    if (img.width < 1 || img.height < 1) throw new Error('That image is empty')
    // 2:1 target band, cropped around the vertical center (faces live high,
    // so the crop window biases slightly toward the top third).
    const targetRatio = 2
    let cropW = img.width
    let cropH = Math.round(img.width / targetRatio)
    if (cropH > img.height) {
      cropH = img.height
      cropW = Math.round(img.height * targetRatio)
    }
    const outW = Math.min(cropW, COVER_MAX_W)
    const outH = Math.max(1, Math.round((cropH / cropW) * outW))
    const canvas = document.createElement('canvas')
    canvas.width = outW
    canvas.height = outH
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas is unavailable here')
    ctx.fillStyle = '#18181b'
    ctx.fillRect(0, 0, outW, outH)
    const cropY = Math.round(Math.max(0, (img.height - cropH) * 0.35))
    ctx.drawImage(
      img,
      Math.round((img.width - cropW) / 2),
      cropY,
      cropW,
      cropH,
      0,
      0,
      outW,
      outH,
    )
    return canvas.toDataURL('image/jpeg', COVER_JPEG_QUALITY)
  } finally {
    URL.revokeObjectURL(bitmapUrl)
  }
}

type CoverPhase = 'idle' | 'optimizing' | 'uploading' | 'saving'

const PHASE_LABEL: Record<CoverPhase, string> = {
  idle: '',
  optimizing: 'Optimizing image…',
  uploading: 'Uploading…',
  saving: 'Saving…',
}

export function CoverPhotoEditor({ me }: { me: AppUser }) {
  const reducedMotion = useReducedMotion()
  const setUser = usePulseSession((s) => s.setUser)
  const queryClient = useQueryClient()
  const inputRef = useRef<HTMLInputElement | null>(null)
  /** local compressed data URL shown while the network round-trip runs */
  const [pendingPreview, setPendingPreview] = useState<string | null>(null)
  const [phase, setPhase] = useState<CoverPhase>('idle')
  const busy = phase !== 'idle'

  const previewSrc = pendingPreview ?? me.cover
  const gradient = gradientFor(me.color)

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

  const runSetCover = async (file: File) => {
    if (busy) return
    haptic(10)
    setPendingPreview(null)
    try {
      setPhase('optimizing')
      const dataUrl = await coverFileToDataUrl(file)
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
          body: JSON.stringify({ cover: `/api/uploads/${up.filePath}` }),
        },
      )
      applyUser(res.user)
      toast.success('Cover photo updated')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not set your cover')
    } finally {
      setPhase('idle')
      setPendingPreview(null)
    }
  }

  const runRemoveCover = async () => {
    if (busy) return
    haptic(10)
    setPhase('saving')
    try {
      const res = await apiJson<{ user: AppUser }>(
        `/api/users/${encodeURIComponent(me.id)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ cover: '' }),
        },
      )
      applyUser(res.user)
      toast.success('Cover photo removed')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not remove your cover')
    } finally {
      setPhase('idle')
      setPendingPreview(null)
    }
  }

  return (
    <ProfileSection title="Cover photo" className="mt-0">
      <div className="flex flex-col gap-3 p-1.5" aria-busy={busy}>
        {/* 2:1 band preview - photo when set, palette gradient otherwise */}
        <motion.div
          key={previewSrc ?? 'palette'}
          initial={reducedMotion ? false : { opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={spring.soft}
          className={cn(
            'relative aspect-[2/1] w-full overflow-hidden rounded-2xl shadow-lg shadow-black/10',
            !previewSrc && gradient,
          )}
        >
          {previewSrc ? (
            <img
              src={previewSrc}
              alt="Cover preview"
              className="absolute inset-0 size-full object-cover"
            />
          ) : (
            <span
              aria-hidden
              className="absolute inset-0 bg-[linear-gradient(180deg,rgba(255,255,255,0.16),transparent_60%)]"
            />
          )}
          {busy ? (
            <span className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-1.5 bg-black/45 backdrop-blur-[2px]">
              <LoaderCircle className="size-6 animate-spin text-white" aria-hidden />
              <span className="text-[11px] font-medium text-white">{PHASE_LABEL[phase]}</span>
            </span>
          ) : null}
        </motion.div>

        <div className="flex items-center gap-2">
          <motion.button
            type="button"
            onClick={() => {
              if (busy) return
              inputRef.current?.click()
            }}
            whileTap={reducedMotion ? undefined : pressTap}
            disabled={busy}
            className={cn(
              'inline-flex h-10 items-center gap-2 rounded-xl px-4 text-[13px] font-semibold',
              'bg-zinc-900 text-white dark:bg-white dark:text-zinc-900',
              'disabled:opacity-50',
            )}
          >
            <ImageUp className="size-4" aria-hidden />
            {me.cover ? 'Change cover' : 'Add cover photo'}
          </motion.button>
          {me.cover ? (
            <motion.button
              type="button"
              onClick={runRemoveCover}
              whileTap={reducedMotion ? undefined : pressTap}
              disabled={busy}
              className={cn(
                'inline-flex h-10 items-center gap-2 rounded-xl px-4 text-[13px] font-semibold',
                'border border-red-500/40 text-red-600 dark:text-red-400',
                'disabled:opacity-50',
              )}
            >
              <Trash2 className="size-4" aria-hidden />
              Remove
            </motion.button>
          ) : null}
        </div>

        <p className="text-[11px] leading-4 text-zinc-500 dark:text-zinc-400">
          Shown as the background of your profile. Cropped to a wide 2:1 band, long edge
          capped at 1280px.
        </p>

        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          className="hidden"
          aria-label="Pick a cover photo"
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = ''
            if (file) void runSetCover(file)
          }}
        />
      </div>
    </ProfileSection>
  )
}

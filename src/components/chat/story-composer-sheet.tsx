// ─────────────────────────────────────────────────────────────
// Pulse — Status story composer (WhatsApp/Telegram-style).
// Two modes: Text (caption + gradient background, live full-screen
// preview) and Photo (upload via /api/uploads + optional caption).
// Posts to POST /api/stories and invalidates ['stories', me.id].
// ─────────────────────────────────────────────────────────────
'use client'

import { useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { useQueryClient } from '@tanstack/react-query'
import { ImagePlus, LoaderCircle, Type, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { apiJson, compressImageToDataUrl, gradientFor, PULSE_COLORS } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { storiesQueryKey } from '@/components/chat/stories-sheet'

export const STORY_COMPOSER_CAPTION_MAX = 280

interface StoryComposerSheetProps {
  me: AppUser
  onClose: () => void
}

export function StoryComposerSheet({ me, onClose }: StoryComposerSheetProps) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<'text' | 'photo'>('text')
  const [caption, setCaption] = useState('')
  const [background, setBackground] = useState<string>('emerald')
  const [photo, setPhoto] = useState<{ imagePath: string; preview: string } | null>(null)
  const [uploading, setUploading] = useState(false)
  const [posting, setPosting] = useState(false)
  const fileRef = useRef<HTMLInputElement | null>(null)

  const canPost =
    !posting &&
    !uploading &&
    (mode === 'text' ? caption.trim().length > 0 : photo !== null)

  const pickPhoto = async (file: File | undefined) => {
    if (!file) return
    setUploading(true)
    try {
      const dataUrl = await compressImageToDataUrl(file)
      const up = await apiJson<{ imagePath: string }>('/api/uploads', {
        method: 'POST',
        body: JSON.stringify({ dataUrl }),
      })
      setPhoto({ imagePath: up.imagePath, preview: dataUrl })
    } catch {
      toast.error('Could not attach that photo')
    } finally {
      setUploading(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  const post = async () => {
    if (!canPost) return
    setPosting(true)
    try {
      await apiJson<{ story: unknown }>('/api/stories', {
        method: 'POST',
        body: JSON.stringify({
          requesterId: me.id,
          caption: caption.trim(),
          ...(mode === 'text' ? { background } : {}),
          ...(mode === 'photo' && photo ? { imagePath: photo.imagePath } : {}),
        }),
      })
      toast.success('Status posted')
      // ring row picks up the new story instantly
      void queryClient.invalidateQueries({ queryKey: storiesQueryKey(me.id) })
      onClose()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not post the status')
      setPosting(false)
    }
  }

  return (
    <motion.div
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={{ type: 'spring', stiffness: 320, damping: 34 }}
      className="absolute inset-0 z-[80] flex flex-col overflow-hidden"
      role="dialog"
      aria-modal="true"
      aria-label="New status"
    >
      {/* live preview stage — full-screen gradient (text) or photo */}
      {mode === 'text' ? (
        <div className={cn('absolute inset-0 bg-gradient-to-br', gradientFor(background))} aria-hidden />
      ) : (
        <div className="absolute inset-0 bg-zinc-950" aria-hidden />
      )}
      {mode === 'text' ? (
        <div className="pointer-events-none relative flex min-h-0 flex-1 items-center justify-center px-8">
          {caption.trim() ? (
            <p className="max-h-[52dvh] w-full overflow-y-auto whitespace-pre-wrap text-center text-[26px] font-bold leading-snug text-white [text-shadow:0_1px_14px_rgba(0,0,0,0.28)]">
              {caption}
            </p>
          ) : (
            <p className="text-center text-[22px] font-semibold text-white/60">
              Type your status…
            </p>
          )}
        </div>
      ) : (
        <div className="relative flex min-h-0 flex-1 items-center justify-center p-4">
          {photo ? (
            <div className="relative flex max-h-full items-center justify-center">
              <img
                src={photo.preview}
                alt="Status photo preview"
                className="max-h-full max-w-full rounded-2xl object-contain shadow-2xl shadow-black/60"
              />
              {caption.trim() ? (
                <p className="absolute inset-x-4 bottom-4 rounded-2xl bg-black/55 px-3.5 py-2 text-center text-[15px] font-medium leading-snug text-white backdrop-blur-sm">
                  <span className="line-clamp-3 whitespace-pre-wrap">{caption}</span>
                </p>
              ) : null}
            </div>
          ) : (
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              disabled={uploading}
              className="flex h-44 w-64 flex-col items-center justify-center gap-3 rounded-3xl border-2 border-dashed border-white/25 text-white/70 outline-none transition-colors hover:border-emerald-400/60 hover:text-emerald-300 active:scale-[0.98] disabled:opacity-60"
            >
              {uploading ? (
                <>
                  <LoaderCircle className="size-7 animate-spin" aria-hidden />
                  <span className="text-sm font-medium">Uploading…</span>
                </>
              ) : (
                <>
                  <ImagePlus className="size-8" aria-hidden />
                  <span className="text-sm font-semibold">Choose a photo</span>
                  <span className="text-xs text-white/50">It disappears after 24 hours</span>
                </>
              )}
            </button>
          )}
        </div>
      )}

      {/* top bar */}
      <div className="absolute inset-x-0 top-0 z-10 flex items-center gap-2 bg-gradient-to-b from-black/45 to-transparent px-3 pb-8 pt-[max(0.6rem,env(safe-area-inset-top))]">
        <button
          type="button"
          aria-label="Discard status"
          onClick={onClose}
          className="flex size-10 items-center justify-center rounded-full text-white/90 outline-none transition-transform hover:bg-white/10 active:scale-90"
        >
          <X className="size-5" aria-hidden />
        </button>
        <h2 className="text-[15px] font-bold tracking-tight text-white">New status</h2>
        <button
          type="button"
          onClick={post}
          disabled={!canPost}
          className="ml-auto flex h-9 items-center rounded-full bg-white px-4 text-[13px] font-bold text-zinc-900 shadow-lg shadow-black/25 outline-none transition-all hover:bg-emerald-50 active:scale-95 disabled:opacity-40"
        >
          {posting ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Post'}
        </button>
      </div>

      {/* control panel */}
      <div className="relative z-10 shrink-0 rounded-t-3xl bg-zinc-900/95 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur-xl">
        <input
          ref={fileRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={(e) => pickPhoto(e.target.files?.[0])}
          aria-label="Choose a photo from your device"
        />

        {/* mode toggle */}
        <div className="mx-auto mb-3 flex w-fit gap-1 rounded-full bg-white/10 p-1" role="tablist" aria-label="Status type">
          {([
            { key: 'text', label: 'Text', icon: Type },
            { key: 'photo', label: 'Photo', icon: ImagePlus },
          ] as const).map(({ key, label, icon: Icon }) => {
            const active = mode === key
            return (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => {
                  setMode(key)
                  if (key === 'photo' && !photo) fileRef.current?.click()
                }}
                className={cn(
                  'flex h-8 items-center gap-1.5 rounded-full px-4 text-[13px] font-semibold outline-none transition-all active:scale-95',
                  active ? 'bg-emerald-500 text-white shadow-sm' : 'text-zinc-300 hover:text-white',
                )}
              >
                <Icon className="size-3.5" aria-hidden />
                {label}
              </button>
            )
          })}
        </div>

        {mode === 'text' ? (
          <>
            {/* gradient swatches */}
            <div className="mb-2.5 flex items-center justify-center gap-2.5 px-4" role="radiogroup" aria-label="Background gradient">
              {PULSE_COLORS.map((color) => {
                const active = background === color
                return (
                  <motion.button
                    key={color}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    aria-label={`${color} background`}
                    onClick={() => setBackground(color)}
                    whileTap={{ scale: 0.82 }}
                    animate={{ scale: active ? 1.18 : 1 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 24 }}
                    className={cn(
                      'size-7 rounded-full bg-gradient-to-br shadow-md outline-none',
                      gradientFor(color),
                      active && 'ring-2 ring-white ring-offset-2 ring-offset-zinc-900',
                    )}
                  />
                )
              })}
            </div>
            <div className="px-3">
              <textarea
                value={caption}
                onChange={(e) => setCaption(e.target.value.slice(0, STORY_COMPOSER_CAPTION_MAX))}
                rows={2}
                maxLength={STORY_COMPOSER_CAPTION_MAX}
                placeholder="What's happening?"
                aria-label="Status text"
                className="w-full resize-none rounded-2xl border border-white/10 bg-white/10 px-4 py-3 text-[15px] text-white placeholder:text-white/40 outline-none focus:border-emerald-400/60 focus:ring-2 focus:ring-emerald-500/30"
              />
              <p className="mt-1 text-right text-[10px] font-medium tabular-nums text-white/40">
                {caption.length}/{STORY_COMPOSER_CAPTION_MAX}
              </p>
            </div>
          </>
        ) : (
          <div className="flex items-end gap-2 px-3">
            <div className="flex-1">
              <input
                value={caption}
                onChange={(e) => setCaption(e.target.value.slice(0, STORY_COMPOSER_CAPTION_MAX))}
                maxLength={STORY_COMPOSER_CAPTION_MAX}
                placeholder={photo ? 'Add a caption… (optional)' : 'Choose a photo first'}
                aria-label="Photo caption"
                disabled={!photo}
                className="h-11 w-full rounded-2xl border border-white/10 bg-white/10 px-4 text-[15px] text-white placeholder:text-white/40 outline-none focus:border-emerald-400/60 focus:ring-2 focus:ring-emerald-500/30 disabled:opacity-50"
              />
              <p className="mt-1 text-right text-[10px] font-medium tabular-nums text-white/40">
                {caption.length}/{STORY_COMPOSER_CAPTION_MAX}
              </p>
            </div>
            {photo ? (
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                disabled={uploading}
                className="flex h-11 shrink-0 items-center gap-1.5 rounded-2xl bg-white/10 px-3.5 text-[13px] font-semibold text-white outline-none transition-colors hover:bg-white/15 active:scale-95 disabled:opacity-50"
              >
                {uploading ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <ImagePlus className="size-4" aria-hidden />
                )}
                Change
              </button>
            ) : null}
          </div>
        )}
      </div>
    </motion.div>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — per-conversation chat theme picker (R29-a).
// Inline glass panel rendered INSIDE the room info page: wallpaper
// swatch tiles (same preview visuals as the global Appearance picker,
// see WALLPAPER_META in conv-theme.ts) + optional tint chips +
// "Reset to default" (clears the override, falls back to the global).
//
// Writes go through the app's normal prefs persistence path:
// usePrefs().save() → optimistic store → debounced PATCH /api/settings
// → User.preferences JSON blob under `chat.convThemes`.
// ─────────────────────────────────────────────────────────────
'use client'

import { motion } from 'framer-motion'
import { Ban, Check, RotateCcw } from 'lucide-react'
import {
  CONV_TINTS,
  CONV_TINT_META,
  WALLPAPER_META,
  clearConvTheme,
  getConvTheme,
  setConvTheme,
} from '@/lib/conv-theme'
import type { PulsePrefs } from '@/lib/prefs-defaults'
import { usePrefs, usePrefsValues } from '@/lib/prefs'
import { haptic } from '@/lib/pulse-settings'
import { spring } from '@/lib/motion'
import { cn } from '@/lib/utils'

/**
 * Per-conversation theme editor.
 *  - Wallpaper tiles show the EFFECTIVE wallpaper (override ?? global)
 *    as selected, with a dot marking a tile that is a live override.
 *  - Tint chips layer an accent over the wallpaper glows.
 *  - "Reset to default" only renders when an override exists (honest:
 *    nothing to reset otherwise) and removes the whole entry.
 */
export function ConvThemePicker({
  conversationId,
  reducedMotion = false,
}: {
  conversationId: string
  reducedMotion?: boolean
}) {
  const prefs = usePrefsValues()
  const save = usePrefs((s) => s.save)

  const override = getConvTheme(prefs, conversationId)
  const effectiveWallpaper = override?.wallpaper ?? prefs.wallpaper
  const globalLabel = WALLPAPER_META.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'

  /** Push a conv-theme change through the shared prefs persistence path. */
  const commit = (nextPrefs: PulsePrefs) => {
    const map = nextPrefs['chat.convThemes']
    if (!map) return
    haptic(8)
    save({ 'chat.convThemes': map })
  }

  return (
    <div className="space-y-3 px-2 pt-1 pb-1.5">
      {/* wallpaper swatches — same tile visuals as Settings → Appearance */}
      <div>
        <p className="px-1 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
          Wallpaper
        </p>
        <div className="grid grid-cols-5 gap-2" role="radiogroup" aria-label="Wallpaper for this chat">
          {WALLPAPER_META.map((w, i) => {
            const selected = effectiveWallpaper === w.id
            const isOverride = override?.wallpaper === w.id
            return (
              <motion.button
                key={w.id}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={`${w.label} wallpaper`}
                onClick={() => commit(setConvTheme(prefs, conversationId, { wallpaper: w.id }))}
                initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring.soft, delay: Math.min(i * 0.03, 0.15) }}
                className="flex min-h-[44px] flex-col items-center gap-1.5 rounded-xl p-1 outline-none transition-transform active:scale-95 focus-visible:ring-2 focus-visible:ring-emerald-500/60"
              >
                <span
                  className={cn(
                    'relative block aspect-square w-full rounded-lg border shadow-sm',
                    w.preview,
                    selected
                      ? 'border-emerald-500 ring-2 ring-emerald-500/60'
                      : 'border-zinc-200/80 dark:border-white/10',
                  )}
                >
                  {selected ? (
                    <span className="absolute inset-0 flex items-center justify-center">
                      <Check className="size-4 text-emerald-600 drop-shadow dark:text-emerald-300" aria-hidden />
                    </span>
                  ) : null}
                  {isOverride ? (
                    <span
                      aria-label="Custom for this chat"
                      className="absolute top-1 right-1 size-1.5 rounded-full bg-emerald-500 shadow"
                    />
                  ) : null}
                </span>
                <span
                  className={cn(
                    'text-[10px] font-semibold',
                    selected ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                  )}
                >
                  {w.label}
                </span>
              </motion.button>
            )
          })}
        </div>
        <p className="px-1 pt-1.5 text-[11px] text-zinc-400 dark:text-zinc-500">
          {override
            ? 'Custom for this chat only'
            : `Following Appearance default · ${globalLabel}`}
        </p>
      </div>

      {/* tint chips — per-conversation accent over the wallpaper glows */}
      <div>
        <p className="px-1 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
          Tint
        </p>
        <div className="flex flex-wrap items-center gap-2" role="radiogroup" aria-label="Tint for this chat">
          <motion.button
            type="button"
            role="radio"
            aria-checked={!override?.tint}
            aria-label="No tint"
            onClick={() => commit(setConvTheme(prefs, conversationId, { tint: null }))}
            whileTap={{ scale: 0.9 }}
            className={cn(
              'flex size-11 items-center justify-center rounded-full border outline-none',
              override?.tint
                ? 'border-zinc-200/80 dark:border-white/10'
                : 'border-emerald-500 ring-2 ring-emerald-500/60',
            )}
          >
            <Ban className="size-4 text-zinc-400" aria-hidden />
          </motion.button>
          {CONV_TINTS.map((t) => {
            const selected = override?.tint === t
            return (
              <motion.button
                key={t}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={`${CONV_TINT_META[t].label} tint`}
                onClick={() => commit(setConvTheme(prefs, conversationId, { tint: t }))}
                whileTap={{ scale: 0.9 }}
                className={cn(
                  'flex size-11 items-center justify-center rounded-full border bg-white/60 outline-none dark:bg-white/5',
                  selected
                    ? 'border-emerald-500 ring-2 ring-emerald-500/60'
                    : 'border-zinc-200/80 dark:border-white/10',
                )}
              >
                <span className={cn('size-6 rounded-full shadow-sm', CONV_TINT_META[t].swatch)} />
              </motion.button>
            )
          })}
        </div>
      </div>

      {/* reset — only when an override exists; falls back to the global default */}
      {override ? (
        <motion.button
          type="button"
          onClick={() => commit(clearConvTheme(prefs, conversationId))}
          whileTap={{ scale: 0.98 }}
          className="glass-pill flex h-11 w-full items-center justify-center gap-2 text-[13px] font-bold text-zinc-600 outline-none dark:text-zinc-300"
        >
          <RotateCcw className="size-4" aria-hidden />
          Reset to default
        </motion.button>
      ) : null}
    </div>
  )
}

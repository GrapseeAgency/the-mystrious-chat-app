// ─────────────────────────────────────────────────────────────
// Pulse — UI language picker (R25-b).
// Renders the five locked design languages from UI_THEMES as live
// preview rows: selecting one calls useUiTheme().setTheme(id),
// which flips `data-ui` on the shell root and re-skins the entire
// app (accent vars cascade into every surface, including this card).
// ─────────────────────────────────────────────────────────────
'use client'

import { motion, useReducedMotion } from 'framer-motion'
import { Check } from 'lucide-react'
import { UI_THEMES, useUiTheme, type UiThemeId } from '@/lib/ui-theme'
import { cn } from '@/lib/utils'
import { pressSpring, pressTap, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'

export function UiLanguagePicker() {
  const [theme, setTheme] = useUiTheme()
  const reducedMotion = useReducedMotion()

  return (
    <div role="radiogroup" aria-label="UI language" className="flex flex-col gap-1.5 p-1">
      {UI_THEMES.map((t, i) => {
        const active = theme === t.id
        return (
          <motion.button
            key={t.id}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => {
              if (!active) {
                haptic(10)
                setTheme(t.id as UiThemeId)
              }
            }}
            initial={reducedMotion ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...spring.soft, delay: 0.03 * i }}
            whileTap={reducedMotion ? undefined : pressTap}
            className={cn(
              'flex min-h-[64px] w-full items-center gap-3 rounded-2xl border px-3 py-2 text-left outline-none transition-colors',
              active
                ? 'border-[var(--ui-accent,#10b981)]/60 bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_9%,transparent)]'
                : 'border-zinc-200/80 hover:border-zinc-300 dark:border-white/10 dark:hover:border-white/20',
            )}
          >
            {/* live swatch — the two accent colors this language will paint with */}
            <span
              aria-hidden
              className={cn(
                'relative flex size-11 shrink-0 items-center justify-center overflow-hidden rounded-xl shadow-inner ring-1 ring-black/5 dark:ring-white/10',
              )}
              style={{ background: `linear-gradient(135deg, ${t.swatch[0]}, ${t.swatch[1]})` }}
            >
              <span className="absolute bottom-1 left-1 size-3 rounded-full bg-white/70" />
              <span className="absolute right-1 top-1 size-2 rounded-full bg-black/25" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-1.5">
                <span className="truncate text-sm font-bold text-zinc-800 dark:text-zinc-100">{t.label}</span>
                <span className="rounded-full bg-zinc-900/5 px-1.5 py-px text-[9px] font-bold uppercase tracking-wide text-zinc-500 dark:bg-white/10 dark:text-zinc-400">
                  {t.motion}
                </span>
              </span>
              <span className="mt-0.5 block truncate text-[11px] leading-snug text-zinc-500 dark:text-zinc-400">
                {t.detail}
              </span>
            </span>
            {/* animated active check — layoutId makes it slide between rows */}
            {active ? (
              <motion.span
                layoutId="ui-lang-active-check"
                transition={pressSpring}
                className="flex size-6 shrink-0 items-center justify-center rounded-full bg-[var(--ui-accent,#10b981)]"
                aria-hidden
              >
                <Check className="size-3.5 text-white" strokeWidth={3} />
              </motion.span>
            ) : (
              <span className="size-6 shrink-0 rounded-full border border-dashed border-zinc-300 dark:border-zinc-600" aria-hidden />
            )}
          </motion.button>
        )
      })}
    </div>
  )
}

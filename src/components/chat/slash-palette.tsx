// ─────────────────────────────────────────────────────────────
// Pulse — slash-command palette (Discord/Slack/Notion-grade).
// Anchored above the composer whenever the draft starts with '/'.
// Fuzzy filtering, arrow-key + Enter navigation, Esc to dismiss,
// tap to select. The legacy plain-text parser in chat-room keeps
// working — the palette is a fast-path on top of it.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  Armchair,
  CalendarClock,
  CircleHelp,
  Dices,
  MapPin,
  PartyPopper,
  PenLine,
  Radio,
  RotateCcw,
  Sparkles,
  Sticker,
  UserRound,
  Vote,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'

export interface SlashCommandDef {
  cmd: string
  args: string
  help: string
  icon: LucideIcon
  tone: string
}

export const PULSE_SLASH_COMMANDS: readonly SlashCommandDef[] = [
  { cmd: '/me', args: '<action>', help: 'Send an italic action line', icon: UserRound, tone: 'text-emerald-500' },
  { cmd: '/shrug', args: '[text]', help: 'Append ¯\\_(ツ)_/¯', icon: PenLine, tone: 'text-teal-500' },
  { cmd: '/tableflip', args: '[text]', help: 'Append (╯°□°）╯︵ ┻━┻', icon: Armchair, tone: 'text-rose-500' },
  { cmd: '/unflip', args: '[text]', help: 'Prefix ┬─┬ ノ( ゜-゜ノ', icon: RotateCcw, tone: 'text-amber-500' },
  { cmd: '/roll', args: '[AdM]', help: 'Roll dice, e.g. /roll 2d6', icon: Dices, tone: 'text-violet-500' },
  { cmd: '/poll', args: '', help: 'Open the live-poll builder', icon: Vote, tone: 'text-violet-500' },
  { cmd: '/schedule', args: '', help: 'Schedule this message for later', icon: CalendarClock, tone: 'text-amber-500' },
  { cmd: '/sticker', args: '', help: 'Open the sticker packs', icon: Sticker, tone: 'text-emerald-500' },
  { cmd: '/location', args: '', help: 'Share a live map pin', icon: MapPin, tone: 'text-teal-500' },
  { cmd: '/effects confetti', args: '[text]', help: 'Send with a confetti blast', icon: PartyPopper, tone: 'text-rose-500' },
  { cmd: '/effects lasers', args: '[text]', help: 'Send with sweeping laser beams', icon: Zap, tone: 'text-amber-500' },
  { cmd: '/effects echo', args: '[text]', help: 'Send with expanding echo rings', icon: Radio, tone: 'text-emerald-500' },
  { cmd: '/effects sparkles', args: '[text]', help: 'Send with twinkling sparkles', icon: Sparkles, tone: 'text-violet-500' },
  { cmd: '/help', args: '', help: 'Show every command', icon: CircleHelp, tone: 'text-zinc-400' },
]

/**
 * Lightweight fuzzy match: true when every character of `needle`
 * appears in `haystack` in order (case-insensitive). '/ef co' → /effects confetti ✓
 */
function fuzzyMatch(haystack: string, needle: string): boolean {
  const h = haystack.toLowerCase()
  const n = needle.toLowerCase().replace(/\s+/g, '')
  if (n.length === 0) return true
  let i = 0
  let gap = 0
  for (const char of n) {
    const idx = h.indexOf(char, i)
    if (idx < 0) return false
    gap += idx - i
    i = idx + 1
  }
  return gap <= n.length * 6 // tolerate sloppy typing, keep ranking sane
}

export function SlashPalette({
  open,
  query,
  commands = PULSE_SLASH_COMMANDS,
  onSelect,
  onDismiss,
}: {
  open: boolean
  /** the full composer draft (must start with '/') */
  query: string
  commands?: readonly SlashCommandDef[]
  onSelect: (cmd: string) => void
  onDismiss: () => void
}) {
  const matches = useMemo(() => {
    const needle = query.startsWith('/') ? query : ''
    if (needle.length === 0) return []
    return commands.filter((c) => fuzzyMatch(`${c.cmd} ${c.args} ${c.help}`, needle))
  }, [query, commands])

  const [index, setIndex] = useState(0)
  // highlight is DERIVED — filtering can shrink the list without an effect
  const activeIndex = matches.length === 0 ? 0 : Math.min(index, matches.length - 1)

  // keyboard capture happens at the window (capture phase) so it wins
  // over the composer textarea's own Enter-to-send / Escape handlers
  useEffect(() => {
    if (!open || matches.length === 0) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        event.stopPropagation()
        setIndex((prev) => (prev + 1) % matches.length)
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        event.stopPropagation()
        setIndex((prev) => (prev - 1 + matches.length) % matches.length)
      } else if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault()
        event.stopPropagation()
        onSelect(matches[activeIndex]?.cmd ?? matches[0].cmd)
      } else if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onDismiss()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [open, matches.length, activeIndex, onSelect, onDismiss])

  return (
    <AnimatePresence>
      {open && matches.length > 0 ? (
        <motion.div
          key="slash-palette"
          role="listbox"
          aria-label="Slash commands"
          initial={{ opacity: 0, y: 8, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 6, scale: 0.98 }}
          transition={{ type: 'spring', stiffness: 520, damping: 32 }}
          className="absolute bottom-full left-0 right-0 z-30 mb-2 overflow-hidden rounded-2xl border border-zinc-200 bg-white/95 shadow-xl shadow-zinc-900/10 backdrop-blur-md dark:border-zinc-700 dark:bg-zinc-800/95"
          style={{ willChange: 'transform, opacity' }}
        >
          <div className="pulse-scroll max-h-64 overflow-y-auto p-1.5">
            {matches.map((command, i) => {
              const Icon = command.icon
              return (
                <button
                  key={command.cmd}
                  type="button"
                  role="option"
                  aria-selected={i === activeIndex}
                  onMouseEnter={() => setIndex(i)}
                  onClick={() => onSelect(command.cmd)}
                  className={cn(
                    'flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left outline-none transition-colors',
                    i === activeIndex
                      ? 'bg-emerald-50 dark:bg-emerald-500/10'
                      : 'hover:bg-zinc-100 dark:hover:bg-zinc-700/60',
                  )}
                >
                  <span
                    className={cn(
                      'flex size-8 shrink-0 items-center justify-center rounded-xl bg-zinc-100 dark:bg-zinc-700/70',
                      command.tone,
                    )}
                  >
                    <Icon className="size-4" aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline gap-1.5">
                      <span className="truncate text-[13px] font-bold text-zinc-800 dark:text-zinc-100">
                        {command.cmd}
                      </span>
                      {command.args ? (
                        <span className="truncate text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                          {command.args}
                        </span>
                      ) : null}
                    </span>
                    <span className="block truncate text-[11px] text-zinc-500 dark:text-zinc-400">
                      {command.help}
                    </span>
                  </span>
                  {i === activeIndex ? (
                    <kbd className="shrink-0 rounded-md border border-zinc-200 bg-zinc-50 px-1.5 py-0.5 text-[9.5px] font-bold text-zinc-400 dark:border-zinc-600 dark:bg-zinc-700/60 dark:text-zinc-400">
                      ↵
                    </kbd>
                  ) : null}
                </button>
              )
            })}
          </div>
          <p className="border-t border-zinc-100 px-3 py-1.5 text-[9.5px] font-medium text-zinc-400 dark:border-zinc-700/70 dark:text-zinc-500">
            ↑↓ navigate · ↵ run · esc dismiss
          </p>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

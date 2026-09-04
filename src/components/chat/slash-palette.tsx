// ─────────────────────────────────────────────────────────────
// Pulse — slash-command palette (Discord/Slack/Notion-grade).
// Anchored above the composer whenever the draft starts with '/'.
// Fuzzy filtering, arrow-key + Enter navigation, Esc to dismiss,
// tap to select. The legacy plain-text parser in chat-room keeps
// working — the palette is a fast-path on top of it.
//
// /whiteboard (Task R21-c) is dispatched standalone: the palette
// fires `pulse:open-whiteboard` on window (or the onOpenWhiteboard
// prop when provided) because the sheet lives in chat-room, which
// wires it via useWhiteboardSheet() — see whiteboard-sheet.tsx.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  Armchair,
  CalendarClock,
  CalendarDays,
  CircleHelp,
  Dices,
  Gamepad2,
  Gift,
  Map as MapIcon,
  MapPin,
  MessagesSquare,
  PartyPopper,
  PenLine,
  Podcast,
  Presentation,
  Radio,
  RotateCcw,
  Sparkles,
  SquareKanban,
  Sticker,
  Trophy,
  UserRound,
  Vote,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { spring, ease, stagger } from '@/lib/motion'
import { glassSurface } from '@/components/ui/glass-card'

export interface SlashCommandDef {
  cmd: string
  args: string
  help: string
  icon: LucideIcon
  tone: string
}

/** Window event fired by the /whiteboard entry — chat-room listens via useWhiteboardSheet(). */
export const WHITEBOARD_OPEN_EVENT = 'pulse:open-whiteboard'

// ── R23: same standalone-dispatch pattern for the new beyond-chat tools.
// The sheets/cards live in chat-room which listens via their use*Sheet hooks;
// /game is special — chat-room POSTs /api/games (DM → peer, group → open). ──
export const REDPACKET_OPEN_EVENT = 'pulse:open-redpacket'
export const KANBAN_OPEN_EVENT = 'pulse:open-kanban'
export const EVENTS_OPEN_EVENT = 'pulse:open-events'
export const NEW_GAME_EVENT = 'pulse:new-game'

// ── R24-b: topics + stage/space/tournament (wave 3) ──
/** Fired after /topic creates a topic — detail carries the fresh TopicSummary. */
export const TOPIC_CREATED_EVENT = 'pulse:topic-created'
/** Fired by /stage — the stage-room sheet hook (crew R24) listens internally. */
export const STAGE_OPEN_EVENT = 'pulse:open-stage'
/** Fired by /space — the spatial-space sheet hook listens internally. */
export const SPACE_OPEN_EVENT = 'pulse:open-space'
/** Fired by /tournament (groups only) — the tournament sheet hook listens. */
export const TOURNAMENT_OPEN_EVENT = 'pulse:open-tournament'

/** Generic fire-and-forget dispatcher for the R23 sheet/game events. */
function dispatchPulseEvent(event: string, conversationId?: string | null): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(event, { detail: { conversationId: conversationId ?? null } }))
}

/**
 * Fire-and-forget trigger for the shared whiteboard. `conversationId` rides
 * along in event.detail when known; the ChatRoom-side listener uses its own
 * conversation id, so callers may omit it.
 */
export function dispatchOpenWhiteboard(conversationId?: string | null): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(
    new CustomEvent(WHITEBOARD_OPEN_EVENT, { detail: { conversationId: conversationId ?? null } }),
  )
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
  { cmd: '/whiteboard', args: '', help: 'Open the shared whiteboard', icon: Presentation, tone: 'text-emerald-500' },
  { cmd: '/redpacket', args: '', help: 'Send a red packet (coins)', icon: Gift, tone: 'text-rose-500' },
  { cmd: '/game', args: '', help: 'Start tic-tac-toe in this chat', icon: Gamepad2, tone: 'text-violet-500' },
  { cmd: '/kanban', args: '', help: 'Open the group board', icon: SquareKanban, tone: 'text-teal-500' },
  { cmd: '/events', args: '', help: 'Group events with RSVP', icon: CalendarDays, tone: 'text-amber-500' },
  { cmd: '/topic', args: '<name>', help: 'Create a topic and file here', icon: MessagesSquare, tone: 'text-emerald-500' },
  { cmd: '/stage', args: '', help: 'Open the live stage room', icon: Podcast, tone: 'text-teal-500' },
  { cmd: '/space', args: '', help: 'Open the spatial space', icon: MapIcon, tone: 'text-amber-500' },
  { cmd: '/tournament', args: '', help: 'Start a group tournament', icon: Trophy, tone: 'text-rose-500' },
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
  onOpenWhiteboard,
  conversationId,
}: {
  open: boolean
  /** the full composer draft (must start with '/') */
  query: string
  commands?: readonly SlashCommandDef[]
  onSelect: (cmd: string) => void
  onDismiss: () => void
  /** direct wiring option — overrides the pulse:open-whiteboard event */
  onOpenWhiteboard?: () => void
  /** rides along in the pulse:open-whiteboard event detail when known */
  conversationId?: string
}) {
  const matches = useMemo(() => {
    const needle = query.startsWith('/') ? query : ''
    if (needle.length === 0) return []
    return commands.filter((c) => fuzzyMatch(`${c.cmd} ${c.args} ${c.help}`, needle))
  }, [query, commands])

  const [index, setIndex] = useState(0)
  // highlight is DERIVED — filtering can shrink the list without an effect
  const activeIndex = matches.length === 0 ? 0 : Math.min(index, matches.length - 1)

  /**
   * Run a palette row. /whiteboard is special: chat-room's onSelect parser
   * doesn't know it, so the palette dispatches the standalone contract itself
   * (prop callback when wired, else the pulse:open-whiteboard window event).
   */
  const activate = useCallback(
    (cmd: string) => {
      if (cmd === '/whiteboard') {
        if (onOpenWhiteboard) {
          onOpenWhiteboard()
        } else {
          dispatchOpenWhiteboard(conversationId ?? null)
        }
        onDismiss()
        return
      }
      // R23 commands flow through onSelect so the host clears the composer
      // draft and dispatches the sheet/game events in one place.
      onSelect(cmd)
    },
    [onOpenWhiteboard, conversationId, onSelect, onDismiss],
  )

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
        activate(matches[activeIndex]?.cmd ?? matches[0].cmd)
      } else if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onDismiss()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [open, matches.length, activeIndex, activate, onDismiss])

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
          transition={spring.snappy}
          className={cn(
            glassSurface,
            'absolute bottom-full left-0 right-0 z-30 mb-2 overflow-hidden rounded-2xl',
          )}
          style={{ willChange: 'transform, opacity' }}
        >
          <div className="pulse-scroll max-h-64 overflow-y-auto p-1.5">
            {matches.map((command, i) => {
              const Icon = command.icon
              return (
                <motion.button
                  key={command.cmd}
                  type="button"
                  role="option"
                  aria-selected={i === activeIndex}
                  onMouseEnter={() => setIndex(i)}
                  onClick={() => activate(command.cmd)}
                  // shared-token entrance: 25ms stagger, swift-out + tactile press
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.28, ease: ease.out, delay: stagger(i, 0.025, 8) }}
                  whileTap={{ scale: 0.97 }}
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
                </motion.button>
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

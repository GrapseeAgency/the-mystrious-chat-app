// ─────────────────────────────────────────────────────────────
// Pulse — chat-list casual actions (R27-e).
// Shared pieces behind the chats list:
//   • fetchFullHistory()      — real paginated GET of a room's messages
//   • downloadTranscript()    — .txt Blob export of the real history
//   • ChatOptionsSheet        — compact frosted GlassMenu (pin/archive,
//                               mute-duration strip, export, clear chat)
// Clear chat only deletes MY OWN messages (DELETE /api/messages/[id]
// is sender-gated server-side) and the confirm copy says so honestly.
// Zero emojis — Lucide icons + the shared glass/motion language.
// ─────────────────────────────────────────────────────────────
'use client'

import { Fragment, useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  BellOff,
  BellRing,
  ChevronRight,
  Download,
  Eraser,
  LoaderCircle,
  Mail,
  MailOpen,
  Pin,
  PinOff,
  X,
} from 'lucide-react'
import type { AppUser, ChatMessage, ConversationSummary } from '@/lib/types'
import { apiJson, conversationDisplayName } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { pressSpring, pressTap, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import {
  GlassMenu,
  GlassMenuItem,
  GlassMenuLabel,
  GlassMenuSeparator,
  GlassMenuStrip,
} from '@/components/ui/glass-menu'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'

// ── real history: paginate the WHOLE room through the public API ──

const HISTORY_PAGE_SIZE = 500 // server cap (MESSAGES_MAX_LIMIT)
const HISTORY_MAX_PAGES = 24 // hard stop: 12k messages is plenty for an export

/**
 * Every message in the room (oldest → newest), tombstones included —
 * fetched page by page through GET /api/conversations/[id]/messages
 * with the `before` cursor the route documents.
 */
export async function fetchFullHistory(conversationId: string): Promise<ChatMessage[]> {
  const pages: ChatMessage[][] = []
  let before: string | null = null
  for (let page = 0; page < HISTORY_MAX_PAGES; page += 1) {
    const query = `limit=${HISTORY_PAGE_SIZE}${before ? `&before=${encodeURIComponent(before)}` : ''}`
    const res = await apiJson<{ messages: ChatMessage[]; hasMore: boolean; total: number }>(
      `/api/conversations/${encodeURIComponent(conversationId)}/messages?${query}`,
    )
    const window = res.messages
    if (window.length === 0) break
    pages.push(window)
    if (!res.hasMore) break
    before = window[0]?.createdAt ?? null
    if (!before) break
  }
  // pages were collected newest-window-first — flipping yields oldest → newest
  return pages.reverse().flat()
}

// ── transcript building + .txt download ──────────────────────

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** "2025-01-31 14:03" local-time stamp for transcript lines. */
function transcriptStamp(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 'unknown-time'
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** One honest transcript line body for a real ChatMessage. */
function transcriptBody(m: ChatMessage): string {
  if (m.deletedAt !== null) return '[message deleted]'
  const text = m.content.replace(/\s+/g, ' ').trim()
  if (text.length > 0) return text
  if (m.poll) return `[poll] ${m.poll.question}`.trim()
  if (m.imagePath) return '[photo]'
  if (m.audioPath) {
    return m.durationMs !== null ? `[voice note — ${Math.max(1, Math.round(m.durationMs / 1000))}s]` : '[voice note]'
  }
  if (m.kind === 'sticker') return '[sticker]'
  if (m.kind === 'location') return '[location]'
  return '[message]'
}

/** File-name-safe slug for the room title. */
function slugForRoom(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 32)
  return slug || 'chat'
}

/**
 * Build + download `pulse-<room>-<date>.txt`. Returns the file name for
 * the completion toast. Blob download — no server round-trip, no storage.
 */
export function downloadTranscript(
  conv: ConversationSummary,
  meId: string,
  messages: ChatMessage[],
): string {
  const title = conversationDisplayName(conv, meId)
  const exportedAt = new Date()
  const lines = [
    'Pulse — chat export',
    `Chat: ${title}`,
    `Exported: ${exportedAt.toLocaleString()}`,
    `Messages: ${messages.length}`,
    '──────────────────────',
    '',
    ...messages.map((m) => {
      const who = m.anon && m.anonAlias ? m.anonAlias : (m.sender?.name ?? 'Unknown')
      return `[${transcriptStamp(m.createdAt)}] ${who}: ${transcriptBody(m)}`
    }),
  ]
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  const fileName = `pulse-${slugForRoom(title)}-${exportedAt.getFullYear()}-${pad2(exportedAt.getMonth() + 1)}-${pad2(exportedAt.getDate())}.txt`
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 4000)
  return fileName
}

/** "Muted until …" copy shared by the row chip, the menu hint and the toast. */
export function muteLabel(mutedUntil: string | null): string {
  if (mutedUntil === null) return 'Not muted'
  return Date.parse(mutedUntil) - Date.now() > 20 * 365 * 24 * 3600 * 1000
    ? 'Always'
    : `Until ${new Date(mutedUntil).toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })}`
}

// ── the compact glass option sheet ───────────────────────────

export interface ChatOptionsSheetProps {
  /** the conversation under the long-press (null = closed) */
  conv: ConversationSummary | null
  me: AppUser
  pinPending: boolean
  archivePending: boolean
  mutePending: boolean
  clearPending: boolean
  exportPending: boolean
  /** R44: the viewer's mark-as-unread flag on THIS conversation */
  manualUnread: boolean
  /** R44: pending state of the mark-unread PATCH */
  markUnreadPending: boolean
  onPin: () => void
  onArchive: () => void
  onMute: (until: '8h' | '1w' | 'always') => void
  onUnmute: () => void
  onExport: () => void
  onClear: () => void
  /** R44: flip the mark-as-unread flag */
  onMarkUnread: () => void
  onClose: () => void
}

/** Mute presets mirrored 1:1 from PATCH /api/conversations/[id]/mute. */
const MUTE_PRESETS = [
  { until: '8h', label: '8 hours' },
  { until: '1w', label: '1 week' },
  { until: 'always', label: 'Always' },
] as const

export function ChatOptionsSheet({
  conv,
  me,
  pinPending,
  archivePending,
  mutePending,
  clearPending,
  exportPending,
  manualUnread,
  markUnreadPending,
  onPin,
  onArchive,
  onMute,
  onUnmute,
  onExport,
  onClear,
  onMarkUnread,
  onClose,
}: ChatOptionsSheetProps) {
  /** 'actions' = main rows · 'mute' = the compact duration strip */
  const [view, setView] = useState<'actions' | 'mute'>('actions')
  const [confirmClear, setConfirmClear] = useState(false)
  const muted = conv !== null && conv.mutedUntil !== null && Date.parse(conv.mutedUntil) > Date.now()
  const sheetMounted = conv !== null

  // reopening for another chat (or after any close) resets the sub-view
  useEffect(() => {
    setView('actions')
    setConfirmClear(false)
  }, [conv])

  // Escape closes the sheet (the AlertDialog keeps its own Escape handling)
  useEffect(() => {
    if (!sheetMounted || confirmClear) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [sheetMounted, confirmClear, onClose])

  return (
    <AnimatePresence>
      {conv !== null && !confirmClear ? (
        /* R44 fix: the fragment MUST carry a key — framer's AnimatePresence
           tracks its direct child by key, and a bare <> renders as an
           empty-keyed child that collides with itself on every open/close
           transition ("two children with the same key``"). */
        <Fragment key="chat-options">
          {/* backdrop — tap anywhere outside to dismiss */}
          <motion.div
            key="chat-options-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.16 }}
            onClick={onClose}
            aria-hidden
            className="fixed inset-0 z-[60] bg-zinc-950/25 backdrop-blur-[2px] dark:bg-black/45"
          />
          {/* bottom-anchored frosted panel — same anatomy as the message menu */}
          <div
            key="chat-options-anchor"
            className="pointer-events-none fixed inset-x-0 bottom-0 z-[61] flex justify-center px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
          >
            <GlassMenu
              aria-label="Chat actions"
              className="pointer-events-auto w-[280px] max-w-full"
              style={{ '--menu-origin': 'bottom center' } as React.CSSProperties}
            >
              <GlassMenuLabel className="truncate text-center">
                {conversationDisplayName(conv, me.id)}
              </GlassMenuLabel>

              {view === 'actions' ? (
                <motion.div
                  key="actions-view"
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={spring.soft}
                >
                  <GlassMenuItem
                    icon={conv.pinnedAt ? PinOff : Pin}
                    label={conv.pinnedAt ? 'Unpin from top' : 'Pin to top'}
                    disabled={pinPending}
                    onClick={onPin}
                  />
                  <GlassMenuItem
                    icon={conv.archivedAt !== null ? ArchiveRestore : Archive}
                    label={conv.archivedAt !== null ? 'Unarchive chat' : 'Archive chat'}
                    disabled={archivePending}
                    onClick={onArchive}
                  />
                  {/* R44 — mark as unread/read: badges MY row until the room
                      is opened again (the read route clears the flag). */}
                  <GlassMenuItem
                    icon={markUnreadPending ? LoaderCircle : manualUnread ? MailOpen : Mail}
                    label={manualUnread ? 'Mark as read' : 'Mark as unread'}
                    disabled={markUnreadPending}
                    onClick={onMarkUnread}
                  />
                  <GlassMenuSeparator />
                  {muted ? (
                    <>
                      <p className="flex items-center gap-1.5 px-3 pb-1 pt-0.5 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                        <BellOff className="size-3 shrink-0" aria-hidden />
                        Muted — {muteLabel(conv.mutedUntil)}
                      </p>
                      <GlassMenuItem icon={BellRing} label="Unmute notifications" disabled={mutePending} onClick={onUnmute} />
                    </>
                  ) : (
                    <GlassMenuItem
                      icon={BellOff}
                      label="Mute notifications"
                      disabled={mutePending}
                      onClick={() => {
                        haptic(6)
                        setView('mute')
                      }}
                      trailing={<ChevronRight className="size-3.5 text-zinc-400 dark:text-zinc-500" aria-hidden />}
                    />
                  )}
                  <GlassMenuSeparator />
                  <GlassMenuItem
                    icon={exportPending ? LoaderCircle : Download}
                    label="Export chat (.txt)"
                    disabled={exportPending}
                    onClick={onExport}
                  />
                  <GlassMenuItem
                    icon={Eraser}
                    label="Clear chat…"
                    destructive
                    disabled={clearPending}
                    onClick={() => setConfirmClear(true)}
                  />
                  <GlassMenuSeparator />
                  <GlassMenuItem icon={X} label="Close" onClick={onClose} />
                </motion.div>
              ) : (
                <motion.div
                  key="mute-view"
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={spring.soft}
                >
                  <GlassMenuStrip role="group" aria-label="Mute notifications for">
                    {MUTE_PRESETS.map((preset) => (
                      <motion.button
                        key={preset.until}
                        type="button"
                        role="menuitem"
                        disabled={mutePending}
                        whileTap={pressTap}
                        transition={pressSpring}
                        onClick={() => onMute(preset.until)}
                        className={cn(
                          'h-9 min-w-0 flex-1 rounded-full px-1 text-[12px] font-semibold outline-none transition-colors',
                          'text-zinc-700 hover:bg-emerald-500/15 hover:text-emerald-700 active:bg-emerald-500/20',
                          'dark:text-zinc-200 dark:hover:text-emerald-300 disabled:pointer-events-none disabled:opacity-40',
                        )}
                      >
                        {preset.label}
                      </motion.button>
                    ))}
                  </GlassMenuStrip>
                  <GlassMenuItem icon={ArrowLeft} label="Back" onClick={() => setView('actions')} />
                </motion.div>
              )}
            </GlassMenu>
          </div>
        </Fragment>
      ) : null}

      {/* destructive confirm — real copy: only MY messages are deletable */}
      <AlertDialog
        open={conv !== null && confirmClear}
        onOpenChange={(open) => {
          if (!open) setConfirmClear(false)
        }}
      >
        <AlertDialogContent className="max-w-[320px] rounded-3xl sm:max-w-[320px]">
          <AlertDialogHeader>
            <AlertDialogTitle>Clear this chat?</AlertDialogTitle>
            <AlertDialogDescription>
              Your messages will be deleted for everyone — messages from other people stay in the
              chat. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={onClear}
              disabled={clearPending}
              className="bg-rose-600 text-white hover:bg-rose-600/90 focus-visible:ring-rose-500/40"
            >
              Clear chat
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </AnimatePresence>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — group kanban board sheet (Task R23-c).
// A three-column board (todo / doing / done) over the chat: add
// cards with Enter, quick-move them forward/back, assign members.
// Cards persist as REAL KanbanCard rows and sync across every
// member by 1500ms polling (paused while the tab is hidden).
//
// Wiring contract for chat-room (lead): mount once per room —
//   const kanban = useKanbanSheet(conversationId, me.id, members, myRole)
//   ... {kanban.node}
// and the /kanban slash entry (or any surface) opens it via the
// `pulse:open-kanban` CustomEvent on window.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, LoaderCircle, Trash2, WifiOff, X } from 'lucide-react'
import { toast } from 'sonner'
import { ApiError, apiJson, gradientFor, initialsOf } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { spring, stagger } from '@/lib/motion'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

// ── event contract (slash-palette dispatches this; sheet obeys) ──

export const KANBAN_OPEN_EVENT = 'pulse:open-kanban'

// ── wire types (mirror of the REST contract) ─────────────────

export type KanbanColumnId = 'todo' | 'doing' | 'done'

export interface KanbanMember {
  id: string
  name: string
  color: string
}

export interface KanbanCardDTO {
  id: string
  conversationId: string
  title: string
  column: string
  position: number
  assigneeId: string | null
  assigneeName: string | null
  createdById: string | null
  createdByName: string | null
  createdAt: string
  updatedAt: string
}

interface KanbanGetResponse {
  cards: KanbanCardDTO[]
}

interface KanbanMutationResponse {
  card: KanbanCardDTO
}

// ── constants ────────────────────────────────────────────────

const POLL_MS = 1500
const COLUMN_ORDER: KanbanColumnId[] = ['todo', 'doing', 'done']

/** Server column strings are app-written, but stay defensive. */
function asColumn(value: string): KanbanColumnId {
  return value === 'doing' || value === 'done' ? value : 'todo'
}

/** Column accents: zinc / amber / emerald (header pills + done cards). */
const COLUMN_META: Record<
  KanbanColumnId,
  { label: string; dot: string; text: string; pill: string }
> = {
  todo: {
    label: 'To do',
    dot: 'bg-zinc-400',
    text: 'text-zinc-300',
    pill: 'border-zinc-400/20 bg-zinc-500/10',
  },
  doing: {
    label: 'Doing',
    dot: 'bg-amber-400',
    text: 'text-amber-300',
    pill: 'border-amber-400/25 bg-amber-500/10',
  },
  done: {
    label: 'Done',
    dot: 'bg-emerald-400',
    text: 'text-emerald-300',
    pill: 'border-emerald-400/25 bg-emerald-500/10',
  },
}

// ── component ────────────────────────────────────────────────

export function KanbanSheet({
  open,
  onClose,
  conversationId,
  me,
  members,
  myRole = 'member',
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  me: { id: string }
  members: Array<{ id: string; name: string; color: string }>
  /** my group role — admins may delete any card; defaults to 'member' */
  myRole?: 'admin' | 'member'
}) {
  const meId = me.id
  const reduce = useReducedMotion() ?? false
  const queryClient = useQueryClient()

  // UI state
  const [synced, setSynced] = useState(false)
  const [drafts, setDrafts] = useState<Record<KanbanColumnId, string>>({
    todo: '',
    doing: '',
    done: '',
  })
  /** card ids with a mutation in flight (buttons dim + lock) */
  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(new Set())
  const [addingIn, setAddingIn] = useState<KanbanColumnId | null>(null)

  const memberMap = useMemo(() => new Map(members.map((m) => [m.id, m])), [members])

  const markBusy = useCallback((id: string, on: boolean) => {
    setBusyIds((prev) => {
      const next = new Set(prev)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  }, [])

  // ── remote sync (1500ms poll while open; pauses when hidden) ──

  const kanbanQuery = useQuery({
    queryKey: ['kanban', conversationId],
    enabled: open && conversationId.length > 0 && meId.length > 0,
    refetchInterval: () =>
      typeof document !== 'undefined' && document.hidden ? false : POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
    retry: 1,
    queryFn: async (): Promise<KanbanGetResponse> => {
      const res = await apiJson<KanbanGetResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/kanban?userId=${encodeURIComponent(meId)}`,
      )
      setSynced(true)
      return res
    },
  })

  const cards = kanbanQuery.data?.cards ?? []

  const grouped = useMemo(() => {
    const g: Record<KanbanColumnId, KanbanCardDTO[]> = { todo: [], doing: [], done: [] }
    for (const card of cards) g[asColumn(card.column)].push(card)
    for (const col of COLUMN_ORDER) {
      g[col].sort(
        (a, b) =>
          a.position - b.position ||
          a.createdAt.localeCompare(b.createdAt) ||
          (a.id < b.id ? -1 : 1),
      )
    }
    return g
  }, [cards])

  // room switch → clear local drafts (fresh board on next poll)
  useEffect(() => {
    setDrafts({ todo: '', doing: '', done: '' })
    setBusyIds(new Set())
    setAddingIn(null)
    setSynced(false)
  }, [conversationId])

  const canDelete = useCallback(
    (card: KanbanCardDTO) => card.createdById === meId || myRole === 'admin',
    [meId, myRole],
  )

  const refresh = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['kanban', conversationId] }),
    [queryClient, conversationId],
  )

  // ── mutations ────────────────────────────────────────────────

  const addCard = useCallback(
    async (col: KanbanColumnId) => {
      const title = drafts[col].trim()
      if (!title || addingIn !== null) return
      setAddingIn(col)
      haptic(8)
      try {
        await apiJson<KanbanMutationResponse>(
          `/api/conversations/${encodeURIComponent(conversationId)}/kanban`,
          { method: 'POST', body: JSON.stringify({ userId: meId, title, column: col }) },
        )
        setDrafts((prev) => ({ ...prev, [col]: '' }))
        await refresh()
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Could not add the card.')
      } finally {
        setAddingIn(null)
      }
    },
    [drafts, addingIn, conversationId, meId, refresh],
  )

  const moveCard = useCallback(
    async (card: KanbanCardDTO, dir: 1 | -1) => {
      const from = COLUMN_ORDER.indexOf(asColumn(card.column))
      const to = from + dir
      if (to < 0 || to >= COLUMN_ORDER.length || busyIds.has(card.id)) return
      markBusy(card.id, true)
      haptic(8)
      try {
        // move-to-end semantics: the API assigns max+1 in the target column
        await apiJson<KanbanMutationResponse>(`/api/kanban/${encodeURIComponent(card.id)}`, {
          method: 'PATCH',
          body: JSON.stringify({ userId: meId, column: COLUMN_ORDER[to] }),
        })
        await refresh()
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Could not move the card.')
      } finally {
        markBusy(card.id, false)
      }
    },
    [busyIds, markBusy, meId, refresh],
  )

  const removeCard = useCallback(
    async (card: KanbanCardDTO) => {
      if (busyIds.has(card.id)) return
      markBusy(card.id, true)
      haptic(12)
      try {
        await apiJson<{ ok: boolean }>(
          `/api/kanban/${encodeURIComponent(card.id)}?userId=${encodeURIComponent(meId)}`,
          { method: 'DELETE' },
        )
        await refresh()
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Could not delete the card.')
      } finally {
        markBusy(card.id, false)
      }
    },
    [busyIds, markBusy, meId, refresh],
  )

  // ── motion (spring entrance stagger + layout moves) ─────────

  const cardVariants: Variants = {
    hidden: { opacity: 0, y: 10, scale: 0.94 },
    show: (i: number) => ({
      opacity: 1,
      y: 0,
      scale: 1,
      transition: {
        ...(reduce ? { duration: 0 } : spring.snappy),
        delay: reduce ? 0 : stagger(i, 0.05),
      },
    }),
    exit: { opacity: 0, scale: 0.9, transition: { duration: reduce ? 0 : 0.14 } },
  }

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent
        role="dialog"
        aria-label="Kanban board"
        className="mx-auto max-h-[92dvh] max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 pb-[max(env(safe-area-inset-bottom),12px)] [&>div:first-child]:bg-white/20 dark:border-white/10 dark:bg-zinc-950"
      >
        <DrawerTitle className="sr-only">Kanban board</DrawerTitle>
        <DrawerDescription className="sr-only">
          Add, move and delete team task cards — the board syncs to everyone in this chat
        </DrawerDescription>

        <motion.div
          initial={{ opacity: 0, y: 24, scale: 0.99 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="flex min-h-0 flex-col"
        >
          {/* header */}
          <header className="flex shrink-0 items-center gap-2.5 border-b border-white/10 px-4 pb-3 pt-1">
            <span
              aria-hidden
              className="flex size-9 shrink-0 items-center justify-center rounded-2xl bg-emerald-500/15 text-[15px]"
            >
              📋
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Board</p>
              <p className="flex items-center gap-1 text-[11px] text-zinc-500">
                {kanbanQuery.isError ? (
                  <>
                    <WifiOff className="size-3 text-rose-400" aria-hidden />
                    Reconnecting…
                  </>
                ) : !synced ? (
                  'Syncing…'
                ) : (
                  <>
                    {cards.length} {cards.length === 1 ? 'card' : 'cards'} · live
                  </>
                )}
              </p>
            </div>
            <button
              type="button"
              aria-label="Close board"
              onClick={() => {
                haptic(8)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 focus-visible:ring-emerald-500/50 active:scale-90"
            >
              <X className="size-4" aria-hidden />
            </button>
          </header>

          {/* board */}
          <div className="grid shrink-0 grid-cols-3 gap-2 px-3 pb-2 pt-3">
            {COLUMN_ORDER.map((col) => {
              const meta = COLUMN_META[col]
              return (
                <section key={col} aria-label={`${meta.label} column`} className="flex min-w-0 flex-col gap-1.5">
                  {/* tinted column header */}
                  <div
                    className={cn(
                      'flex shrink-0 items-center gap-1.5 rounded-lg border px-2 py-1.5',
                      meta.pill,
                    )}
                  >
                    <span className={cn('size-1.5 shrink-0 rounded-full', meta.dot)} aria-hidden />
                    <p className={cn('truncate text-[10px] font-bold uppercase tracking-wider', meta.text)}>
                      {meta.label}
                    </p>
                    <span className="ml-auto shrink-0 rounded-full bg-black/25 px-1.5 text-[10px] font-semibold tabular-nums text-zinc-400">
                      {grouped[col].length}
                    </span>
                  </div>

                  {/* cards */}
                  <div className="pulse-scroll max-h-64 min-h-[68px] overflow-y-auto px-0.5 py-0.5">
                    <div role="list" className="flex flex-col gap-1.5">
                      <AnimatePresence mode="popLayout" initial>
                        {grouped[col].map((card, i) => {
                          const done = asColumn(card.column) === 'done'
                          const busy = busyIds.has(card.id)
                          const assignee = card.assigneeId
                            ? memberMap.get(card.assigneeId)
                            : undefined
                          return (
                            <motion.article
                              key={card.id}
                              layout
                              custom={i}
                              variants={cardVariants}
                              initial="hidden"
                              animate="show"
                              exit="exit"
                              transition={{ layout: reduce ? { duration: 0 } : spring.snappy }}
                              style={{ willChange: 'transform' }}
                              role="listitem"
                              className={cn(
                                'rounded-xl border p-2',
                                done
                                  ? 'border-emerald-500/20 bg-emerald-500/[0.05]'
                                  : 'border-white/10 bg-white/[0.04]',
                                busy && 'opacity-60',
                              )}
                            >
                              <p
                                className={cn(
                                  'line-clamp-2 break-words text-[11.5px] font-medium leading-snug',
                                  done ? 'text-zinc-500 line-through' : 'text-zinc-100',
                                )}
                              >
                                {card.title}
                              </p>
                              <div className="mt-1.5 flex items-center justify-between gap-1">
                                {card.assigneeId ? (
                                  <span
                                    className="flex min-w-0 items-center gap-1"
                                    title={card.assigneeName ?? 'Assignee'}
                                  >
                                    <span
                                      aria-hidden
                                      className={cn(
                                        'flex size-4 shrink-0 items-center justify-center rounded-full bg-gradient-to-br text-[7px] font-bold text-white',
                                        gradientFor(assignee?.color ?? 'emerald'),
                                      )}
                                    >
                                      {initialsOf(assignee?.name ?? card.assigneeName ?? '?')}
                                    </span>
                                    <span className="truncate text-[10px] text-zinc-400">
                                      {assignee?.name ?? card.assigneeName ?? 'Unknown'}
                                    </span>
                                  </span>
                                ) : (
                                  <span />
                                )}
                                <div className="flex shrink-0 items-center gap-0.5">
                                  {asColumn(card.column) !== 'todo' ? (
                                    <button
                                      type="button"
                                      aria-label={`Move card back ${card.title}`}
                                      disabled={busy}
                                      onClick={() => void moveCard(card, -1)}
                                      className="flex size-6 items-center justify-center rounded-md text-zinc-500 outline-none transition-colors hover:bg-white/10 hover:text-zinc-200 focus-visible:ring-2 focus-visible:ring-emerald-500/50 disabled:opacity-40 active:scale-90"
                                    >
                                      <ArrowLeft className="size-3" aria-hidden />
                                    </button>
                                  ) : null}
                                  {asColumn(card.column) !== 'done' ? (
                                    <button
                                      type="button"
                                      aria-label={`Move card forward ${card.title}`}
                                      disabled={busy}
                                      onClick={() => void moveCard(card, 1)}
                                      className="flex size-6 items-center justify-center rounded-md text-zinc-500 outline-none transition-colors hover:bg-white/10 hover:text-zinc-200 focus-visible:ring-2 focus-visible:ring-emerald-500/50 disabled:opacity-40 active:scale-90"
                                    >
                                      <ArrowRight className="size-3" aria-hidden />
                                    </button>
                                  ) : null}
                                  {canDelete(card) ? (
                                    <button
                                      type="button"
                                      aria-label={`Delete card ${card.title}`}
                                      disabled={busy}
                                      onClick={() => void removeCard(card)}
                                      className="flex size-6 items-center justify-center rounded-md text-zinc-500 outline-none transition-colors hover:bg-rose-500/15 hover:text-rose-400 focus-visible:ring-2 focus-visible:ring-rose-500/50 disabled:opacity-40 active:scale-90"
                                    >
                                      <Trash2 className="size-3" aria-hidden />
                                    </button>
                                  ) : null}
                                </div>
                              </div>
                            </motion.article>
                          )
                        })}
                      </AnimatePresence>
                      {grouped[col].length === 0 ? (
                        <p className="px-1 py-3 text-center text-[10px] text-zinc-600">No cards</p>
                      ) : null}
                    </div>
                  </div>

                  {/* add-card input */}
                  <div className="relative shrink-0">
                    <input
                      value={drafts[col]}
                      onChange={(e) =>
                        setDrafts((prev) => ({ ...prev, [col]: e.target.value }))
                      }
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault()
                          void addCard(col)
                        }
                      }}
                      placeholder="Add card…"
                      aria-label={`Add card ${col}`}
                      maxLength={120}
                      disabled={addingIn !== null}
                      className="w-full rounded-lg border border-white/10 bg-white/[0.04] px-2 py-1.5 pr-6 text-[11px] text-zinc-100 outline-none transition-colors placeholder:text-zinc-600 focus:border-emerald-500/50 focus:bg-white/[0.06] disabled:opacity-50"
                    />
                    {addingIn === col ? (
                      <LoaderCircle
                        aria-hidden
                        className="absolute right-1.5 top-1/2 size-3 -translate-y-1/2 animate-spin text-emerald-400"
                      />
                    ) : null}
                  </div>
                </section>
              )
            })}
          </div>

          <p className="shrink-0 px-4 pb-3 pt-0.5 text-center text-[10px] text-zinc-600">
            Cards sync to everyone in this room within ~1.5s
          </p>
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

/**
 * Drop-in wiring for chat-room (lead, 2 lines):
 *   const kanban = useKanbanSheet(conversationId, me.id, members, myRole)
 *   ... {kanban.node}
 * Listens for the `pulse:open-kanban` CustomEvent dispatched by the
 * /kanban slash-palette entry. Members power the assignee chips.
 */
export function useKanbanSheet(
  conversationId: string,
  meId: string,
  members: KanbanMember[],
  myRole: 'admin' | 'member' = 'member',
) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(KANBAN_OPEN_EVENT, handler)
    return () => window.removeEventListener(KANBAN_OPEN_EVENT, handler)
  }, [])

  const node = (
    <KanbanSheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      me={{ id: meId }}
      members={members}
      myRole={myRole}
    />
  )

  return { open, setOpen, node }
}

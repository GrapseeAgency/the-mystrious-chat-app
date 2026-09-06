// ─────────────────────────────────────────────────────────────
// Pulse — R39 "Automations" (keyword-triggered auto-replies).
// The honest ManyChat/Landbot "bot flow" adaptation: an admin stores a
// trigger phrase + reply; the send path lands the reply as a real
// machine-sent message. This file carries the WHOLE surface so the
// room-info diff stays one line:
//   • AutomationsSection — the glass section card (rows + optimistic
//     enable/disable + honest delete + create affordance).
//   • AutomationsCreateSheet — the small glass bottom sheet with the
//     trigger/reply form (live validation, pending-disabled submit).
//   • AutomationsEditSheet — R41: rename a rule's trigger after create
//     (PATCH gains `trigger`; optimistic update + rollback + toast).
// Data rides GET/POST /api/conversations/[id]/automations and
// PATCH/DELETE /api/automations/[id] — real rows only, no mocks.
// ─────────────────────────────────────────────────────────────
'use client'

import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult } from '@tanstack/react-query'
import { Bot, Check, LoaderCircle, Pencil, Plus, Trash2, Zap } from 'lucide-react'
import type { AppUser, AutomationSummary } from '@/lib/types'
import { apiJson, formatListStamp } from '@/lib/pulse-utils'
import { ease, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { toast } from 'sonner'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { cn } from '@/lib/utils'

const TRIGGER_MIN = 2
const TRIGGER_MAX = 40
const REPLY_MAX = 500

/** Shared TanStack cache key for one conversation's automation rules. */
export function automationsKey(conversationId: string): ['automations', string] {
  return ['automations', conversationId]
}

export interface AutomationsSectionProps {
  me: AppUser
  conversationId: string
  /** viewer holds the admin role (room-info already computed it) */
  isAdmin: boolean
  reducedMotion?: boolean
}

/**
 * The room-info "Automations" glass section: every rule as a row
 * (trigger bold → reply preview, Zap hits counter, honest Switch and
 * delete for admins), read-only for members with an honest caption.
 */
export function AutomationsSection({
  me,
  conversationId,
  isAdmin,
  reducedMotion = false,
}: AutomationsSectionProps) {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  /** R41 — rule whose trigger is being renamed (drives AutomationsEditSheet). */
  const [editTarget, setEditTarget] = useState<AutomationSummary | null>(null)
  const [editOpen, setEditOpen] = useState(false)

  const openTriggerEdit = (row: AutomationSummary) => {
    setEditTarget(row)
    setEditOpen(true)
  }
  /** close now, unmount the sheet after its exit animation */
  const handleEditClose = (next: boolean) => {
    setEditOpen(next)
    if (!next) setTimeout(() => setEditTarget(null), 300)
  }

  const automationsQuery = useQuery({
    queryKey: automationsKey(conversationId),
    queryFn: () =>
      apiJson<{ automations: AutomationSummary[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/automations?userId=${encodeURIComponent(me.id)}`,
      ),
    staleTime: 10_000,
  })
  const rows = automationsQuery.data?.automations ?? []

  // Optimistic enable/disable — flips the cached row instantly, PATCHes,
  // rolls back + honest toast when the server refuses.
  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      apiJson<{ automation: AutomationSummary }>(`/api/automations/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify({ userId: me.id, enabled }),
      }),
    onMutate: async ({ id, enabled }) => {
      await queryClient.cancelQueries({ queryKey: automationsKey(conversationId) })
      const previous = queryClient.getQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
      )
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old
            ? {
                automations: old.automations.map((r) =>
                  r.id === id ? { ...r, enabled } : r,
                ),
              }
            : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      // Server truth (the PATCH also revalidates the reply text).
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old
            ? {
                automations: old.automations.map((r) =>
                  r.id === data.automation.id ? data.automation : r,
                ),
              }
            : old,
      )
      haptic(10)
    },
    onError: (error, _vars, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(automationsKey(conversationId), ctx.previous)
      toast.error(error instanceof Error ? error.message : 'Could not update the automation')
    },
  })

  // Honest delete — no undo by design (kept simple); the row leaves the
  // list optimistically and comes back if the server refuses.
  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      apiJson<{ ok: true }>(`/api/automations/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        body: JSON.stringify({ userId: me.id }),
      }),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: automationsKey(conversationId) })
      const previous = queryClient.getQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
      )
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old
            ? { automations: old.automations.filter((r) => r.id !== id) }
            : old,
      )
      return { previous }
    },
    onSuccess: () => {
      toast.success('Automation deleted')
      haptic(12)
    },
    onError: (error, _id, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(automationsKey(conversationId), ctx.previous)
      toast.error(error instanceof Error ? error.message : 'Could not delete the automation')
    },
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: automationsKey(conversationId) }),
  })

  // R41 — optimistic trigger rename: flips the cached row instantly, PATCHes,
  // rolls back + the server's honest error (409 duplicate / 400 length) on refusal.
  const renameMutation = useMutation({
    mutationFn: ({ id, trigger }: { id: string; trigger: string }) =>
      apiJson<{ automation: AutomationSummary }>(`/api/automations/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify({ userId: me.id, trigger }),
      }),
    onMutate: async ({ id, trigger }) => {
      await queryClient.cancelQueries({ queryKey: automationsKey(conversationId) })
      const previous = queryClient.getQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
      )
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old
            ? {
                automations: old.automations.map((r) =>
                  r.id === id ? { ...r, trigger } : r,
                ),
              }
            : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      // Server truth (also re-syncs hits/lastFiredAt if they moved).
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old
            ? {
                automations: old.automations.map((r) =>
                  r.id === data.automation.id ? data.automation : r,
                ),
              }
            : old,
      )
      toast.success('Trigger updated')
      haptic(12)
    },
    onError: (error, _vars, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(automationsKey(conversationId), ctx.previous)
      toast.error(error instanceof Error ? error.message : 'Could not rename the trigger')
    },
  })

  const anim = {
    initial: reducedMotion ? false : { opacity: 0, y: 10 },
    animate: { opacity: 1, y: 0 },
  }

  return (
    <>
      <motion.p
        {...anim}
        transition={{ ...spring.soft, delay: stagger(3) }}
        className="px-2 pt-4 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500"
      >
        Automations{automationsQuery.isPending && rows.length === 0 ? '' : ` · ${rows.length}`}
      </motion.p>
      <motion.div
        {...anim}
        transition={{ ...spring.soft, delay: stagger(3) }}
        className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5"
      >
        {automationsQuery.isPending && rows.length === 0 ? (
          <div className="space-y-2 p-1" role="status" aria-label="Loading automations">
            <Skeleton className="h-12 w-full rounded-2xl" />
            <Skeleton className="h-12 w-4/5 rounded-2xl" />
          </div>
        ) : rows.length === 0 ? (
          <div className="flex items-start gap-3 px-3 py-4">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-900/[0.04] text-zinc-400 dark:bg-white/[0.06]">
              <Bot className="size-4" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-xs font-semibold text-zinc-600 dark:text-zinc-300">
                {isAdmin ? 'No automations yet' : 'No automations here'}
              </p>
              <p className="mt-0.5 text-[11px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                {isAdmin
                  ? 'Add a keyword that fires an instant reply when a member sends it.'
                  : 'Only admins can manage automations.'}
              </p>
            </div>
            {isAdmin ? (
              <button
                type="button"
                aria-label="New automation"
                onClick={() => {
                  haptic(8)
                  setCreateOpen(true)
                }}
                className="glass-pill flex size-8 shrink-0 items-center justify-center text-emerald-600 outline-none transition-transform active:scale-90 dark:text-emerald-400"
              >
                <Plus className="size-4" aria-hidden />
              </button>
            ) : null}
          </div>
        ) : (
          <ul>
            {rows.map((row, i) => (
              <motion.li
                key={row.id}
                initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.2, delay: Math.min(i * 0.03, 0.18), ease: ease.out }}
                className={cn(
                  'glass-row-hover flex items-center gap-2.5 rounded-2xl px-2.5 py-2',
                  !row.enabled && 'opacity-70',
                )}
              >
                <span
                  className={cn(
                    'flex size-8 shrink-0 items-center justify-center rounded-xl',
                    row.enabled
                      ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                      : 'bg-zinc-900/[0.05] text-zinc-400 dark:bg-white/[0.07]',
                  )}
                >
                  <Zap className="size-3.5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1">
                    <p className="truncate text-[13px] font-bold text-zinc-900 dark:text-zinc-50">
                      {row.trigger}
                    </p>
                    {isAdmin ? (
                      <button
                        type="button"
                        aria-label={`Edit trigger "${row.trigger}"`}
                        disabled={renameMutation.isPending}
                        onClick={() => {
                          haptic(8)
                          openTriggerEdit(row)
                        }}
                        className="flex size-6 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-90 disabled:opacity-40 dark:hover:text-emerald-400"
                      >
                        <Pencil className="size-3" aria-hidden />
                      </button>
                    ) : null}
                  </div>
                  <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                    {row.reply}
                  </p>
                  <p className="mt-0.5 flex items-center gap-1 truncate text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
                    <Zap className="size-2.5 shrink-0" aria-hidden />
                    <span className="tabular-nums">{row.hits}</span>
                    <span>
                      {row.hits === 1 ? 'hit' : 'hits'}
                      {row.lastFiredAt ? ` · last ${formatListStamp(row.lastFiredAt)}` : ''}
                      {row.createdBy?.name ? ` · by ${row.createdBy.name}` : ''}
                    </span>
                  </p>
                </div>
                {isAdmin ? (
                  <>
                    <Switch
                      checked={row.enabled}
                      disabled={toggleMutation.isPending}
                      onCheckedChange={(checked) =>
                        toggleMutation.mutate({ id: row.id, enabled: checked })
                      }
                      aria-label={`${row.enabled ? 'Disable' : 'Enable'} automation "${row.trigger}"`}
                      className="shrink-0"
                    />
                    <button
                      type="button"
                      aria-label={`Delete automation "${row.trigger}"`}
                      disabled={deleteMutation.isPending}
                      onClick={() => {
                        haptic(10)
                        deleteMutation.mutate(row.id)
                      }}
                      className="flex size-8 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90 disabled:opacity-40"
                    >
                      {deleteMutation.isPending && deleteMutation.variables === row.id ? (
                        <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                      ) : (
                        <Trash2 className="size-3.5" aria-hidden />
                      )}
                    </button>
                  </>
                ) : (
                  <span
                    className={cn(
                      'shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold',
                      row.enabled
                        ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                        : 'bg-zinc-900/[0.05] text-zinc-500 dark:bg-white/[0.07] dark:text-zinc-400',
                    )}
                  >
                    {row.enabled ? 'On' : 'Off'}
                  </span>
                )}
              </motion.li>
            ))}
          </ul>
        )}
        {/* create affordance for populated lists — admins only */}
        {isAdmin && rows.length > 0 ? (
          <button
            type="button"
            onClick={() => {
              haptic(8)
              setCreateOpen(true)
            }}
            className="glass-row-hover mt-1 flex w-full items-center gap-3 rounded-2xl px-3 py-2 text-left text-xs font-bold text-emerald-600 outline-none transition-transform active:scale-[0.99] dark:text-emerald-400"
          >
            <Plus className="size-3.5 shrink-0" aria-hidden />
            New automation
          </button>
        ) : null}
      </motion.div>

      <AutomationsCreateSheet
        me={me}
        conversationId={conversationId}
        reducedMotion={reducedMotion}
        open={createOpen}
        onOpenChange={setCreateOpen}
      />

      {/* R41 — trigger rename sheet (unmounts after the exit animation) */}
      {editTarget ? (
        <AutomationsEditSheet
          key={editTarget.id}
          automation={editTarget}
          renameMutation={renameMutation}
          reducedMotion={reducedMotion}
          open={editOpen}
          onOpenChange={handleEditClose}
        />
      ) : null}
    </>
  )
}

interface AutomationsCreateSheetProps {
  me: AppUser
  conversationId: string
  reducedMotion: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Small glass bottom sheet: trigger + reply form with live validation. */
function AutomationsCreateSheet({
  me,
  conversationId,
  reducedMotion,
  open,
  onOpenChange,
}: AutomationsCreateSheetProps) {
  const queryClient = useQueryClient()
  const [trigger, setTrigger] = useState('')
  const [reply, setReply] = useState('')

  const trimmedTrigger = trigger.trim()
  const trimmedReply = reply.trim()
  const triggerInvalid = trimmedTrigger.length < TRIGGER_MIN || trimmedTrigger.length > TRIGGER_MAX
  const replyInvalid = trimmedReply.length < 1 || trimmedReply.length > REPLY_MAX
  const valid = !triggerInvalid && !replyInvalid

  const createMutation = useMutation({
    mutationFn: () =>
      apiJson<{ automation: AutomationSummary }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/automations`,
        {
          method: 'POST',
          body: JSON.stringify({ userId: me.id, trigger: trimmedTrigger, reply: trimmedReply }),
        },
      ),
    onSuccess: (data) => {
      queryClient.setQueryData<{ automations: AutomationSummary[] }>(
        automationsKey(conversationId),
        (old) =>
          old ? { automations: [data.automation, ...old.automations] } : { automations: [data.automation] },
      )
      toast.success('Automation created')
      haptic(14)
      setTrigger('')
      setReply('')
      onOpenChange(false)
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Could not create the automation'),
  })

  return (
    <AnimatePresence>
      {open ? (
        <>
          {/* backdrop — tap anywhere outside to dismiss */}
          <motion.button
            type="button"
            aria-hidden
            tabIndex={-1}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.16 }}
            onClick={() => onOpenChange(false)}
            className="absolute inset-0 z-[60] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
          />
          <motion.div
            role="dialog"
            aria-label="New automation"
            initial={reducedMotion ? false : { y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={spring.soft}
            className="glass-deep glass-sheen absolute inset-x-0 bottom-0 z-[61] mx-auto flex w-full flex-col overflow-hidden rounded-t-3xl px-4 pt-2.5 pb-[max(1rem,env(safe-area-inset-bottom))]"
          >
            <div
              aria-hidden
              className="mx-auto mb-2.5 h-1 w-10 shrink-0 rounded-full bg-zinc-900/15 dark:bg-white/20"
            />

            <div className="flex items-center gap-2.5 px-1">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                <Bot className="size-4" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-zinc-900 dark:text-zinc-50">New automation</p>
                <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
                  Fires once per matching message — as a reply from you
                </p>
              </div>
            </div>

            <div className="mt-3 space-y-2.5">
              <label className="block">
                <span className="mb-1 flex items-baseline justify-between px-1">
                  <span className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
                    Trigger keyword
                  </span>
                  <span className="text-[10px] font-medium tabular-nums text-zinc-400 dark:text-zinc-500">
                    {trimmedTrigger.length}/{TRIGGER_MAX}
                  </span>
                </span>
                <input
                  value={trigger}
                  onChange={(e) => setTrigger(e.target.value.slice(0, TRIGGER_MAX + 8))}
                  placeholder="e.g. pricing"
                  aria-label="Trigger keyword"
                  aria-invalid={trigger.length > 0 && triggerInvalid}
                  autoComplete="off"
                  className={cn(
                    'h-10 w-full rounded-2xl border border-zinc-900/[0.07] bg-white/50 px-3 text-[13px] font-medium text-zinc-900 placeholder:text-zinc-400 outline-none transition-colors focus:border-emerald-500/50 dark:border-white/[0.09] dark:bg-white/[0.06] dark:text-zinc-100',
                    trigger.length > 0 && triggerInvalid && 'border-rose-400/60',
                  )}
                />
                <span
                  className={cn(
                    'mt-1 block px-1 text-[10px] leading-relaxed',
                    trigger.length > 0 && triggerInvalid
                      ? 'font-semibold text-rose-500'
                      : 'text-zinc-400 dark:text-zinc-500',
                  )}
                >
                  {trigger.length > 0 && triggerInvalid
                    ? `Use ${TRIGGER_MIN}-${TRIGGER_MAX} characters.`
                    : 'Matched as a standalone word — "pricing" will not fire on "pricinggg".'}
                </span>
              </label>

              <label className="block">
                <span className="mb-1 flex items-baseline justify-between px-1">
                  <span className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
                    Reply
                  </span>
                  <span className="text-[10px] font-medium tabular-nums text-zinc-400 dark:text-zinc-500">
                    {trimmedReply.length}/{REPLY_MAX}
                  </span>
                </span>
                <textarea
                  value={reply}
                  onChange={(e) => setReply(e.target.value.slice(0, REPLY_MAX + 8))}
                  placeholder="Sent as a normal message when the keyword appears"
                  aria-label="Automation reply"
                  aria-invalid={reply.length > 0 && replyInvalid}
                  rows={3}
                  className={cn(
                    'w-full resize-none rounded-2xl border border-zinc-900/[0.07] bg-white/50 px-3 py-2 text-[13px] leading-relaxed text-zinc-900 placeholder:text-zinc-400 outline-none transition-colors focus:border-emerald-500/50 dark:border-white/[0.09] dark:bg-white/[0.06] dark:text-zinc-100',
                    reply.length > 0 && replyInvalid && 'border-rose-400/60',
                  )}
                />
                <span
                  className={cn(
                    'mt-1 block px-1 text-[10px] leading-relaxed',
                    reply.length > 0 && replyInvalid
                      ? 'font-semibold text-rose-500'
                      : 'text-zinc-400 dark:text-zinc-500',
                  )}
                >
                  {reply.length > 0 && replyInvalid
                    ? `Use 1-${REPLY_MAX} characters.`
                    : 'The message lands from your account, marked Automation.'}
                </span>
              </label>
            </div>

            <button
              type="button"
              disabled={!valid || createMutation.isPending}
              onClick={() => {
                haptic(8)
                createMutation.mutate()
              }}
              className="mt-3 flex h-11 w-full shrink-0 items-center justify-center gap-1.5 rounded-full bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-50"
            >
              {createMutation.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <Zap className="size-4" aria-hidden />
              )}
              Create automation
            </button>
          </motion.div>
        </>
      ) : null}
    </AnimatePresence>
  )
}

interface AutomationsEditSheetProps {
  automation: AutomationSummary
  renameMutation: UseMutationResult<
    { automation: AutomationSummary },
    Error,
    { id: string; trigger: string },
    { previous: { automations: AutomationSummary[] } | undefined }
  >
  reducedMotion: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * R41 — small glass bottom sheet for renaming a rule's trigger in place.
 * Same glass recipe as the create sheet; the reply stays untouched. Save is
 * disabled while the draft is invalid or unchanged; the optimistic mutation
 * (owned by the section) rolls the row back + toasts the server's honest
 * error on refusal (409 duplicate / 400 length).
 */
function AutomationsEditSheet({
  automation,
  renameMutation,
  reducedMotion,
  open,
  onOpenChange,
}: AutomationsEditSheetProps) {
  const [trigger, setTrigger] = useState(automation.trigger)

  const trimmedTrigger = trigger.trim()
  const triggerInvalid = trimmedTrigger.length < TRIGGER_MIN || trimmedTrigger.length > TRIGGER_MAX
  const unchanged = trimmedTrigger === automation.trigger
  const valid = !triggerInvalid && !unchanged

  return (
    <AnimatePresence>
      {open ? (
        <>
          {/* backdrop — tap anywhere outside to dismiss */}
          <motion.button
            type="button"
            aria-hidden
            tabIndex={-1}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.16 }}
            onClick={() => onOpenChange(false)}
            className="absolute inset-0 z-[60] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
          />
          <motion.div
            role="dialog"
            aria-label="Edit trigger"
            initial={reducedMotion ? false : { y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={spring.soft}
            className="glass-deep glass-sheen absolute inset-x-0 bottom-0 z-[61] mx-auto flex w-full flex-col overflow-hidden rounded-t-3xl px-4 pt-2.5 pb-[max(1rem,env(safe-area-inset-bottom))]"
          >
            <div
              aria-hidden
              className="mx-auto mb-2.5 h-1 w-10 shrink-0 rounded-full bg-zinc-900/15 dark:bg-white/20"
            />

            <div className="flex items-center gap-2.5 px-1">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                <Pencil className="size-4" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-zinc-900 dark:text-zinc-50">Edit trigger</p>
                <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
                  Only the keyword moves — the reply stays unchanged
                </p>
              </div>
            </div>

            {/* current rule context (read-only) */}
            <div className="mt-3 rounded-2xl border border-zinc-900/[0.06] bg-white/40 px-3 py-2 dark:border-white/[0.08] dark:bg-white/[0.04]">
              <p className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
                Reply
              </p>
              <p className="mt-0.5 truncate text-[12px] text-zinc-600 dark:text-zinc-300">
                {automation.reply}
              </p>
            </div>

            <label className="mt-2.5 block">
              <span className="mb-1 flex items-baseline justify-between px-1">
                <span className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
                  Trigger keyword
                </span>
                <span className="text-[10px] font-medium tabular-nums text-zinc-400 dark:text-zinc-500">
                  {trimmedTrigger.length}/{TRIGGER_MAX}
                </span>
              </span>
              <input
                value={trigger}
                onChange={(e) => setTrigger(e.target.value.slice(0, TRIGGER_MAX + 8))}
                placeholder="e.g. pricing"
                aria-label="Trigger keyword"
                aria-invalid={trigger.length > 0 && triggerInvalid}
                autoComplete="off"
                className={cn(
                  'h-10 w-full rounded-2xl border border-zinc-900/[0.07] bg-white/50 px-3 text-[13px] font-medium text-zinc-900 placeholder:text-zinc-400 outline-none transition-colors focus:border-emerald-500/50 dark:border-white/[0.09] dark:bg-white/[0.06] dark:text-zinc-100',
                  trigger.length > 0 && triggerInvalid && 'border-rose-400/60',
                )}
              />
              <span
                className={cn(
                  'mt-1 block px-1 text-[10px] leading-relaxed',
                  trigger.length > 0 && triggerInvalid
                    ? 'font-semibold text-rose-500'
                    : 'text-zinc-400 dark:text-zinc-500',
                )}
              >
                {trigger.length > 0 && triggerInvalid
                  ? `Use ${TRIGGER_MIN}-${TRIGGER_MAX} characters.`
                  : 'Matched as a standalone word — must stay unique in this chat.'}
              </span>
            </label>

            <button
              type="button"
              disabled={!valid || renameMutation.isPending}
              onClick={() =>
                renameMutation.mutate(
                  { id: automation.id, trigger: trimmedTrigger },
                  { onSuccess: () => onOpenChange(false) },
                )
              }
              className="mt-3 flex h-11 w-full shrink-0 items-center justify-center gap-1.5 rounded-full bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-50"
            >
              {renameMutation.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <Check className="size-4" aria-hidden />
              )}
              Save trigger
            </button>
          </motion.div>
        </>
      ) : null}
    </AnimatePresence>
  )
}

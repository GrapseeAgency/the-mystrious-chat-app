// ─────────────────────────────────────────────────────────────
// Pulse — ReportPanel (R48): WhatsApp/Telegram-style account report.
// Inline expandable panel shared by the profile sheet and the
// profile route page. Reports are PRIVATE: the reported account is
// never notified (server contract), the panel only shows the
// reporter's own prior submissions as an honest hint.
// ─────────────────────────────────────────────────────────────
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Flag, LoaderCircle } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

const REASONS: { key: string; label: string }[] = [
  { key: 'spam', label: 'Spam' },
  { key: 'harassment', label: 'Harassment or bullying' },
  { key: 'impersonation', label: 'Impersonation' },
  { key: 'inappropriate', label: 'Inappropriate content' },
  { key: 'scam', label: 'Scam or fraud' },
  { key: 'other', label: 'Something else' },
]

export function ReportPanel({
  reportedId,
  reporterId,
  reportedName,
  onDone,
}: {
  reportedId: string
  reporterId: string | undefined
  reportedName: string
  /** called after a successful submit (callers may collapse the panel) */
  onDone?: () => void
}) {
  const queryClient = useQueryClient()
  const [reason, setReason] = useState<string | null>(null)
  const [details, setDetails] = useState('')

  // the reporter's OWN prior submissions about this account (private hint)
  const reportQ = useQuery({
    queryKey: ['report-pair', reporterId ?? '-', reportedId],
    queryFn: async (): Promise<{ reasons: { reason: string; at: string }[] }> => {
      return apiJson(
        `/api/users/${encodeURIComponent(reportedId)}/report?userId=${encodeURIComponent(reporterId ?? '')}`,
      )
    },
    enabled: !!reporterId,
    staleTime: 5_000,
  })

  const submitMutation = useMutation({
    mutationFn: async () => {
      return apiJson<{ reported: boolean; updated?: boolean }>(
        `/api/users/${encodeURIComponent(reportedId)}/report`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: reporterId, reason, details: details.trim() }),
        },
      )
    },
    onSuccess: (data) => {
      haptic(14)
      void queryClient.invalidateQueries({ queryKey: ['report-pair', reporterId ?? '-', reportedId] })
      toast.success(
        data.updated
          ? 'Report updated — thanks for the extra detail'
          : `Report submitted. Thanks for helping keep Pulse safe.`,
      )
      setReason(null)
      setDetails('')
      onDone?.()
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not submit the report')
    },
  })

  const reportedReasons = reportQ.data?.reasons ?? []

  return (
    <div
      role="group"
      aria-label={`Report ${reportedName}`}
      className="rounded-2xl border border-amber-500/25 bg-amber-500/[0.05] p-3"
    >
      <p className="flex items-center gap-1.5 text-[12px] font-bold text-amber-700 dark:text-amber-300">
        <Flag className="size-3.5" aria-hidden />
        Report {reportedName}
      </p>
      <p className="mt-1 text-[11.5px] leading-relaxed text-zinc-500 dark:text-zinc-400">
        Tell us what&apos;s happening. Reports are private —{' '}
        <span className="font-semibold">{reportedName.split(' ')[0]}</span> will not be notified.
      </p>

      {reportedReasons.length > 0 ? (
        <p className="mt-2 rounded-xl bg-amber-500/10 px-2.5 py-1.5 text-[11px] font-medium text-amber-700 dark:text-amber-300" role="status">
          You already reported this account for{' '}
          {reportedReasons.map((r) => REASONS.find((x) => x.key === r.reason)?.label ?? r.reason).join(', ')}.
        </p>
      ) : null}

      <div className="mt-2.5 grid grid-cols-1 gap-1.5" role="radiogroup" aria-label="Reason for the report">
        {REASONS.map((r) => {
          const active = reason === r.key
          return (
            <button
              key={r.key}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => {
                haptic(6)
                setReason(active ? null : r.key)
              }}
              className={cn(
                'flex min-h-[40px] items-center gap-2 rounded-xl border px-3 py-2 text-left text-[12.5px] font-semibold outline-none transition-colors',
                active
                  ? 'border-amber-500/50 bg-amber-500/15 text-amber-800 dark:text-amber-200'
                  : 'border-zinc-200/70 bg-white/60 text-zinc-700 hover:bg-white dark:border-white/10 dark:bg-white/[0.04] dark:text-zinc-300 dark:hover:bg-white/[0.08]',
              )}
            >
              <span
                aria-hidden
                className={cn(
                  'flex size-4 shrink-0 items-center justify-center rounded-full border',
                  active ? 'border-amber-500 bg-amber-500 text-white' : 'border-zinc-300 dark:border-zinc-600',
                )}
              >
                {active ? <Check className="size-2.5" strokeWidth={4} /> : null}
              </span>
              {r.label}
            </button>
          )
        })}
      </div>

      <textarea
        value={details}
        onChange={(e) => setDetails(e.target.value)}
        maxLength={500}
        rows={2}
        placeholder="Add details (optional)"
        aria-label="Additional details for the report"
        className="mt-2 w-full resize-none rounded-xl border border-zinc-200/70 bg-white/70 px-3 py-2 text-[12.5px] text-zinc-700 outline-none placeholder:text-zinc-400 focus:border-amber-500/40 dark:border-white/10 dark:bg-white/[0.04] dark:text-zinc-200 dark:placeholder:text-zinc-500"
      />

      <Button
        disabled={!reason || submitMutation.isPending}
        onClick={() => submitMutation.mutate()}
        className="mt-2 h-10 w-full rounded-xl bg-amber-500 text-[13px] font-bold text-white hover:bg-amber-500/90 disabled:opacity-50"
      >
        {submitMutation.isPending ? (
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
        ) : (
          <Flag className="mr-1.5 size-4" aria-hidden />
        )}
        Submit report
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — @handle editor dialog (R25-b, logic preserved from the
// original Profile tab; visual shell modernized). Live availability
// check via GET /api/users/check-username, save via PATCH
// /api/users/[id] { username }. 3–20 chars: a-z 0-9 _.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Check, LoaderCircle, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { ApiError, apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

const HANDLE_MIN = 3
const HANDLE_MAX = 20
const HANDLE_RE = /^[a-z0-9_]+$/
const HANDLE_DEBOUNCE_MS = 350

/** Mirrors normalizeUsername on the server: 3–20 chars, a-z0-9_ */
function isValidHandle(value: string): boolean {
  return value.length >= HANDLE_MIN && value.length <= HANDLE_MAX && HANDLE_RE.test(value)
}

/** Keep only characters the server would accept. */
function sanitizeHandleInput(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '')
    .slice(0, HANDLE_MAX)
}

export function HandleEditorDialog({
  open,
  onOpenChange,
  me,
  onSaved,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  me: AppUser
  onSaved: (user: AppUser) => void
}) {
  // body mounts only while open → its state resets naturally on every open
  if (!open) return null
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <HandleEditorBody me={me} onSaved={onSaved} onClose={() => onOpenChange(false)} />
    </Dialog>
  )
}

function HandleEditorBody({
  me,
  onSaved,
  onClose,
}: {
  me: AppUser
  onSaved: (user: AppUser) => void
  onClose: () => void
}) {
  const [value, setValue] = useState(me.username ?? '')
  const [debounced, setDebounced] = useState(me.username ?? '')
  /** suggestion captured from a 409 username_taken response */
  const [clashSuggestion, setClashSuggestion] = useState<string | null>(null)

  const trimmed = value.trim()
  const changed = trimmed !== (me.username ?? '')
  const valid = isValidHandle(trimmed)

  // live availability — debounced, skipped while re-typing the current handle
  const checkEnabled = valid && debounced !== (me.username ?? '')
  const checkQ = useQuery({
    queryKey: ['username-check', debounced],
    queryFn: async (): Promise<{ available: boolean; suggestion: string | null }> => {
      return apiJson<{ available: boolean; suggestion: string | null }>(
        `/api/users/check-username?username=${encodeURIComponent(debounced)}`,
      )
    },
    enabled: checkEnabled,
    staleTime: 5_000,
  })

  const checkStale = !checkEnabled || debounced !== trimmed
  const checking = valid && changed && (checkStale || checkQ.isFetching)
  const available = valid && changed && !checkStale && checkQ.data?.available === true
  const taken = valid && changed && !checkStale && checkQ.data?.available === false

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(trimmed), HANDLE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [trimmed])

  const save = useMutation({
    mutationFn: async (): Promise<AppUser> => {
      const res = await apiJson<{ user: AppUser }>(
        `/api/users/${encodeURIComponent(me.id)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ username: trimmed }),
        },
      )
      return res.user
    },
    onSuccess: (user) => {
      onSaved(user)
      toast.success(user.username ? `@${user.username} is yours now` : 'Handle saved')
      onClose()
    },
    onError: async (error: Error) => {
      toast.error(error.message || 'Could not save the handle')
      if (error instanceof ApiError && error.status === 409) {
        // the PATCH route's 409 carries a suggestion but ApiError drops it —
        // re-ask the availability endpoint for the nearest free variant
        try {
          const res = await apiJson<{ available: boolean; suggestion: string | null }>(
            `/api/users/check-username?username=${encodeURIComponent(trimmed)}`,
          )
          setClashSuggestion(res.suggestion)
        } catch {
          setClashSuggestion(null)
        }
      }
    },
  })

  return (
    <DialogContent className="max-w-[340px] gap-3 rounded-2xl border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
      <div className="text-left">
        <h2 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Your @handle</h2>
        <p className="mt-1 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          3–20 characters: lowercase letters, digits, underscore. Friends can find you by it.
        </p>
      </div>

      <div className="space-y-1.5">
        <div className="relative">
          <span
            aria-hidden
            className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-[15px] font-semibold text-zinc-400 dark:text-zinc-500"
          >
            @
          </span>
          <Input
            value={value}
            onChange={(e) => {
              setClashSuggestion(null)
              setValue(sanitizeHandleInput(e.target.value))
            }}
            placeholder="e.g. alice_chen"
            maxLength={HANDLE_MAX}
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="none"
            spellCheck={false}
            enterKeyHint="done"
            aria-label="Your handle"
            aria-invalid={(taken || clashSuggestion !== null) || undefined}
            aria-describedby="handle-editor-availability"
            className={cn(
              'h-11 rounded-xl border-zinc-200 bg-zinc-50 pl-8 text-[15px] focus-visible:ring-[var(--ui-accent,#10b981)]/60 dark:border-zinc-700 dark:bg-zinc-800',
              (taken || clashSuggestion !== null) &&
                'border-amber-400 focus-visible:ring-amber-500/50 dark:border-amber-500/60',
            )}
          />
        </div>

        <p
          id="handle-editor-availability"
          role="status"
          aria-live="polite"
          className="flex min-h-[18px] flex-wrap items-center gap-1.5 text-xs font-medium"
        >
          {trimmed.length === 0 ? (
            <span className="text-zinc-400 dark:text-zinc-500">Type a handle, or leave empty.</span>
          ) : !valid ? (
            <span className="text-zinc-500 dark:text-zinc-400">
              {HANDLE_MIN}–{HANDLE_MAX} characters: a-z, 0-9, underscore.
            </span>
          ) : checking ? (
            <span className="flex items-center gap-1.5 text-zinc-500 dark:text-zinc-400">
              <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
              Checking @{trimmed}…
            </span>
          ) : available ? (
            <span className="flex items-center gap-1.5 text-[var(--ui-accent,#10b981)]">
              <Check className="size-3.5" strokeWidth={3} aria-hidden />
              @{trimmed} is free
            </span>
          ) : trimmed === (me.username ?? '') ? (
            <span className="flex items-center gap-1.5 text-zinc-500 dark:text-zinc-400">
              <Check className="size-3.5" strokeWidth={3} aria-hidden />
              That&apos;s your current handle
            </span>
          ) : taken ? (
            <span className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400">
              <X className="size-3.5" strokeWidth={3} aria-hidden />
              @{trimmed} is taken
              {checkQ.data?.suggestion ? (
                <button
                  type="button"
                  onClick={() => setValue(sanitizeHandleInput(checkQ.data?.suggestion ?? ''))}
                  className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
                >
                  Use @{checkQ.data.suggestion}
                </button>
              ) : null}
            </span>
          ) : clashSuggestion ? (
            <span className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400">
              <X className="size-3.5" strokeWidth={3} aria-hidden />
              @{trimmed} was just taken
              <button
                type="button"
                onClick={() => setValue(sanitizeHandleInput(clashSuggestion))}
                className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
              >
                Use @{clashSuggestion}
              </button>
            </span>
          ) : null}
        </p>
      </div>

      <div className="flex gap-2">
        <Button
          variant="outline"
          onClick={onClose}
          className="h-10 flex-1 rounded-xl text-sm font-semibold"
        >
          Cancel
        </Button>
        <Button
          onClick={() => save.mutate()}
          disabled={!valid || checking || taken || !changed || save.isPending}
          className="h-10 flex-1 rounded-xl bg-[var(--ui-accent,#10b981)] text-sm font-semibold text-white hover:opacity-90 active:scale-[0.98]"
        >
          {save.isPending ? (
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
          ) : (
            'Save handle'
          )}
        </Button>
      </div>
    </DialogContent>
  )
}

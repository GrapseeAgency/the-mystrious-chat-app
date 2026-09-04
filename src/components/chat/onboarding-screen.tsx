// ─────────────────────────────────────────────────────────────
// Pulse Chat — onboarding, two steps:
//   1. display name + avatar color (existing reclaim-by-name flow)
//   2. @handle picker — auto-suggested from the name, live
//      availability via GET /api/users/check-username (debounced),
//      skippable. Creates the real account via POST /api/users.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState } from 'react'
import Image from 'next/image'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleAlert,
  LoaderCircle,
  LogIn,
  Sparkles,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
  apiJson,
  type AvatarColor,
} from '@/lib/pulse-utils'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { WebglGlow } from '@/components/fx/webgl-glow'
import { cn } from '@/lib/utils'

interface CreateUserResponse {
  user: AppUser
}
interface UsernameCheckResponse {
  available: boolean
  suggestion: string | null
}

const NAME_MAX = 32
const USERNAME_MIN = 3
const USERNAME_MAX = 20
const HANDLE_RE = /^[a-z0-9_]+$/
const CHECK_DEBOUNCE_MS = 350

/** Mirrors normalizeUsername on the server: 3–20 chars, a-z0-9_ */
function isValidHandle(value: string): boolean {
  return (
    value.length >= USERNAME_MIN &&
    value.length <= USERNAME_MAX &&
    HANDLE_RE.test(value)
  )
}

/** Auto-suggest a handle from a display name (lowercase, sanitized). */
function suggestHandleFromName(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+/, '')
    .replace(/_+$/, '')
    .replace(/_{2,}/g, '_')
    .slice(0, USERNAME_MAX)
}

/** Keep only chars the server would accept, lowercased, capped. */
function sanitizeHandleInput(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '')
    .slice(0, USERNAME_MAX)
}

type CreateOutcome =
  | { ok: true; user: AppUser }
  | { ok: false; status: number; code: string | null; message: string; suggestion: string | null }

/**
 * POST /api/users with full 409 detail (code + suggestion survive here,
 * where ApiError would only carry the message).
 */
async function createUserRequest(payload: {
  name: string
  color: string
  username?: string
}): Promise<CreateOutcome> {
  try {
    const res = await fetch('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      cache: 'no-store',
    })
    const body: unknown = await res.json().catch(() => null)
    if (res.ok) {
      const user = (body as { user?: AppUser } | null)?.user
      if (user) return { ok: true, user }
      return {
        ok: false,
        status: res.status,
        code: null,
        message: 'Unexpected response from the server.',
        suggestion: null,
      }
    }
    const rec = body as { error?: unknown; code?: unknown; suggestion?: unknown } | null
    return {
      ok: false,
      status: res.status,
      code: typeof rec?.code === 'string' ? rec.code : null,
      message: typeof rec?.error === 'string' ? rec.error : `Request failed (${res.status})`,
      suggestion: typeof rec?.suggestion === 'string' ? rec.suggestion : null,
    }
  } catch {
    return {
      ok: false,
      status: 0,
      code: null,
      message: 'Network error — try again.',
      suggestion: null,
    }
  }
}

/** Case-insensitive account lookup (deep-link login / "that's you" affordance). */
async function lookupUser(name: string): Promise<AppUser | null> {
  try {
    const res = await apiJson<{ user: AppUser }>(
      `/api/users?name=${encodeURIComponent(name)}`,
    )
    return res.user
  } catch {
    return null
  }
}

type Step = 'name' | 'handle'

export function OnboardingScreen() {
  const setUser = usePulseSession((s) => s.setUser)
  const [step, setStep] = useState<Step>('name')
  const [name, setName] = useState('')
  const [color, setColor] = useState<AvatarColor>('emerald')
  const [nameTaken, setNameTaken] = useState(false)
  // handle step state
  const [handle, setHandle] = useState('')
  const [debouncedHandle, setDebouncedHandle] = useState('')
  const [serverTaken, setServerTaken] = useState<{ message: string; suggestion: string | null } | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  /** guards the one-shot ?login= auto-login (React strict-mode double effects) */
  const deepLinkHandled = useRef(false)

  // deep-link login: /?login=Alice%20Chen → sign into the existing account
  // when it exists, otherwise keep the value as a signup prefill
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const prefill = params.get('login')?.trim() ?? ''
    const frame = requestAnimationFrame(() => {
      if (prefill.length > 0) {
        setName(prefill.slice(0, NAME_MAX))
        if (!deepLinkHandled.current) {
          deepLinkHandled.current = true
          void lookupUser(prefill.slice(0, NAME_MAX)).then((user) => {
            if (user) {
              setUser(user)
              toast.success(`Welcome back, ${user.name}!`)
            }
          })
        }
      }
      inputRef.current?.focus()
    })
    return () => cancelAnimationFrame(frame)
  }, [setUser])

  const validName = name.trim().length > 0 && name.trim().length <= NAME_MAX
  const trimmedHandle = handle.trim()
  const validHandle = isValidHandle(trimmedHandle)

  // live availability — debounced so typing doesn't hammer the API
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedHandle(trimmedHandle), CHECK_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [trimmedHandle])

  const checkQ = useQuery({
    queryKey: ['username-check', debouncedHandle],
    queryFn: async (): Promise<UsernameCheckResponse> => {
      return apiJson<UsernameCheckResponse>(
        `/api/users/check-username?username=${encodeURIComponent(debouncedHandle)}`,
      )
    },
    enabled: step === 'handle' && isValidHandle(debouncedHandle),
    staleTime: 5_000,
  })

  // availability only counts when it belongs to the handle currently on screen
  const checkStale = step !== 'handle' || debouncedHandle !== trimmedHandle
  const checking = validHandle && (checkStale || checkQ.isFetching)
  const checkAvailable = validHandle && !checkStale && checkQ.data?.available === true
  const checkTaken = validHandle && !checkStale && checkQ.data?.available === false
  const liveSuggestion = checkTaken ? (checkQ.data?.suggestion ?? null) : null

  const create = useMutation({
    mutationFn: async (opts: { username: string | null }): Promise<CreateOutcome> => {
      return createUserRequest({
        name: name.trim(),
        color,
        ...(opts.username ? { username: opts.username } : {}),
      })
    },
    onSuccess: (outcome) => {
      if (outcome.ok) {
        setUser(outcome.user)
        toast.success(`Welcome to Pulse, ${outcome.user.name}!`)
        return
      }
      if (outcome.status === 409 && outcome.code === 'username_taken') {
        setServerTaken({ message: outcome.message, suggestion: outcome.suggestion })
        return
      }
      if (outcome.status === 409) {
        // display-name clash → step back and reuse the existing "log in instead" flow
        setStep('name')
        setNameTaken(true)
        toast.error(outcome.message)
        return
      }
      setServerTaken({ message: outcome.message, suggestion: null })
      toast.error(outcome.message)
    },
    onError: () => {
      toast.error('Could not create your account')
    },
  })

  const login = useMutation({
    mutationFn: async () => {
      const user = await lookupUser(name.trim())
      if (!user) throw new Error('No Pulse account with that name.')
      return user
    },
    onSuccess: (user) => {
      setUser(user)
      toast.success(`Welcome back, ${user.name}!`)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not sign you in')
    },
  })

  // any edit to the name clears the "taken" state so the affordance re-hides
  const handleNameChange = (value: string) => {
    setName(value)
    setNameTaken(false)
  }

  const startHandleStep = () => {
    setServerTaken(null)
    setHandle(suggestHandleFromName(name))
    setDebouncedHandle('')
    setStep('handle')
  }

  const pending = create.isPending

  return (
    <div className="flex h-full flex-col overflow-y-auto pulse-scroll px-6 pb-[max(1.5rem,env(safe-area-inset-bottom))] pt-[max(2rem,env(safe-area-inset-top))]">
      <div className="flex flex-1 flex-col items-center justify-center gap-5">
        <div className="relative overflow-hidden rounded-3xl">
          <WebglGlow className="absolute inset-0" intensity={0.85} />
          <Image
            src="/onboarding-hero.png"
            alt="Pulse messenger illustration"
            width={196}
            height={196}
            priority
            className="relative rounded-3xl shadow-lg shadow-emerald-500/10"
          />
        </div>

        <div className="text-center">
          <h1 className="flex items-center justify-center gap-1.5 text-[26px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Pulse
            <span aria-hidden className="inline-block size-2 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600" />
          </h1>
          <p className="mt-1 text-[13px] font-medium text-zinc-500 dark:text-zinc-400">
            Your conversations, instantly alive.
          </p>
        </div>

        <AnimatePresence mode="wait" initial={false}>
          {step === 'name' ? (
            <motion.div
              key="step-name"
              initial={{ opacity: 0, x: 24 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -24 }}
              transition={{ type: 'spring', stiffness: 380, damping: 34 }}
              className="w-full"
            >
              {/* ── step 1: display name + color ── */}
              <form
                className="w-full space-y-4"
                onSubmit={(event) => {
                  event.preventDefault()
                  if (validName && !pending) startHandleStep()
                }}
              >
                <div className="space-y-1.5">
                  <Label htmlFor="display-name" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                    Display name
                  </Label>
                  <Input
                    id="display-name"
                    ref={inputRef}
                    value={name}
                    onChange={(e) => handleNameChange(e.target.value.slice(0, NAME_MAX))}
                    placeholder="What should people call you?"
                    maxLength={NAME_MAX}
                    autoComplete="off"
                    autoCorrect="off"
                    spellCheck={false}
                    enterKeyHint="go"
                    aria-invalid={nameTaken || undefined}
                    className={cn(
                      'h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800',
                      nameTaken && 'border-amber-400 focus-visible:ring-amber-500/50 dark:border-amber-500/60',
                    )}
                  />
                  {nameTaken ? (
                    <motion.p
                      initial={{ opacity: 0, y: -3 }}
                      animate={{ opacity: 1, y: 0 }}
                      className="flex items-center gap-1.5 text-xs font-medium text-amber-600 dark:text-amber-400"
                      role="status"
                    >
                      <CircleAlert className="size-3.5 shrink-0" aria-hidden />
                      Already on Pulse as “{name.trim()}”?
                    </motion.p>
                  ) : null}
                </div>

                <div className="space-y-2">
                  <Label className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                    Avatar color
                  </Label>
                  <div role="radiogroup" aria-label="Avatar color" className="flex items-center justify-between">
                    {PULSE_COLORS.map((c) => {
                      const selected = c === color
                      return (
                        <button
                          key={c}
                          type="button"
                          role="radio"
                          aria-checked={selected}
                          aria-label={`${c} avatar`}
                          onClick={() => setColor(c)}
                          className={cn(
                            'flex size-9 items-center justify-center rounded-full bg-gradient-to-br shadow-sm outline-none transition-transform active:scale-90',
                            AVATAR_GRADIENTS[c],
                            selected
                              ? 'ring-2 ring-emerald-600 ring-offset-2 ring-offset-white dark:ring-offset-zinc-900 scale-105'
                              : 'hover:scale-105',
                          )}
                        >
                          {selected ? <Check className="size-4 text-white" strokeWidth={3} /> : null}
                        </button>
                      )
                    })}
                  </div>
                </div>

                <Button
                  type="submit"
                  disabled={!validName || pending}
                  className="h-12 w-full rounded-xl bg-emerald-600 text-[15px] font-semibold tracking-tight text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 active:scale-[0.98]"
                >
                  Continue
                  <ArrowRight className="size-4" aria-hidden />
                </Button>

                {nameTaken ? (
                  <Button
                    type="button"
                    disabled={!validName || login.isPending}
                    onClick={() => login.mutate()}
                    className="h-11 w-full rounded-xl border border-emerald-500/50 bg-emerald-500/10 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-500/20 active:scale-[0.98] dark:text-emerald-400"
                  >
                    {login.isPending ? (
                      <>
                        <LoaderCircle className="size-4 animate-spin" aria-hidden />
                        Signing you in…
                      </>
                    ) : (
                      <>
                        <LogIn className="size-4" aria-hidden />
                        That&apos;s me — log in instead
                      </>
                    )}
                  </Button>
                ) : null}
              </form>
            </motion.div>
          ) : (
            <motion.div
              key="step-handle"
              initial={{ opacity: 0, x: 24 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -24 }}
              transition={{ type: 'spring', stiffness: 380, damping: 34 }}
              className="w-full"
            >
              {/* ── step 2: pick a @handle ── */}
              <form
                className="w-full space-y-4"
                onSubmit={(event) => {
                  event.preventDefault()
                  if (validHandle && !checking && !checkTaken && !pending && !serverTaken) {
                    create.mutate({ username: trimmedHandle })
                  }
                }}
              >
                <div className="flex items-center gap-1.5">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Back to name step"
                    onClick={() => setStep('name')}
                    className="size-9 shrink-0 rounded-full text-zinc-500 hover:bg-zinc-100 active:scale-90 dark:text-zinc-400 dark:hover:bg-zinc-800"
                  >
                    <ArrowLeft className="size-4.5" aria-hidden />
                  </Button>
                  <div>
                    <p className="text-sm font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                      Pick your handle
                    </p>
                    <p className="text-[11px] text-zinc-500 dark:text-zinc-400">
                      Creating account for “{name.trim()}” — optional, but it makes you findable.
                    </p>
                  </div>
                </div>

                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <Label htmlFor="handle-input" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                      @handle
                    </Label>
                    <span className="text-[10px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                      optional
                    </span>
                  </div>
                  <div className="relative">
                    <span
                      aria-hidden
                      className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-[15px] font-semibold text-zinc-400 dark:text-zinc-500"
                    >
                      @
                    </span>
                    <Input
                      id="handle-input"
                      value={handle}
                      onChange={(e) => {
                        setServerTaken(null)
                        setHandle(sanitizeHandleInput(e.target.value))
                      }}
                      placeholder="e.g. alice_chen"
                      maxLength={USERNAME_MAX}
                      autoComplete="off"
                      autoCorrect="off"
                      autoCapitalize="none"
                      spellCheck={false}
                      enterKeyHint="go"
                      aria-invalid={(checkTaken || serverTaken !== null) || undefined}
                      aria-describedby="handle-availability"
                      className={cn(
                        'h-11 rounded-xl border-zinc-200 bg-zinc-50 pl-8 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800',
                        (checkTaken || serverTaken !== null) &&
                          'border-amber-400 focus-visible:ring-amber-500/50 dark:border-amber-500/60',
                      )}
                    />
                  </div>

                  {/* live availability line */}
                  <p
                    id="handle-availability"
                    role="status"
                    aria-live="polite"
                    className="flex min-h-[18px] items-center gap-1.5 text-xs font-medium"
                  >
                    {trimmedHandle.length === 0 ? (
                      <span className="text-zinc-400 dark:text-zinc-500">
                        Skip it if you prefer — you can add one later in Profile.
                      </span>
                    ) : !validHandle ? (
                      <span className="text-zinc-500 dark:text-zinc-400">
                        {USERNAME_MIN}–{USERNAME_MAX} characters: lowercase letters, digits, underscore.
                      </span>
                    ) : checking ? (
                      <span className="flex items-center gap-1.5 text-zinc-500 dark:text-zinc-400">
                        <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                        Checking @{trimmedHandle}…
                      </span>
                    ) : checkAvailable ? (
                      <motion.span
                        initial={{ opacity: 0, y: -3 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400"
                      >
                        <Check className="size-3.5" strokeWidth={3} aria-hidden />
                        @{trimmedHandle} is free!
                      </motion.span>
                    ) : checkTaken ? (
                      <motion.span
                        initial={{ opacity: 0, y: -3 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400"
                      >
                        <X className="size-3.5" strokeWidth={3} aria-hidden />
                        @{trimmedHandle} is taken
                        {liveSuggestion ? (
                          <button
                            type="button"
                            onClick={() => {
                              setServerTaken(null)
                              setHandle(sanitizeHandleInput(liveSuggestion))
                            }}
                            className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
                          >
                            Use @{liveSuggestion}
                          </button>
                        ) : null}
                      </motion.span>
                    ) : serverTaken ? (
                      <span className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400">
                        <X className="size-3.5" strokeWidth={3} aria-hidden />
                        {serverTaken.message}
                        {serverTaken.suggestion ? (
                          <button
                            type="button"
                            onClick={() => {
                              setServerTaken(null)
                              setHandle(sanitizeHandleInput(serverTaken.suggestion ?? ''))
                            }}
                            className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
                          >
                            Use @{serverTaken.suggestion}
                          </button>
                        ) : null}
                      </span>
                    ) : null}
                  </p>
                </div>

                <div className="space-y-2">
                  <Button
                    type="submit"
                    disabled={!validHandle || checking || checkTaken || pending || serverTaken !== null}
                    className="h-12 w-full rounded-xl bg-emerald-600 text-[15px] font-semibold tracking-tight text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 active:scale-[0.98]"
                  >
                    {pending ? (
                      <>
                        <LoaderCircle className="size-4 animate-spin" aria-hidden />
                        Creating your account…
                      </>
                    ) : (
                      <>
                        Start chatting
                        <ArrowRight className="size-4" aria-hidden />
                      </>
                    )}
                  </Button>
                  <Button
                    type="button"
                    disabled={pending}
                    onClick={() => create.mutate({ username: null })}
                    variant="ghost"
                    className="h-10 w-full rounded-xl text-sm font-semibold text-zinc-500 hover:bg-zinc-100 hover:text-zinc-700 active:scale-[0.98] dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-200"
                  >
                    Skip for now
                  </Button>
                </div>
              </form>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="mt-1 w-full rounded-2xl border border-dashed border-zinc-200 bg-zinc-50 p-3 dark:border-zinc-700 dark:bg-zinc-800/60">
          <p className="flex items-start gap-2 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
            <Sparkles className="mt-0.5 size-3.5 shrink-0 text-emerald-500" aria-hidden />
            Tip: open this preview in two browser tabs to watch messages fly between accounts.
          </p>
        </div>
      </div>
    </div>
  )
}

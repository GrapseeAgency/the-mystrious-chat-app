// ─────────────────────────────────────────────────────────────
// Pulse Chat — onboarding: pick a display name + avatar color,
// creates a real user via POST /api/users.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState } from 'react'
import Image from 'next/image'
import { motion } from 'framer-motion'
import { useMutation } from '@tanstack/react-query'
import { ArrowRight, Check, LoaderCircle, Sparkles } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
  apiJson,
  jsonBody,
  type AvatarColor,
} from '@/lib/pulse-utils'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

interface CreateUserResponse {
  user: AppUser
}

const NAME_MAX = 32

export function OnboardingScreen() {
  const setUser = usePulseSession((s) => s.setUser)
  const [name, setName] = useState('')
  const [color, setColor] = useState<AvatarColor>('emerald')
  const inputRef = useRef<HTMLInputElement>(null)

  // support deep-link prefill: /?login=Maya%20Chen
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const prefill = params.get('login')?.trim() ?? ''
    const frame = requestAnimationFrame(() => {
      if (prefill.length > 0) {
        setName(prefill.slice(0, NAME_MAX))
      }
      inputRef.current?.focus()
    })
    return () => cancelAnimationFrame(frame)
  }, [])

  const createUser = useMutation({
    mutationFn: async () => {
      return apiJson<CreateUserResponse>(
        '/api/users',
        jsonBody({ name: name.trim(), color }),
      )
    },
    onSuccess: (data) => {
      setUser(data.user)
      toast.success(`Welcome to Pulse, ${data.user.name}!`)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not create your account')
    },
  })

  const validName = name.trim().length > 0 && name.trim().length <= NAME_MAX
  const pending = createUser.isPending

  return (
    <div className="flex h-full flex-col overflow-y-auto pulse-scroll px-6 pb-[max(1.5rem,env(safe-area-inset-bottom))] pt-[max(2rem,env(safe-area-inset-top))]">
      <motion.div
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.28, ease: 'easeOut' }}
        className="flex flex-1 flex-col items-center justify-center gap-5"
      >
        <Image
          src="/onboarding-hero.png"
          alt="Pulse messenger illustration"
          width={196}
          height={196}
          priority
          className="rounded-3xl shadow-lg shadow-emerald-500/10"
        />

        <div className="text-center">
          <h1 className="flex items-center justify-center gap-1.5 text-[26px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Pulse
            <span aria-hidden className="inline-block size-2 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600" />
          </h1>
          <p className="mt-1 text-[13px] font-medium text-zinc-500 dark:text-zinc-400">
            Your conversations, instantly alive.
          </p>
        </div>

        <form
          className="mt-1 w-full space-y-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (validName && !pending) createUser.mutate()
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
              onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
              placeholder="What should people call you?"
              maxLength={NAME_MAX}
              autoComplete="off"
              autoCorrect="off"
              spellCheck={false}
              enterKeyHint="go"
              className="h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
            />
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
        </form>

        <div className="mt-1 w-full rounded-2xl border border-dashed border-zinc-200 bg-zinc-50 p-3 dark:border-zinc-700 dark:bg-zinc-800/60">
          <p className="flex items-start gap-2 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
            <Sparkles className="mt-0.5 size-3.5 shrink-0 text-emerald-500" aria-hidden />
            Tip: open this preview in two browser tabs to watch messages fly between accounts.
          </p>
        </div>
      </motion.div>
    </div>
  )
}

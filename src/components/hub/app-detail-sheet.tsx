// ─────────────────────────────────────────────────────────────
// Pulse Hub — App detail subpage + the real install plumbing.
// appId = String(MATRIX app.n) · GET/POST/DELETE
// /api/hub/apps/[appId]/install — optimistic flip w/ rollback.
// Also hosts the shared "installed set" cache (client-known
// appIds) that the Apps panel tiles + My apps view read from.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect } from 'react'
import {
  animate,
  motion,
  useMotionValue,
  useTransform,
} from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check, Loader2, Plus, RotateCcw, Users } from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { apiJson, initialsOf } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { MATRIX, MATRIX_TO_NAV, type MatrixApp } from '@/lib/hub-catalog'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/chat/user-avatar'

// ── Install API contract ─────────────────────────────────────

export interface InstallInstaller {
  id: string
  name: string
  username: string | null
  color: string
}

export interface InstallStatus {
  installed: boolean
  status: string | null
  installedAt: string | null
  installs: number
  installers: InstallInstaller[]
}

const installedSetKey = (meId: string) => ['hub-apps-installed-set', meId] as const
const perAppKey = (appId: string, meId: string) => ['hub-app-install', appId, meId] as const

/**
 * Client-known set of connected appIds. Hydrated by the 100-app
 * Promise.all on "My apps" chip activation (cached 60s), kept
 * fresh by connect/disconnect mutations + per-app detail fetches.
 */
export async function fetchInstalledSet(meId: string): Promise<string[]> {
  const results = await Promise.all(
    MATRIX.map(async (app) => {
      const res = await apiJson<{ installed: boolean }>(
        `/api/hub/apps/${app.n}/install?userId=${encodeURIComponent(meId)}`,
      )
      return res.installed ? String(app.n) : null
    }),
  )
  return results.filter((id): id is string => id !== null)
}

/** Fetch-on-demand hydration — dedupes, respects a 60s freshness window. */
export function hydrateInstalledSet(qc: QueryClient, meId: string): Promise<string[]> {
  return qc.fetchQuery({
    queryKey: installedSetKey(meId),
    queryFn: () => fetchInstalledSet(meId),
    staleTime: 60_000,
  })
}

/** Merge one app's server truth into the client-known set. */
export function patchInstalledSet(qc: QueryClient, meId: string, appId: string, installed: boolean): void {
  qc.setQueryData<string[]>(installedSetKey(meId), (prev) => {
    if (!installed && prev === undefined) return undefined // never materialize a false-empty set
    const base = prev ?? []
    const has = base.includes(appId)
    if (installed && !has) return [...base, appId]
    if (!installed && has) return base.filter((id) => id !== appId)
    return base
  })
}

/** Cache-only subscription (enabled: false — data lands via hydrate/patches). */
export function useInstalledSet(meId: string) {
  return useQuery({
    queryKey: installedSetKey(meId),
    queryFn: () => fetchInstalledSet(meId),
    enabled: false,
    staleTime: 60_000,
  })
}

/** Per-app install status — GET truth; also patches the client-known set. */
export function useAppInstallStatus(appId: string, meId: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: perAppKey(appId, meId),
    queryFn: async () => {
      const res = await apiJson<InstallStatus>(
        `/api/hub/apps/${appId}/install?userId=${encodeURIComponent(meId)}`,
      )
      patchInstalledSet(qc, meId, appId, res.installed)
      return res
    },
    staleTime: 30_000,
  })
}

/**
 * Connect (POST) / disconnect (DELETE) with optimistic flip +
 * rollback + error toast. Success shows as the UI flip itself
 * (check pulse on tiles/detail), plus a short confirmation toast.
 */
export function useInstallToggle(app: { n: number; name: string }, meId: string) {
  const qc = useQueryClient()
  const appId = String(app.n)
  return useMutation({
    mutationFn: async (next: boolean) => {
      const res = await apiJson<{ installed: boolean; installs: number }>(
        `/api/hub/apps/${appId}/install`,
        { method: next ? 'POST' : 'DELETE', body: JSON.stringify({ userId: meId }) },
      )
      return res
    },
    onMutate: async (next) => {
      await qc.cancelQueries({ queryKey: perAppKey(appId, meId) })
      const prevStatus = qc.getQueryData<InstallStatus>(perAppKey(appId, meId))
      const prevSet = qc.getQueryData<string[]>(installedSetKey(meId))
      if (prevStatus) {
        qc.setQueryData<InstallStatus>(perAppKey(appId, meId), {
          ...prevStatus,
          installed: next,
          status: next ? 'connected' : null,
          installedAt: next ? (prevStatus.installedAt ?? new Date().toISOString()) : null,
          installs: Math.max(0, prevStatus.installs + (next ? 1 : -1)),
        })
      } else if (next) {
        qc.setQueryData<InstallStatus>(perAppKey(appId, meId), {
          installed: true,
          status: 'connected',
          installedAt: new Date().toISOString(),
          installs: 1,
          installers: [],
        })
      }
      patchInstalledSet(qc, meId, appId, next)
      return { prevStatus, prevSet }
    },
    onError: (err: Error, _next, ctx) => {
      if (ctx?.prevStatus !== undefined) qc.setQueryData(perAppKey(appId, meId), ctx.prevStatus)
      else qc.removeQueries({ queryKey: perAppKey(appId, meId) })
      if (ctx?.prevSet !== undefined) qc.setQueryData(installedSetKey(meId), ctx.prevSet)
      else qc.removeQueries({ queryKey: installedSetKey(meId) })
      toast.error(err instanceof Error ? err.message : 'Could not update the connection')
    },
    onSuccess: (res) => {
      toast.success(res.installed ? `Connected to ${app.name}` : `Disconnected from ${app.name}`)
      qc.setQueryData<InstallStatus>(perAppKey(appId, meId), (old) =>
        old ? { ...old, installed: res.installed, installs: res.installs } : old,
      )
    },
    onSettled: () => {
      // marks stale even without observers → next detail mount refetches truth
      void qc.invalidateQueries({ queryKey: perAppKey(appId, meId) })
    },
  })
}

// ── Shared micro-bits ────────────────────────────────────────

/** 300ms count-up (framer motion values — no re-render churn). */
export function CountUp({ value, className }: { value: number; className?: string }) {
  const mv = useMotionValue(0)
  const text = useTransform(mv, (v) => Math.round(v).toLocaleString())
  useEffect(() => {
    const controls = animate(mv, value, { duration: 0.3, ease: 'easeOut' })
    return () => controls.stop()
  }, [value, mv])
  return <motion.span className={className}>{text}</motion.span>
}

/** Spotlight-grade loading dots. */
export function SkeletonDots({ className, label = 'Loading' }: { className?: string; label?: string }) {
  return (
    <div className={cn('flex items-center gap-1.5', className)} role="status" aria-label={label}>
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          className="size-1.5 rounded-full bg-zinc-400 dark:bg-zinc-500"
          animate={{ opacity: [0.25, 1, 0.25], scale: [0.85, 1.15, 0.85] }}
          transition={{ duration: 1.1, repeat: Infinity, delay: i * 0.18, ease: 'easeInOut' }}
        />
      ))}
    </div>
  )
}

/** Error card with retry — used by the detail stats and the My apps view. */
export function LoadErrorCard({ onRetry, message }: { onRetry: () => void; message?: string }) {
  return (
    <div
      role="alert"
      className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-rose-400/50 bg-rose-500/5 px-4 py-5 text-center"
    >
      <p className="text-[13px] font-medium text-rose-600 dark:text-rose-400">
        {message ?? 'Could not load connection data.'}
      </p>
      <Button size="sm" variant="outline" onClick={onRetry} className="h-9 gap-1.5">
        <RotateCcw className="size-3.5" /> Retry
      </Button>
    </div>
  )
}

/** Up to 6 most-recent installers (real users) + "+N" overflow. */
function InstallerStack({ installers, extra }: { installers: InstallInstaller[]; extra: number }) {
  if (installers.length === 0) return null
  return (
    <div className="flex shrink-0 items-center">
      <div className="flex -space-x-2">
        {installers.map((u) => (
          <UserAvatar
            key={u.id}
            name={u.name}
            color={u.color}
            size={26}
            className="rounded-full ring-2 ring-white dark:ring-zinc-900"
          />
        ))}
        {extra > 0 ? (
          <div className="flex size-[26px] items-center justify-center rounded-full bg-zinc-200 text-[9px] font-bold text-zinc-600 ring-2 ring-white dark:bg-zinc-700 dark:text-zinc-300 dark:ring-zinc-900">
            +{extra}
          </div>
        ) : null}
      </div>
    </div>
  )
}

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

// ── The detail subpage ───────────────────────────────────────

export function AppDetailSheet({ app, me, onClose }: { app: MatrixApp; me: AppUser; onClose: () => void }) {
  const appId = String(app.n)
  const statusQ = useAppInstallStatus(appId, me.id)
  const toggle = useInstallToggle(app, me.id)
  const status = statusQ.data
  const installed = Boolean(status?.installed)

  return (
    <div className="flex h-full flex-col bg-background" aria-label={`${app.name} app details`}>
      <div className="flex shrink-0 items-center gap-2 border-b border-zinc-200/80 px-3 py-2.5 dark:border-white/10">
        <Button variant="ghost" size="sm" className="h-10 px-3" onClick={onClose}>
          ‹ Back
        </Button>
        <p className="truncate text-sm font-bold">
          #{String(app.n).padStart(3, '0')} · {app.name}
        </p>
        {installed ? (
          <span className="ml-auto inline-flex shrink-0 items-center gap-1.5 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2.5 py-1 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
            <span className="size-1.5 rounded-full bg-emerald-500" /> Connected
          </span>
        ) : null}
      </div>

      <div className="flex-1 space-y-3 overflow-y-auto p-4">
        {/* live connection hero */}
        <div className="rounded-2xl border border-zinc-200/80 bg-gradient-to-br from-emerald-500/10 via-teal-500/5 to-transparent p-4 dark:border-white/10 dark:from-emerald-500/15 dark:via-transparent">
          <div className="flex items-center gap-3">
            <div
              aria-hidden
              className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-teal-600 text-base font-black text-white shadow-md"
            >
              {initialsOf(app.name)}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-base font-bold">{app.name}</p>
              <p className="mt-0.5 truncate text-[11px] font-medium text-zinc-500">
                #{String(app.n).padStart(3, '0')} · {app.category}
              </p>
            </div>
          </div>

          <div className="mt-3 flex items-end justify-between gap-2">
            <div className="min-h-[42px]">
              {statusQ.isLoading ? (
                <div className="flex h-full flex-col justify-center">
                  <SkeletonDots label="Loading connection stats" />
                </div>
              ) : statusQ.isError || !status ? (
                <p className="text-[11px] font-medium text-rose-500">Connection stats unavailable</p>
              ) : (
                <>
                  <p className="flex items-baseline gap-1.5 text-2xl font-black leading-none tabular-nums text-emerald-600 dark:text-emerald-400">
                    <CountUp value={status.installs} />
                    <Users className="size-4 translate-y-0.5" aria-hidden />
                  </p>
                  <p className="mt-1 text-[11px] font-medium text-zinc-500">
                    member{status.installs === 1 ? '' : 's'} connected
                  </p>
                </>
              )}
            </div>
            <InstallerStack
              installers={status?.installers ?? []}
              extra={Math.max(0, (status?.installs ?? 0) - (status?.installers.length ?? 0))}
            />
          </div>

          {installed && status?.installedAt ? (
            <p className="mt-2.5 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
              Connected on {formatDay(status.installedAt)}
            </p>
          ) : null}

          <Button
            className={cn(
              'mt-3 h-11 w-full text-sm font-bold',
              installed &&
                'border-emerald-500/50 bg-emerald-500/10 text-emerald-700 hover:bg-emerald-500/20 hover:text-emerald-700 dark:text-emerald-400 dark:hover:text-emerald-300',
            )}
            variant={installed ? 'outline' : 'default'}
            disabled={toggle.isPending || statusQ.isLoading}
            onClick={() => toggle.mutate(!installed)}
            aria-pressed={installed}
            aria-label={installed ? `Disconnect from ${app.name}` : `Connect to ${app.name}`}
          >
            {toggle.isPending ? (
              <>
                <Loader2 className="mr-1.5 size-4 animate-spin" aria-hidden />
                {installed ? 'Disconnecting…' : 'Connecting…'}
              </>
            ) : installed ? (
              <>
                <motion.span
                  key="check"
                  className="relative mr-1.5 flex size-2"
                  initial={{ scale: 0.4 }}
                  animate={{ scale: [1.7, 1] }}
                  transition={{ type: 'spring', stiffness: 500, damping: 18 }}
                >
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-50" />
                  <span className="relative inline-flex size-2 rounded-full bg-emerald-500" />
                </motion.span>
                <Check className="mr-0.5 size-4" aria-hidden />
                Connected — tap to disconnect
              </>
            ) : (
              <>
                <Plus className="mr-1.5 size-4" aria-hidden /> Connect
              </>
            )}
          </Button>
        </div>

        {/* blueprint fields */}
        <div className="rounded-xl border border-zinc-200/80 p-3 dark:border-white/10">
          <p className="text-[11px] font-semibold uppercase text-zinc-500">Mobile nav style</p>
          <p className="mt-1 text-sm font-medium">{app.nav}</p>
        </div>
        <div className="rounded-xl border border-zinc-200/80 p-3 dark:border-white/10">
          <p className="text-[11px] font-semibold uppercase text-zinc-500">Input toolkit</p>
          <p className="mt-1 text-sm font-medium">{app.input}</p>
        </div>
        <div className="rounded-xl border border-zinc-200/80 p-3 dark:border-white/10">
          <p className="text-[11px] font-semibold uppercase text-zinc-500">Sub-page category</p>
          <p className="mt-1 text-sm font-medium">{app.category}</p>
        </div>
        <div className="rounded-xl border border-emerald-300/60 bg-emerald-50 p-3 dark:border-emerald-500/30 dark:bg-emerald-950/30">
          <p className="text-[11px] font-semibold uppercase text-emerald-700 dark:text-emerald-400">
            Secret UI architecture feature
          </p>
          <p className="mt-1 text-sm font-medium">{app.secret}</p>
        </div>
        <div className="rounded-xl border border-dashed border-zinc-300 p-3 text-center text-[11px] text-zinc-500 dark:border-white/15">
          Pulse implements this app&apos;s nav pattern as{' '}
          <strong className="text-emerald-600 dark:text-emerald-400">{MATRIX_TO_NAV[app.nav]}</strong> — switch it live
          from the Profile → Navigation panel.
        </div>

        {statusQ.isError ? (
          <LoadErrorCard onRetry={() => void statusQ.refetch()} />
        ) : null}
      </div>
    </div>
  )
}

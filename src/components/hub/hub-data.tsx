// ─────────────────────────────────────────────────────────────
// Pulse Hub — shared data plumbing (R27-b). Real install/community/
// wallet contracts + the shared "installed set" cache, extracted
// from the R19-e detail sheet so the hub sub-pages (category page,
// app page, root tiles) all read one source of truth.
// Zero mocks — every value comes from /api/hub/* → Prisma.
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
import { RotateCcw } from 'lucide-react'
import type { ConversationDetail, WalletState } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { MATRIX } from '@/lib/hub-catalog'
import { Button } from '@/components/ui/button'

// ── Install API contract ─────────────────────────────────────
// GET    /api/hub/apps/[appId]/install?userId= → { installed, status, installedAt, installs, installers }
// POST   /api/hub/apps/[appId]/install { userId } → { installed: true, installs }
// DELETE /api/hub/apps/[appId]/install { userId } → { installed: false, installs }
// (AppInstall.status is connected|muted in the schema — no mute
// endpoint ships yet, so the UI never fakes one.)

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
 * Promise.all on "My apps" / category pages (cached 60s), kept
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

// ── Community API contract ───────────────────────────────────
// GET  /api/hub/apps/[appId]/community?userId= →
//   { conversation: ConversationDetail | null, memberCount, joined }
//   (conversation === null → nobody has joined yet — founder moment)
// POST /api/hub/apps/[appId]/community { userId } →
//   { conversation: ConversationDetail, joined: true }
//   (auto-provisions the group on first join; founder = admin)

export interface AppCommunityState {
  conversation: ConversationDetail | null
  memberCount: number
  joined: boolean
}

const communityKey = (appId: string, meId: string) => ['app-community', appId, meId] as const

export type AppCommunityQuery = ReturnType<typeof useAppCommunity>
export type JoinCommunityMutation = ReturnType<typeof useJoinCommunity>

/** Live app-community state — refetches on window focus. */
export function useAppCommunity(appId: string, meId: string) {
  return useQuery({
    queryKey: communityKey(appId, meId),
    queryFn: () =>
      apiJson<AppCommunityState>(
        `/api/hub/apps/${appId}/community?userId=${encodeURIComponent(meId)}`,
      ),
    staleTime: 15_000,
    refetchOnWindowFocus: true,
  })
}

/**
 * Join (or found) the community — POST is idempotent on the server.
 * onSuccess reconciles the query cache with server truth, teaches
 * the chat list about the room, toasts, then hands the conversation
 * id to `onJoined` (→ auto-open in the main chat surface).
 * Hook-level callbacks keep firing even if the page unmounts on
 * navigation, so the open-chat handoff is never dropped.
 */
export function useJoinCommunity(
  app: { n: number; name: string },
  meId: string,
  opts?: { onJoined?: (conversationId: string) => void },
) {
  const qc = useQueryClient()
  const appId = String(app.n)
  return useMutation({
    mutationFn: async () => {
      const prior = qc.getQueryData<AppCommunityState>(communityKey(appId, meId))
      const res = await apiJson<{ conversation: ConversationDetail; joined: boolean }>(
        `/api/hub/apps/${appId}/community`,
        { method: 'POST', body: JSON.stringify({ userId: meId }) },
      )
      return { res, founding: (prior?.conversation ?? null) === null }
    },
    onSuccess: ({ res, founding }) => {
      const name =
        res.conversation.name ?? `#${String(app.n).padStart(3, '0')} · ${app.name} community`
      toast.success(`${founding ? 'Founded' : 'Joined'} ${name}`)
      qc.setQueryData<AppCommunityState>(communityKey(appId, meId), {
        conversation: res.conversation,
        memberCount: res.conversation.members.length,
        joined: true,
      })
      void qc.invalidateQueries({ queryKey: communityKey(appId, meId) })
      void qc.invalidateQueries({ queryKey: ['conversations', meId] }) // chat list learns the room
      opts?.onJoined?.(res.conversation.id)
    },
    onError: (err: Error) => {
      toast.error(err instanceof Error ? err.message : 'Could not join the community')
    },
  })
}

// ── Wallet (mini) contract ───────────────────────────────────
// GET /api/hub/wallet?userId= → { wallet, ledger } — the mini query
// reuses the exact cache key the Market panel uses, so both stay
// in sync from one network truth.

const walletMiniKey = (meId: string) => ['hub-wallet-mini', meId] as const

export function useWalletMini(meId: string) {
  return useQuery({
    queryKey: walletMiniKey(meId),
    queryFn: async () => {
      const res = await apiJson<{ wallet: WalletState }>(
        `/api/hub/wallet?userId=${encodeURIComponent(meId)}`,
      )
      return res.wallet
    },
    refetchInterval: 30_000,
    staleTime: 15_000,
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

/** Error card with retry — used by the sub-pages and the My apps view. */
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

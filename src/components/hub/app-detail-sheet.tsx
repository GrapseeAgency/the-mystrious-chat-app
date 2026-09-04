// ─────────────────────────────────────────────────────────────
// Pulse Hub — App detail subpage (3-tab dense deck) + the real
// install & community plumbing.
//   Overview   — blueprint fields + live install card (R19-e)
//   Community  — real app community group (GET/POST
//                /api/hub/apps/[appId]/community) → open in chats
//   Connectors — live installer rows (GET /api/hub/apps/[appId]/install)
// appId = String(MATRIX app.n) · zero mocks — every value from Prisma.
// Also hosts the shared "installed set" cache (client-known appIds)
// that the Apps panel tiles + My apps view read from.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useState } from 'react'
import {
  AnimatePresence,
  animate,
  motion,
  useMotionValue,
  useTransform,
} from 'framer-motion'
import type { Variants } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Cable,
  Check,
  Crown,
  Loader2,
  Megaphone,
  MessagesSquare,
  Plus,
  RotateCcw,
  Sparkles,
  UserPlus,
  Users,
} from 'lucide-react'
import type { AppUser, ConversationDetail } from '@/lib/types'
import { apiJson, initialsOf } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { MATRIX, MATRIX_TO_NAV, type MatrixApp } from '@/lib/hub-catalog'
import { Badge } from '@/components/ui/badge'
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
 * Hook-level callbacks keep firing even if the sheet unmounts on
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

/** Spring-pops whenever the value changes (join-success count pulse). */
function CountPulse({ value, className }: { value: number; className?: string }) {
  return (
    <motion.span
      key={value}
      className={cn('inline-block', className)}
      initial={{ scale: 1.4, opacity: 0.35 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ type: 'spring', stiffness: 520, damping: 15 }}
    >
      <CountUp value={value} />
    </motion.span>
  )
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

/** Error card with retry — used by the detail tabs and the My apps view. */
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

/** Stagger-in choreography for dense rows (members, connectors). */
const staggerParent: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045 } },
}
const staggerChild: Variants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring', stiffness: 460, damping: 32 } },
}

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Relative stamp for the viewer's own connector row ("3h ago"). */
function formatRelative(iso: string, now: Date = new Date()): string {
  const ms = now.getTime() - new Date(iso).getTime()
  if (!Number.isFinite(ms)) return ''
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return formatDay(iso)
}

// ── Tab chrome ───────────────────────────────────────────────

type DetailTab = 'overview' | 'community' | 'connectors'

interface DetailTabDef {
  id: DetailTab
  label: string
  badge?: number
}

function DetailTabBar({
  active,
  onChange,
  tabs,
  appName,
}: {
  active: DetailTab
  onChange: (tab: DetailTab) => void
  tabs: DetailTabDef[]
  appName: string
}) {
  return (
    <div
      role="tablist"
      aria-label={`${appName} detail sections`}
      className="relative flex shrink-0 border-b border-zinc-200/80 px-2 dark:border-white/10"
    >
      {tabs.map((t) => {
        const selected = active === t.id
        return (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={`app-detail-tab-${t.id}`}
            aria-selected={selected}
            aria-controls={`app-detail-panel-${t.id}`}
            onClick={() => onChange(t.id)}
            className={cn(
              'relative flex h-11 min-w-[44px] flex-1 items-center justify-center gap-1.5 text-[12.5px] font-semibold transition-colors',
              selected
                ? 'text-emerald-600 dark:text-emerald-400'
                : 'text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200',
            )}
          >
            {t.label}
            {t.badge !== undefined && t.badge > 0 ? (
              <motion.span
                key={t.badge}
                className="inline-flex"
                initial={{ scale: 1.4 }}
                animate={{ scale: 1 }}
                transition={{ type: 'spring', stiffness: 520, damping: 16 }}
              >
                <Badge
                  variant="secondary"
                  className="h-[17px] min-w-[17px] rounded-full border-transparent bg-zinc-200/80 px-1.5 py-0 text-[10px] font-bold tabular-nums text-zinc-600 dark:bg-white/10 dark:text-zinc-300"
                >
                  {t.badge}
                </Badge>
              </motion.span>
            ) : null}
            {selected ? (
              <motion.span
                layoutId="app-detail-tab-underline"
                className="absolute inset-x-4 bottom-0 h-[2.5px] rounded-full bg-gradient-to-r from-emerald-400 to-teal-500"
                transition={{ type: 'spring', stiffness: 500, damping: 36 }}
                aria-hidden
              />
            ) : null}
          </button>
        )
      })}
    </div>
  )
}

// ── Overview tab (blueprint + live install card) ─────────────

interface TabPanelBaseProps {
  app: MatrixApp
  me: AppUser
  statusQ: ReturnType<typeof useAppInstallStatus>
  toggle: ReturnType<typeof useInstallToggle>
}

function OverviewPanel({ app, statusQ, toggle }: TabPanelBaseProps) {
  const status = statusQ.data
  const installed = Boolean(status?.installed)
  return (
    <>
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

      {statusQ.isError ? <LoadErrorCard onRetry={() => void statusQ.refetch()} /> : null}
    </>
  )
}

// ── Community tab ────────────────────────────────────────────

function CommunitySkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading community"
      className="rounded-2xl border border-zinc-200/80 p-4 dark:border-white/10 dark:bg-zinc-900/60"
    >
      <div className="flex items-center gap-3">
        <div className="size-12 shrink-0 animate-pulse rounded-2xl bg-zinc-200 dark:bg-white/10" />
        <div className="flex-1 space-y-2">
          <div className="h-3.5 w-2/3 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
          <div className="h-2.5 w-1/3 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
        </div>
      </div>
      <div className="mt-4 h-3 w-28 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
      <div className="mt-4 h-11 animate-pulse rounded-xl bg-zinc-200 dark:bg-white/10" />
    </div>
  )
}

function CommunityPanel({
  app,
  me,
  query,
  join,
  onOpenConversation,
  onClose,
}: {
  app: MatrixApp
  me: AppUser
  query: AppCommunityQuery
  join: JoinCommunityMutation
  onOpenConversation?: (conversationId: string) => void
  onClose: () => void
}) {
  const state = query.data
  const conversation = state?.conversation ?? null
  const memberCount = state?.memberCount ?? 0
  const joined = Boolean(state?.joined)
  const fallbackName = `#${String(app.n).padStart(3, '0')} · ${app.name} community`

  if (query.isLoading) return <CommunitySkeleton />
  if (query.isError || !state) {
    return (
      <LoadErrorCard
        onRetry={() => void query.refetch()}
        message="Could not load the community."
      />
    )
  }

  // founder moment — nobody has provisioned this app's group yet
  if (!conversation) {
    return (
      <div className="rounded-2xl border border-dashed border-zinc-300 p-5 text-center dark:border-white/15">
        <div
          aria-hidden
          className="mx-auto flex size-12 items-center justify-center rounded-2xl border border-dashed border-zinc-300 bg-zinc-100/60 dark:border-white/15 dark:bg-white/5"
        >
          <Users className="size-5 text-zinc-400" />
        </div>
        <p className="mt-2.5 text-sm font-bold">Be the first to start the community</p>
        <p className="mt-1 text-[11px] font-medium text-zinc-500">
          No members yet — the room {fallbackName} gets created on first join.
        </p>
        <Button
          className="mt-3 h-11 w-full text-sm font-bold"
          onClick={() => join.mutate()}
          disabled={join.isPending}
          aria-label={`Found the ${fallbackName}`}
        >
          {join.isPending ? (
            <>
              <Loader2 className="mr-1.5 size-4 animate-spin" aria-hidden /> Founding…
            </>
          ) : (
            <>
              <Sparkles className="mr-1.5 size-4" aria-hidden /> Found the community
            </>
          )}
        </Button>
        <p className="mt-2 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
          You&apos;ll be the founding admin.
        </p>
      </div>
    )
  }

  const stack = conversation.members.slice(0, 8)
  const extra = Math.max(0, memberCount - stack.length)
  const list = conversation.members.slice(0, 8)
  const listExtra = Math.max(0, conversation.members.length - list.length)

  return (
    <>
      {/* community identity card */}
      <div className="rounded-2xl border border-zinc-200/80 p-4 dark:border-white/10 dark:bg-zinc-900/60">
        <div className="flex items-center gap-3">
          <div
            aria-hidden
            className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-teal-600 text-base font-black text-white shadow-md"
          >
            {initialsOf(app.name)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-bold">{conversation.name ?? fallbackName}</p>
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              <span
                className={cn(
                  'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-bold',
                  joined
                    ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                    : 'border-zinc-300 text-zinc-500 dark:border-white/15 dark:text-zinc-400',
                )}
              >
                {joined ? 'Member' : 'Not joined'}
              </span>
              {conversation.broadcastMode ? (
                <span className="inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                  <Megaphone className="size-3" aria-hidden /> Admins post only
                </span>
              ) : null}
            </div>
          </div>
        </div>

        <div className="mt-3 flex items-end justify-between gap-2">
          <p className="flex items-baseline gap-1.5">
            <CountPulse
              value={memberCount}
              className="text-2xl font-black leading-none tabular-nums text-emerald-600 dark:text-emerald-400"
            />
            <span className="text-[11px] font-medium text-zinc-500">
              member{memberCount === 1 ? '' : 's'}
            </span>
          </p>
          <div
            className="flex shrink-0 -space-x-2"
            role="img"
            aria-label={`${memberCount} community member${memberCount === 1 ? '' : 's'}`}
          >
            {stack.map((m) => (
              <UserAvatar
                key={m.id}
                name={m.name}
                color={m.color}
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

        {joined ? (
          <>
            <Button
              className="mt-3 h-11 w-full text-sm font-bold"
              onClick={() => {
                onClose()
                onOpenConversation?.(conversation.id)
              }}
              disabled={!onOpenConversation}
              aria-label={`Open ${conversation.name ?? fallbackName} in chats`}
            >
              <MessagesSquare className="mr-1.5 size-4" aria-hidden /> Open chat
            </Button>
            <p className="mt-2 text-center text-[11px] font-medium text-zinc-500">
              Opens {conversation.name ?? fallbackName} in your Chats.
            </p>
          </>
        ) : (
          <>
            <Button
              className="mt-3 h-11 w-full text-sm font-bold"
              onClick={() => join.mutate()}
              disabled={join.isPending}
              aria-label={`Join ${conversation.name ?? fallbackName}`}
            >
              {join.isPending ? (
                <>
                  <Loader2 className="mr-1.5 size-4 animate-spin" aria-hidden /> Joining…
                </>
              ) : (
                <>
                  <UserPlus className="mr-1.5 size-4" aria-hidden /> Join community
                </>
              )}
            </Button>
            <p className="mt-2 text-center text-[11px] font-medium text-zinc-500">
              {memberCount === 1 ? '1 member is' : `${memberCount} members are`} already inside.
            </p>
          </>
        )}
      </div>

      {/* member roster (real participants, ≤8 rows) */}
      <motion.div
        variants={staggerParent}
        initial="hidden"
        animate="show"
        className="overflow-hidden rounded-2xl border border-zinc-200/80 dark:border-white/10 dark:bg-zinc-900/60"
      >
        <div className="flex items-center justify-between border-b border-zinc-200/80 px-4 py-2.5 dark:border-white/10">
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500">Members</p>
          <span className="text-[12px] font-black tabular-nums text-emerald-600 dark:text-emerald-400">
            {conversation.members.length}
          </span>
        </div>
        <motion.div className="divide-y divide-zinc-200/70 dark:divide-white/5">
          {list.map((m) => (
            <motion.div
              key={m.id}
              variants={staggerChild}
              className="flex items-center gap-3 px-4 py-2.5"
            >
              <UserAvatar name={m.name} color={m.color} size={34} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-semibold">
                  {m.name}
                  {m.id === me.id ? <span className="font-normal text-zinc-400"> (you)</span> : null}
                </p>
                <p className="truncate text-[11px] font-medium text-zinc-500">
                  {m.username ? `@${m.username}` : m.about || 'Pulse member'}
                </p>
              </div>
              {m.role === 'admin' ? (
                <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                  <Crown className="size-3" aria-hidden /> Admin
                </span>
              ) : null}
            </motion.div>
          ))}
          {listExtra > 0 ? (
            <div className="px-4 py-2.5 text-center text-[11px] font-medium text-zinc-500">
              +{listExtra} more member{listExtra === 1 ? '' : 's'}
            </div>
          ) : null}
        </motion.div>
      </motion.div>
    </>
  )
}

// ── Connectors tab ───────────────────────────────────────────

function ConnectorsSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading connectors"
      className="overflow-hidden rounded-2xl border border-zinc-200/80 dark:border-white/10 dark:bg-zinc-900/60"
    >
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <div className="size-[34px] shrink-0 animate-pulse rounded-full bg-zinc-200 dark:bg-white/10" />
          <div className="flex-1 space-y-1.5">
            <div className="h-3 w-1/3 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
            <div className="h-2.5 w-1/5 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
          </div>
        </div>
      ))}
    </div>
  )
}

function ConnectorsPanel({ app, me, statusQ, toggle }: TabPanelBaseProps) {
  const status = statusQ.data
  const installed = Boolean(status?.installed)
  const installers = status?.installers ?? []
  const extra = Math.max(0, (status?.installs ?? 0) - installers.length)

  return (
    <>
      {/* viewer's own connect state card */}
      <div
        className={cn(
          'rounded-2xl border p-4',
          installed
            ? 'border-emerald-300/60 bg-emerald-50/70 dark:border-emerald-500/30 dark:bg-emerald-950/30'
            : 'border-zinc-200/80 dark:border-white/10 dark:bg-zinc-900/60',
        )}
      >
        <div className="flex items-center gap-3">
          <div
            aria-hidden
            className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 text-[13px] font-black text-white shadow-sm"
          >
            {initialsOf(app.name)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-bold">Your connection</p>
            <p className="mt-0.5 truncate text-[11px] font-medium text-zinc-500">
              {statusQ.isLoading
                ? 'Checking…'
                : installed && status?.installedAt
                  ? `Connected on ${formatDay(status.installedAt)}`
                  : 'Not connected yet'}
            </p>
          </div>
          <Button
            size="sm"
            className={cn(
              'h-11 min-w-[108px] text-[12.5px] font-bold',
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
              <Loader2 className="size-4 animate-spin" aria-hidden />
            ) : installed ? (
              <>
                <Check className="mr-1 size-4" aria-hidden /> Connected
              </>
            ) : (
              <>
                <Plus className="mr-1 size-4" aria-hidden /> Connect
              </>
            )}
          </Button>
        </div>
      </div>

      {/* live installer roster */}
      {statusQ.isLoading ? (
        <ConnectorsSkeleton />
      ) : statusQ.isError || !status ? (
        <LoadErrorCard
          onRetry={() => void statusQ.refetch()}
          message="Could not load connectors."
        />
      ) : installers.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-zinc-300 p-5 text-center dark:border-white/15">
          <Cable className="mx-auto size-5 text-zinc-400" aria-hidden />
          <p className="mt-2 text-[13px] font-bold">No connectors yet</p>
          <p className="mt-1 text-[11px] font-medium text-zinc-500">
            Be the first to connect {app.name}.
          </p>
        </div>
      ) : (
        <motion.div
          variants={staggerParent}
          initial="hidden"
          animate="show"
          className="overflow-hidden rounded-2xl border border-zinc-200/80 dark:border-white/10 dark:bg-zinc-900/60"
        >
          <div className="flex items-center justify-between border-b border-zinc-200/80 px-4 py-2.5 dark:border-white/10">
            <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500">
              Connected members
            </p>
            <CountUp
              value={status.installs}
              className="text-[12px] font-black tabular-nums text-emerald-600 dark:text-emerald-400"
            />
          </div>
          <motion.div className="divide-y divide-zinc-200/70 dark:divide-white/5">
            {installers.map((u) => {
              const isViewer = u.id === me.id
              return (
                <motion.div
                  key={u.id}
                  variants={staggerChild}
                  className="flex items-center gap-3 px-4 py-2.5"
                >
                  <UserAvatar name={u.name} color={u.color} size={34} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-semibold">
                      {u.name}
                      {isViewer ? <span className="font-normal text-zinc-400"> (you)</span> : null}
                    </p>
                    <p className="truncate text-[11px] font-medium text-zinc-500">
                      {u.username ? `@${u.username}` : 'Pulse member'}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className="text-[10.5px] font-medium text-zinc-500">connected</p>
                    {/* installedAt is per-viewer truth — others get no invented date */}
                    {isViewer && installed && status.installedAt ? (
                      <p className="text-[10px] font-medium text-zinc-400">
                        {formatRelative(status.installedAt)}
                      </p>
                    ) : null}
                  </div>
                </motion.div>
              )
            })}
          </motion.div>
          {extra > 0 ? (
            <div className="border-t border-zinc-200/70 px-4 py-2.5 text-center text-[11px] font-medium text-zinc-500 dark:border-white/5">
              +{extra} more connected
            </div>
          ) : null}
        </motion.div>
      )}
    </>
  )
}

// ── The detail subpage ───────────────────────────────────────

export function AppDetailSheet({
  app,
  me,
  onClose,
  onOpenConversation,
}: {
  app: MatrixApp
  me: AppUser
  onClose: () => void
  /** open the app community conversation in the main chat surface (optional) */
  onOpenConversation?: (conversationId: string) => void
}) {
  const appId = String(app.n)
  const statusQ = useAppInstallStatus(appId, me.id)
  const toggle = useInstallToggle(app, me.id)
  const communityQ = useAppCommunity(appId, me.id)
  const join = useJoinCommunity(app, me.id, {
    onJoined: (conversationId) => onOpenConversation?.(conversationId),
  })
  const [tab, setTab] = useState<DetailTab>('overview')

  const status = statusQ.data
  const installed = Boolean(status?.installed)
  const memberCount = communityQ.data?.memberCount ?? 0
  const installs = status?.installs ?? 0

  const tabs: DetailTabDef[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'community', label: 'Community', badge: memberCount > 0 ? memberCount : undefined },
    { id: 'connectors', label: 'Connectors', badge: installs > 0 ? installs : undefined },
  ]

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

      <DetailTabBar active={tab} onChange={setTab} tabs={tabs} appName={app.name} />

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={tab}
          role="tabpanel"
          id={`app-detail-panel-${tab}`}
          aria-labelledby={`app-detail-tab-${tab}`}
          initial={{ opacity: 0, x: 14 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: -10 }}
          transition={{ duration: 0.16, ease: 'easeOut' }}
          className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4"
        >
          {tab === 'overview' ? (
            <OverviewPanel app={app} me={me} statusQ={statusQ} toggle={toggle} />
          ) : tab === 'community' ? (
            <CommunityPanel
              app={app}
              me={me}
              query={communityQ}
              join={join}
              onOpenConversation={onOpenConversation}
              onClose={onClose}
            />
          ) : (
            <ConnectorsPanel app={app} me={me} statusQ={statusQ} toggle={toggle} />
          )}
        </motion.div>
      </AnimatePresence>
    </div>
  )
}

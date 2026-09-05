// ─────────────────────────────────────────────────────────────
// Pulse Hub — #/hub/app/<appId> app sub-page (R27-b).
// Replaces the R19-e overlay sheet with a REAL hash-routed page:
// accent hero (brand-true gradient + icon tile + tagline), real
// wallet chip, feature list, install/community/connectors with
// optimistic mutations, and a related-apps rail. The "Open" action
// keeps the existing contract — the app's community conversation
// is opened in the main chat surface via onOpenConversation.
// appId = String(MATRIX app.n) · zero mocks — every live value
// comes from /api/hub/* → Prisma. Shared plumbing lives in
// hub-data.tsx; glass primitives in hub-primitives.tsx.
// ─────────────────────────────────────────────────────────────
'use client'

import { useState } from 'react'
import {
  AnimatePresence,
  motion,
  useReducedMotion,
} from 'framer-motion'
import type { Variants } from 'framer-motion'
import {
  BadgeCheck,
  Cable,
  Check,
  Coins,
  Crown,
  Ellipsis,
  Gem,
  Loader2,
  Megaphone,
  MessagesSquare,
  Plus,
  ShieldQuestion,
  Sparkles,
  UserPlus,
  Users,
} from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { buzz } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { backHash, navigateHash } from '@/lib/hash-router'
import {
  MATRIX,
  MATRIX_TO_NAV,
  appAccent,
  appFeatures,
  appTagline,
  slugForCategory,
} from '@/lib/hub-catalog'
import { spring } from '@/lib/motion'
import {
  CountUp,
  LoadErrorCard,
  SkeletonDots,
  useAppCommunity,
  useAppInstallStatus,
  useInstallToggle,
  useJoinCommunity,
  useWalletMini,
} from '@/components/hub/hub-data'
import type { AppCommunityQuery, InstallInstaller, JoinCommunityMutation } from '@/components/hub/hub-data'
import {
  AppIconTile,
  ConnectedBadge,
  HubSubHeader,
  staggerChild,
  staggerParent,
} from '@/components/hub/hub-primitives'
import { GlassMenu, GlassMenuItem, GlassMenuLabel, GlassMenuSeparator } from '@/components/ui/glass-menu'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/chat/user-avatar'

// ── shared micro-bits (page-local) ───────────────────────────

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
            onClick={() => {
              buzz(6)
              onChange(t.id)
            }}
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

// ── Hero (identity + live install card + actions) ────────────

function AppHero({
  appId,
  statusQ,
  toggle,
  join,
  communityQ,
  walletQ,
  onOpenConversation,
}: {
  appId: string
  statusQ: ReturnType<typeof useAppInstallStatus>
  toggle: ReturnType<typeof useInstallToggle>
  join: JoinCommunityMutation
  communityQ: AppCommunityQuery
  walletQ: ReturnType<typeof useWalletMini>
  onOpenConversation?: (conversationId: string) => void
}) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
  const status = statusQ.data
  const installed = Boolean(status?.installed)
  const conversation = communityQ.data?.conversation ?? null
  const joined = Boolean(communityQ.data?.joined)
  const [menuOpen, setMenuOpen] = useState(false)
  const wallet = walletQ.data

  const openChat = () => {
    if (conversation) {
      onOpenConversation?.(conversation.id)
      backHash('/hub') // the sheet used to close itself — the page pops its route
      return
    }
    // not a member yet — join (idempotent, auto-provisions) then hand off
    join.mutate()
  }

  return (
    <div className="glass-deep glass-sheen relative overflow-hidden rounded-2xl p-4">
      {/* accent wash */}
      <div
        aria-hidden
        className="pointer-events-none absolute -right-12 -top-14 size-44 rounded-full opacity-[0.28] blur-2xl"
        style={{ backgroundImage: `linear-gradient(135deg, ${appAccent(app)[0]}, ${appAccent(app)[1]})` }}
      />

      <div className="relative flex items-start gap-3">
        <AppIconTile app={app} size={60} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-[17px] font-black leading-tight">{app.name}</p>
            {installed ? <ConnectedBadge appName={app.name} className="hidden h-6 px-2 text-[10px] sm:inline-flex" /> : null}
          </div>
          <p className="mt-0.5 text-[12.5px] font-medium leading-snug text-zinc-600 dark:text-zinc-300">
            {appTagline(app)}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              onClick={() => navigateHash(`/hub/c/${slugForCategory(app.category)}`)}
              className="glass-pill inline-flex h-7 items-center rounded-full px-2.5 text-[10px] font-bold text-zinc-600 transition-transform active:scale-95 dark:text-zinc-300"
              aria-label={`Open ${app.category} category`}
            >
              {app.category}
            </button>
            <span className="inline-flex h-7 items-center rounded-full border border-zinc-200/80 px-2.5 text-[10px] font-semibold tabular-nums text-zinc-500 dark:border-white/10 dark:text-zinc-400">
              #{String(app.n).padStart(3, '0')}
            </span>
            {wallet ? (
              <span
                className="glass-pill inline-flex h-7 items-center gap-1 rounded-full px-2.5 text-[10px] font-bold text-emerald-700 dark:text-emerald-400"
                aria-label={`Wallet balance ${wallet.coins} Pulse Coins`}
              >
                <Coins className="size-3" aria-hidden /> {wallet.coins.toLocaleString()} PC
                <Gem className="ml-1 size-3 text-sky-500" aria-hidden /> {wallet.gems}
              </span>
            ) : (
              <SkeletonDots className="h-7 items-center px-1" label="Loading wallet" />
            )}
          </div>
        </div>
      </div>

      {/* live install stats */}
      <div className="relative mt-3 flex items-end justify-between gap-2">
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
        <p className="relative mt-1.5 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
          Connected on {formatDay(status.installedAt)}
        </p>
      ) : null}

      {/* actions — install · open · overflow */}
      <div className="relative mt-3 flex items-center gap-2">
        <Button
          className={cn(
            'h-11 flex-1 text-sm font-bold',
            installed &&
              'border-emerald-500/50 bg-emerald-500/10 text-emerald-700 hover:bg-emerald-500/20 hover:text-emerald-700 dark:text-emerald-400 dark:hover:text-emerald-300',
          )}
          variant={installed ? 'outline' : 'default'}
          disabled={toggle.isPending || statusQ.isLoading}
          onClick={() => {
            buzz(12)
            toggle.mutate(!installed)
          }}
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
              Connected
            </>
          ) : (
            <>
              <Plus className="mr-1.5 size-4" aria-hidden /> Connect
            </>
          )}
        </Button>

        <Button
          className="h-11 shrink-0 text-sm font-bold"
          variant="secondary"
          disabled={join.isPending || (communityQ.isLoading && !conversation)}
          onClick={() => {
            buzz(12)
            openChat()
          }}
          aria-label={conversation ? `Open the ${app.name} community chat` : `Join the ${app.name} community chat`}
        >
          {join.isPending ? (
            <Loader2 className="size-4 animate-spin" aria-hidden />
          ) : conversation ? (
            <>
              <MessagesSquare className="mr-1.5 size-4" aria-hidden /> Open
            </>
          ) : (
            <>
              <UserPlus className="mr-1.5 size-4" aria-hidden /> Join
            </>
          )}
        </Button>

        {/* overflow — real destructive actions only (mute has no server contract yet) */}
        <div className="relative shrink-0">
          <button
            type="button"
            aria-label="More actions"
            aria-expanded={menuOpen}
            onClick={() => {
              buzz(8)
              setMenuOpen((v) => !v)
            }}
            className="glass-pill flex size-11 items-center justify-center text-zinc-600 outline-none transition-transform active:scale-90 dark:text-zinc-300"
          >
            <Ellipsis className="size-[18px]" aria-hidden />
          </button>
          <AnimatePresence>
            {menuOpen ? (
              <>
                <div
                  className="fixed inset-0 z-[80]"
                  role="presentation"
                  onClick={() => setMenuOpen(false)}
                />
                <GlassMenu className="absolute right-0 top-[52px] z-[90]" role="menu">
                  <GlassMenuLabel>{app.name}</GlassMenuLabel>
                  <GlassMenuItem
                    icon={BadgeCheck}
                    label={installed ? 'Connection active' : 'Not connected'}
                    trailing={installed ? 'on' : 'off'}
                    active
                    onClick={() => setMenuOpen(false)}
                  />
                  <GlassMenuSeparator />
                  <GlassMenuItem
                    icon={Check}
                    label="Remove connection"
                    destructive
                    disabled={!installed || toggle.isPending}
                    onClick={() => {
                      setMenuOpen(false)
                      toggle.mutate(false)
                    }}
                  />
                </GlassMenu>
              </>
            ) : null}
          </AnimatePresence>
        </div>
      </div>
    </div>
  )
}

// ── Feature list (real matrix fields) ────────────────────────

function FeatureList({ appId }: { appId: string }) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
  const features = appFeatures(app)
  return (
    <motion.section
      variants={staggerParent}
      initial="hidden"
      animate="show"
      aria-label={`${app.name} features`}
      className="glass-deep glass-sheen overflow-hidden rounded-2xl"
    >
      <p className="border-b border-zinc-200/70 px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500 dark:border-white/[0.06]">
        What it ships
      </p>
      <motion.ul className="divide-y divide-zinc-200/60 dark:divide-white/[0.05]">
        {features.map((f, i) => (
          <motion.li
            key={i}
            variants={staggerChild}
            className="flex items-start gap-2.5 px-4 py-2.5"
          >
            {i === features.length - 1 ? (
              <Sparkles className="mt-0.5 size-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
            ) : (
              <Check className="mt-0.5 size-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
            )}
            <span className="text-[12.5px] font-medium leading-snug text-zinc-700 dark:text-zinc-200">{f}</span>
          </motion.li>
        ))}
      </motion.ul>
    </motion.section>
  )
}

// ── Overview tab (blueprint fields) ──────────────────────────

function OverviewPanel({ appId, statusQ }: { appId: string; statusQ: ReturnType<typeof useAppInstallStatus> }) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
  return (
    <>
      <div className="glass-deep glass-sheen rounded-xl p-3">
        <p className="text-[11px] font-semibold uppercase text-zinc-500">Mobile nav style</p>
        <p className="mt-1 text-sm font-medium">{app.nav}</p>
      </div>
      <div className="glass-deep glass-sheen rounded-xl p-3">
        <p className="text-[11px] font-semibold uppercase text-zinc-500">Input toolkit</p>
        <p className="mt-1 text-sm font-medium">{app.input}</p>
      </div>
      <div className="glass-deep glass-sheen rounded-xl border-emerald-300/60 p-3 dark:border-emerald-500/30">
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
      className="glass-deep glass-sheen rounded-2xl p-4"
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
  appId,
  me,
  query,
  join,
  onOpenConversation,
}: {
  appId: string
  me: AppUser
  query: AppCommunityQuery
  join: JoinCommunityMutation
  onOpenConversation?: (conversationId: string) => void
}) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
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
      <div className="glass-deep glass-sheen rounded-2xl border border-dashed border-zinc-300 p-5 text-center dark:border-white/15">
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
      <div className="glass-deep glass-sheen rounded-2xl p-4">
        <div className="flex items-center gap-3">
          <AppIconTile app={app} size={48} />
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
                backHash('/hub')
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
        className="glass-deep glass-sheen overflow-hidden rounded-2xl"
      >
        <div className="flex items-center justify-between border-b border-zinc-200/70 px-4 py-2.5 dark:border-white/[0.06]">
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500">Members</p>
          <span className="text-[12px] font-black tabular-nums text-emerald-600 dark:text-emerald-400">
            {conversation.members.length}
          </span>
        </div>
        <motion.div className="divide-y divide-zinc-200/60 dark:divide-white/[0.05]">
          {list.map((m) => (
            <motion.div
              key={m.id}
              variants={staggerChild}
              className="glass-row-hover flex items-center gap-3 px-4 py-2.5"
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
      className="glass-deep glass-sheen overflow-hidden rounded-2xl"
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

function ConnectorsPanel({
  appId,
  me,
  statusQ,
  toggle,
}: {
  appId: string
  me: AppUser
  statusQ: ReturnType<typeof useAppInstallStatus>
  toggle: ReturnType<typeof useInstallToggle>
}) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
  const status = statusQ.data
  const installed = Boolean(status?.installed)
  const installers = status?.installers ?? []
  const extra = Math.max(0, (status?.installs ?? 0) - installers.length)

  return (
    <>
      {/* viewer's own connect state card */}
      <div
        className={cn(
          'glass-deep glass-sheen rounded-2xl p-4',
          installed && 'border-emerald-300/60 dark:border-emerald-500/30',
        )}
      >
        <div className="flex items-center gap-3">
          <AppIconTile app={app} size={40} />
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
        <div className="glass-deep glass-sheen rounded-2xl border border-dashed border-zinc-300 p-5 text-center dark:border-white/15">
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
          className="glass-deep glass-sheen overflow-hidden rounded-2xl"
        >
          <div className="flex items-center justify-between border-b border-zinc-200/70 px-4 py-2.5 dark:border-white/[0.06]">
            <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500">
              Connected members
            </p>
            <CountUp
              value={status.installs}
              className="text-[12px] font-black tabular-nums text-emerald-600 dark:text-emerald-400"
            />
          </div>
          <motion.div className="divide-y divide-zinc-200/60 dark:divide-white/[0.05]">
            {installers.map((u) => {
              const isViewer = u.id === me.id
              return (
                <motion.div
                  key={u.id}
                  variants={staggerChild}
                  className="glass-row-hover flex items-center gap-3 px-4 py-2.5"
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
            <div className="border-t border-zinc-200/60 px-4 py-2.5 text-center text-[11px] font-medium text-zinc-500 dark:border-white/[0.05]">
              +{extra} more connected
            </div>
          ) : null}
        </motion.div>
      )}
    </>
  )
}

// ── Related apps rail (same category, real install dots) ─────

function RelatedRail({ appId }: { appId: string }) {
  const app = MATRIX.find((a) => String(a.n) === appId)!
  const related = MATRIX.filter((a) => a.category === app.category && a.n !== app.n).slice(0, 10)
  if (related.length === 0) return null

  return (
    <section aria-label="Related apps in this category">
      <div className="flex items-center justify-between px-1">
        <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500">More in {app.category.split(' /')[0]}</p>
        <button
          type="button"
          onClick={() => navigateHash(`/hub/c/${slugForCategory(app.category)}`)}
          className="text-[11px] font-bold text-emerald-600 outline-none hover:underline dark:text-emerald-400"
          aria-label={`Open the ${app.category} category page`}
        >
          See all
        </button>
      </div>
      <motion.ul
        variants={staggerParent}
        initial="hidden"
        animate="show"
        className="pulse-scroll -mx-1 mt-2 flex gap-2 overflow-x-auto px-1 pb-2"
      >
        {related.map((r) => (
          <motion.li key={r.n} variants={staggerChild} className="shrink-0">
            <button
              type="button"
              onClick={() => {
                buzz(10)
                navigateHash(`/hub/app/${r.n}`)
              }}
              aria-label={`Open ${r.name} page`}
              className="glass-deep glass-sheen glass-row-hover flex w-[104px] flex-col items-center gap-1.5 rounded-2xl px-2 py-3 outline-none transition-transform active:scale-[0.96] focus-visible:ring-2 focus-visible:ring-emerald-500/60"
            >
              <AppIconTile app={r} size={44} />
              <span className="w-full truncate text-center text-[11.5px] font-bold">{r.name}</span>
              <span className="text-[9.5px] font-semibold text-zinc-400">#{String(r.n).padStart(3, '0')}</span>
            </button>
          </motion.li>
        ))}
      </motion.ul>
    </section>
  )
}

// ── Page skeleton ────────────────────────────────────────────

function AppPageSkeleton() {
  return (
    <div role="status" aria-label="Loading app" className="flex flex-col gap-3 p-4">
      <div className="glass-deep glass-sheen flex flex-col gap-3 rounded-2xl p-4">
        <div className="flex items-center gap-3">
          <div className="size-[60px] shrink-0 animate-pulse rounded-2xl bg-zinc-200 dark:bg-white/10" />
          <div className="flex-1 space-y-2">
            <div className="h-4 w-1/2 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
            <div className="h-3 w-3/4 animate-pulse rounded bg-zinc-200 dark:bg-white/10" />
            <div className="h-5 w-2/3 animate-pulse rounded-full bg-zinc-200 dark:bg-white/10" />
          </div>
        </div>
        <div className="h-11 animate-pulse rounded-xl bg-zinc-200 dark:bg-white/10" />
      </div>
      <div className="glass-deep glass-sheen h-24 rounded-2xl" />
      <div className="glass-deep glass-sheen h-40 rounded-2xl" />
    </div>
  )
}

// ── The app sub-page ─────────────────────────────────────────

const heroEntrance: Variants = {
  hidden: { opacity: 0, y: 16, scale: 0.985 },
  show: { opacity: 1, y: 0, scale: 1, transition: { ...spring.soft } },
}

export function AppDetailPage({
  appId,
  me,
  onOpenConversation,
  direction = 'forward',
}: {
  appId: string
  me: AppUser
  /** open the app community conversation in the main chat surface */
  onOpenConversation?: (conversationId: string) => void
  /** slide direction for push vs pop (category→app = forward) */
  direction?: 'forward' | 'back'
}) {
  const reduced = Boolean(useReducedMotion())
  const numeric = Number(appId)
  const app = Number.isInteger(numeric) ? MATRIX.find((a) => a.n === numeric) ?? null : null

  const statusQ = useAppInstallStatus(appId, me.id)
  const toggle = useInstallToggle(app ?? { n: 0, name: 'App' }, me.id)
  const communityQ = useAppCommunity(appId, me.id)
  const join = useJoinCommunity(app ?? { n: 0, name: 'App' }, me.id, {
    onJoined: (conversationId) => onOpenConversation?.(conversationId),
  })
  const walletQ = useWalletMini(me.id)
  const [tab, setTab] = useState<DetailTab>('overview')

  // unknown id — honest 404 state, back to the hub
  if (!app) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        className="absolute inset-0 z-[70] flex flex-col bg-background"
        role="region"
        aria-label="Unknown app"
      >
        <HubSubHeader title="Unknown app" onBack={() => backHash('/hub')} backLabel="Back to Hub" />
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-8 text-center">
          <ShieldQuestion className="size-8 text-zinc-400" aria-hidden />
          <p className="text-[13px] font-medium text-zinc-500">
            No platform in the matrix answers to &quot;{appId}&quot;.
          </p>
          <Button size="sm" variant="outline" className="h-9" onClick={() => backHash('/hub')}>
            Back to the Hub
          </Button>
        </div>
      </motion.div>
    )
  }

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
    <motion.div
      initial={reduced ? { opacity: 0 } : direction === 'forward' ? { opacity: 0, x: 44 } : { opacity: 0, x: -44 }}
      animate={{ opacity: 1, x: 0, transition: { type: 'spring', stiffness: 300, damping: 28 } }}
      exit={
        reduced
          ? { opacity: 0, transition: { duration: 0.12 } }
          : direction === 'forward'
            ? { opacity: 0, x: -32, transition: { duration: 0.18, ease: 'easeIn' } }
            : { opacity: 0, x: 32, transition: { duration: 0.18, ease: 'easeIn' } }
      }
      className="absolute inset-0 z-[70] flex flex-col bg-background"
      role="region"
      aria-label={`${app.name} app page`}
    >
      <HubSubHeader
        title={app.name}
        subtitle={app.category}
        onBack={() => backHash('/hub')}
        backLabel={`Back, from ${app.name}`}
        trailing={
          installed ? <ConnectedBadge appName={app.name} className="mr-1 h-7 px-2.5 text-[10px]" /> : undefined
        }
      />

      {statusQ.isLoading && !status ? (
        <div className="min-h-0 flex-1 overflow-y-auto">
          <AppPageSkeleton />
        </div>
      ) : (
        <>
          <DetailTabBar active={tab} onChange={setTab} tabs={tabs} appName={app.name} />

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-10 pt-3">
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
                className="flex flex-col gap-3"
              >
                {tab === 'overview' ? (
                  <>
                    <motion.div variants={reduced ? undefined : heroEntrance} initial={reduced ? false : 'hidden'} animate="show">
                      <AppHero
                        appId={String(app.n)}
                        statusQ={statusQ}
                        toggle={toggle}
                        join={join}
                        communityQ={communityQ}
                        walletQ={walletQ}
                        onOpenConversation={onOpenConversation}
                      />
                    </motion.div>
                    <motion.div variants={reduced ? undefined : heroEntrance} initial={reduced ? false : 'hidden'} animate="show" transition={{ delay: 0.05 }}>
                      <FeatureList appId={String(app.n)} />
                    </motion.div>
                    <RelatedRail appId={String(app.n)} />
                  </>
                ) : tab === 'community' ? (
                  <CommunityPanel
                    appId={String(app.n)}
                    me={me}
                    query={communityQ}
                    join={join}
                    onOpenConversation={onOpenConversation}
                  />
                ) : (
                  <ConnectorsPanel appId={String(app.n)} me={me} statusQ={statusQ} toggle={toggle} />
                )}
              </motion.div>
            </AnimatePresence>
          </div>
        </>
      )}
    </motion.div>
  )
}

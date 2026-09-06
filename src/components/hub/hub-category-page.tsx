// ─────────────────────────────────────────────────────────────
// Pulse Hub — #/hub/c/<slug> category sub-page (R27-b).
// Full glass listing for one matrix category (or slug "mine" =
// My apps, real AppInstall data). Rows carry per-app accent icon
// tiles, live connect badges and spring entrances. Back pops the
// hash history via backHash(). Zero mocks — install truth comes
// from /api/hub/apps/*/install.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronRight, Compass, Layers, SearchX } from 'lucide-react'
import { backHash, navigateHash } from '@/lib/hash-router'
import type { AppUser } from '@/lib/types'
import { buzz } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring } from '@/lib/motion'
import {
  CATEGORY_META,
  MATRIX,
  appTagline,
  categoryBySlug,
  slugForCategory,
} from '@/lib/hub-catalog'
import {
  hydrateInstalledSet,
  useInstalledSet,
  SkeletonDots,
  LoadErrorCard,
} from '@/components/hub/hub-data'
import {
  AppIconTile,
  CategoryIconTile,
  ConnectedBadge,
  HubSubHeader,
  staggerChild,
  staggerParent,
} from '@/components/hub/hub-primitives'
import { Button } from '@/components/ui/button'

export const MINE_SLUG = 'mine'

export function HubCategoryPage({
  slug,
  me,
  direction = 'forward',
}: {
  slug: string
  me: AppUser
  /** slide direction for push vs pop (root→category = forward) */
  direction?: 'forward' | 'back'
}) {
  const qc = useQueryClient()
  const reduced = Boolean(useReducedMotion())
  const setQ = useInstalledSet(me.id)

  const category = useMemo(() => categoryBySlug(slug), [slug])
  const isMine = category === null && slug === MINE_SLUG

  // hydrate the real install set (Promise.all over the catalog, cached 60s)
  useEffect(() => {
    if (!category && !isMine) return
    void hydrateInstalledSet(qc, me.id).catch(() => {})
  }, [qc, me.id, category, isMine])

  const installed = useMemo(() => new Set(setQ.data ?? []), [setQ.data])

  const apps = useMemo(() => {
    if (isMine) return MATRIX.filter((a) => installed.has(String(a.n)))
    if (category) return MATRIX.filter((a) => a.category === category)
    return []
  }, [isMine, category, installed])

  // header copy
  const meta = category ? CATEGORY_META[category] : null
  const title = isMine ? 'My apps' : (meta?.label ?? 'Unknown category')
  const blurb = isMine
    ? 'Everything you have connected, live from your install history.'
    : (meta?.blurb ?? 'This corner of the matrix does not exist.')
  const accent = meta?.accent ?? ['#10b981', '#0d9488']
  const Icon = meta ? undefined : isMine ? Layers : undefined

  const hydrating = isMine && setQ.data === undefined && setQ.isFetching
  const failed = isMine && setQ.data === undefined && setQ.isError

  return (
    <motion.div
      initial={reduced ? { opacity: 0 } : direction === 'forward' ? { opacity: 0, x: 44 } : { opacity: 0, x: -44 }}
      animate={{ opacity: 1, x: 0, transition: spring.soft }}
      exit={
        reduced
          ? { opacity: 0, transition: { duration: 0.12 } }
          : direction === 'forward'
            ? { opacity: 0, x: -32, transition: { duration: 0.18, ease: 'easeIn' } }
            : { opacity: 0, x: 32, transition: { duration: 0.18, ease: 'easeIn' } }
      }
      className="absolute inset-0 z-[70] flex flex-col bg-background"
      role="region"
      aria-label={`${title} category`}
    >
      <HubSubHeader
        title={title}
        subtitle={meta ? `${apps.length} platform${apps.length === 1 ? '' : 's'} in the matrix` : isMine ? `${apps.length} connected` : undefined}
        onBack={() => backHash('/hub')}
        backLabel={`Back to Hub, from ${title}`}
      />

      {/* accent identity band — the category wash sits BEHIND the glass card
          (locked recipe: ambient colored wash refracting through glass) */}
      <div
        className="relative shrink-0 px-3 pb-2.5 pt-3"
        style={{ backgroundImage: `linear-gradient(120deg, ${accent[0]}26, ${accent[1]}14 55%, transparent)` }}
      >
        <div className="glass-deep glass-sheen flex items-center gap-3 rounded-3xl p-3.5">
          {category ? (
            <CategoryIconTile category={category} size={44} />
          ) : Icon ? (
            <div
              aria-hidden
              className="flex size-11 shrink-0 items-center justify-center rounded-2xl text-white"
              style={{
                backgroundImage: `linear-gradient(135deg, ${accent[0]}, ${accent[1]})`,
                boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.45), 0 8px 20px -8px rgba(0,0,0,0.5)',
              }}
            >
              <Icon className="size-5" />
            </div>
          ) : null}
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13.5px] font-bold tracking-tight">{blurb}</p>
            <p className="mt-0.5 truncate text-[10px] font-bold uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
              {meta ? `Category · ${slugForCategory(category!)}` : 'Live from your Pulse installs'}
            </p>
          </div>
        </div>
      </div>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-10 pt-1">
        {/* unknown slug */}
        {!category && !isMine ? (
          <motion.div
            initial={reduced ? false : { opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.28, ease: ease.out }}
            className="glass-deep glass-sheen mt-2 flex flex-col items-center gap-3 rounded-3xl px-6 py-10 text-center"
          >
            <div className="flex size-14 items-center justify-center rounded-2xl bg-emerald-500/10">
              <Compass className="size-7 text-emerald-500" aria-hidden />
            </div>
            <div>
              <p className="text-sm font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">Category not found</p>
              <p className="mx-auto mt-1 max-w-[260px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                That slug does not map to any corner of the matrix.
              </p>
            </div>
            <Button size="sm" variant="outline" className="h-9 rounded-full" onClick={() => backHash('/hub')}>
              Back to the Hub
            </Button>
          </motion.div>
        ) : hydrating ? (
          <div className="glass-deep glass-sheen mt-2 flex flex-col items-center gap-3 rounded-3xl px-6 py-12">
            <SkeletonDots label="Checking your connected apps" />
            <p className="text-[12px] font-medium text-zinc-500 dark:text-zinc-400">Checking your connections…</p>
          </div>
        ) : failed ? (
          <LoadErrorCard
            message="Could not verify your connected apps."
            onRetry={() => void hydrateInstalledSet(qc, me.id)}
          />
        ) : apps.length === 0 ? (
          <motion.div
            initial={reduced ? false : { opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.28, ease: ease.out }}
            className="glass-deep glass-sheen mt-2 flex flex-col items-center gap-3 rounded-3xl px-6 py-10 text-center"
          >
            <div className="flex size-14 items-center justify-center rounded-2xl bg-emerald-500/10">
              <SearchX className="size-7 text-emerald-500" aria-hidden />
            </div>
            <div>
              <p className="text-sm font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                {isMine ? 'No connections yet' : 'Nothing here yet'}
              </p>
              <p className="mx-auto mt-1 max-w-[260px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                {isMine
                  ? 'You haven\u2019t connected any apps yet — open one in the matrix and tap Connect.'
                  : 'Nothing lives in this category yet.'}
              </p>
            </div>
          </motion.div>
        ) : (
          <motion.ul
            variants={reduced ? undefined : staggerParent}
            initial={reduced ? false : 'hidden'}
            animate="show"
            aria-label={`${title} apps`}
            className="glass-deep glass-sheen flex flex-col overflow-hidden rounded-3xl"
          >
            {apps.map((app, i) => (
              <motion.li
                key={app.n}
                variants={reduced ? undefined : staggerChild}
                className={cn(
                  'glass-row-hover relative',
                  i > 0 && 'border-t border-zinc-900/[0.06] dark:border-white/[0.06]',
                )}
              >
                <motion.button
                  type="button"
                  onClick={() => {
                    buzz(10)
                    navigateHash(`/hub/app/${app.n}`)
                  }}
                  aria-label={`Open ${app.name} page`}
                  whileTap={reduced ? undefined : pressTap}
                  transition={pressSpring}
                  className="flex w-full items-center gap-3 px-3.5 py-3 text-left outline-none focus-visible:bg-zinc-900/[0.05] dark:focus-visible:bg-white/[0.06]"
                >
                  <AppIconTile app={app} size={44} />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-[13.5px] font-bold">{app.name}</span>
                      <span className="shrink-0 text-[10px] font-semibold tabular-nums text-zinc-400">
                        #{String(app.n).padStart(3, '0')}
                      </span>
                    </span>
                    <span className="mt-0.5 block truncate text-[11.5px] font-medium text-zinc-500">
                      {appTagline(app)}
                    </span>
                  </span>
                  {installed.has(String(app.n)) ? <ConnectedBadge appName={app.name} /> : null}
                  <ChevronRight className="size-4 shrink-0 text-zinc-400" aria-hidden />
                </motion.button>
              </motion.li>
            ))}
          </motion.ul>
        )}
      </div>
    </motion.div>
  )
}

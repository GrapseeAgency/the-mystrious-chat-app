// ─────────────────────────────────────────────────────────────
// Pulse — full-screen status story viewer (Instagram-style).
// Progress bars (5s per story, hold-to-pause), tap left/right to
// navigate, vertical drag-to-dismiss, auto-advance, view marking
// for others' stories, owner delete + live viewers list.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion, useMotionValue } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, Eye, LoaderCircle, Trash2, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { apiJson, gradientFor } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { UserAvatar } from '@/components/chat/user-avatar'

// ── Shared stories contract (single source of truth for the wave) ──

export interface StoryItem {
  id: string
  kind: 'image' | 'text'
  imagePath: string | null
  caption: string
  background: string
  createdAt: string
  expiresAt: string
  viewCount: number
  viewedByMe: boolean
}

export interface StoryGroup {
  user: { id: string; name: string; username: string | null; color: string }
  mine: boolean
  allSeen: boolean
  stories: StoryItem[]
}

export interface StoriesResponse {
  groups: StoryGroup[]
}

/** TanStack Query key contract for the stories feed — used everywhere. */
export const storiesQueryKey = (meId: string) => ['stories', meId] as const

export const STORY_VIEW_MS = 5000

/** "2h" / "5m" / "3d" — compact Instagram-style relative stamp. */
export function storyRelativeTime(iso: string): string {
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return ''
  const mins = Math.max(0, Math.floor((Date.now() - t) / 60000))
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.floor(hours / 24)}d`
}

// ── Cache patch helpers (shared with chats-tab) ─────────────

function mutateGroupsCache(
  queryClient: ReturnType<typeof useQueryClient>,
  meId: string,
  mutate: (groups: StoryGroup[]) => StoryGroup[],
) {
  queryClient.setQueryData<StoriesResponse>(storiesQueryKey(meId), (prev) =>
    prev ? { groups: mutate(prev.groups) } : prev,
  )
}

export function markStoryViewed(queryClient: ReturnType<typeof useQueryClient>, meId: string, storyId: string, viewCount: number) {
  mutateGroupsCache(queryClient, meId, (groups) =>
    groups.map((g) => ({
      ...g,
      allSeen: g.mine
        ? g.allSeen
        : g.stories.every((s) => s.viewedByMe || s.id === storyId),
      stories: g.stories.map((s) =>
        s.id === storyId ? { ...s, viewedByMe: true, viewCount } : s,
      ),
    })),
  )
}

export function removeStoryFromCache(queryClient: ReturnType<typeof useQueryClient>, meId: string, storyId: string) {
  mutateGroupsCache(queryClient, meId, (groups) =>
    groups
      .map((g) => ({ ...g, stories: g.stories.filter((s) => s.id !== storyId) }))
      .filter((g) => g.stories.length > 0),
  )
}

// ── Viewer ──────────────────────────────────────────────────

interface StoriesSheetProps {
  me: AppUser
  groups: StoryGroup[]
  /** where to start: user id + story id (stable against cache updates) */
  start: { userId: string; storyId: string }
  onClose: () => void
}

interface FlatStory {
  userId: string
  mine: boolean
  story: StoryItem
}

export function StoriesSheet({ me, groups, start, onClose }: StoriesSheetProps) {
  const queryClient = useQueryClient()

  // Display order flattened: groups arrive mine-first, stories oldest-first.
  const flat = useMemo<FlatStory[]>(
    () => groups.flatMap((g) => g.stories.map((story) => ({ userId: g.user.id, mine: g.mine, story }))),
    [groups],
  )

  const [position, setPosition] = useState<{ userId: string; storyId: string }>({
    userId: start.userId,
    storyId: start.storyId,
  })

  const current = useMemo(() => {
    const idx = flat.findIndex((f) => f.story.id === position.storyId)
    return idx >= 0 ? { flatItem: flat[idx], index: idx } : null
  }, [flat, position.storyId])

  const group = useMemo(() => groups.find((g) => g.user.id === current?.flatItem.userId) ?? null, [groups, current])

  // Auto-close when everything disappeared (deleted / expired underneath us).
  useEffect(() => {
    if (flat.length === 0) onClose()
  }, [flat.length, onClose])

  const goTo = useCallback((target: FlatStory) => {
    setPosition({ userId: target.userId, storyId: target.story.id })
  }, [])

  const next = useCallback(() => {
    if (!current) return
    if (current.index + 1 < flat.length) goTo(flat[current.index + 1])
    else onClose()
  }, [current, flat, goTo, onClose])

  const prev = useCallback(() => {
    if (!current) return
    if (current.index > 0) goTo(flat[current.index - 1])
  }, [current, flat, goTo])

  // ── progress loop (rAF; writes the active bar directly) ──
  const [paused, setPaused] = useState(false)
  const barRef = useRef<HTMLDivElement | null>(null)
  const elapsedRef = useRef(0)
  const startRef = useRef(0)
  const nextRef = useRef(next)
  nextRef.current = next

  // drag + owner-only UI state (declared before the loop pauses on them)
  const [dragging, setDragging] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [viewersOpen, setViewersOpen] = useState(false)

  useEffect(() => {
    elapsedRef.current = 0
  }, [position.storyId])

  useEffect(() => {
    if (paused || dragging || viewersOpen || confirmDelete || !current) return
    let raf = 0
    startRef.current = performance.now() - elapsedRef.current
    const tick = (now: number) => {
      const progress = Math.min(1, (now - startRef.current) / STORY_VIEW_MS)
      elapsedRef.current = now - startRef.current
      if (barRef.current) barRef.current.style.transform = `scaleX(${progress})`
      if (progress >= 1) {
        nextRef.current()
        return
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [paused, dragging, viewersOpen, confirmDelete, current])

  // ── mark others' stories as viewed (never my own) ────────
  const story = current?.flatItem.story ?? null
  const isMine = current?.flatItem.mine ?? false
  useEffect(() => {
    if (!story || isMine || story.viewedByMe) return
    let cancelled = false
    apiJson<{ viewCount: number }>(`/api/stories/${encodeURIComponent(story.id)}/view`, {
      method: 'POST',
      body: JSON.stringify({ requesterId: me.id }),
    })
      .then((data) => {
        if (!cancelled) markStoryViewed(queryClient, me.id, story.id, data.viewCount)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [story, isMine, me.id, queryClient])

  // ── vertical drag to dismiss ─────────────────────────────
  const y = useMotionValue(0)

  // ── tap vs hold on the stage ─────────────────────────────
  const downAtRef = useRef(0)
  const dragMovedRef = useRef(false)

  const handlePointerDown = useCallback(() => {
    downAtRef.current = performance.now()
    dragMovedRef.current = false
    setPaused(true)
  }, [])

  const handlePointerUp = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const held = performance.now() - downAtRef.current
      const tapped = !dragMovedRef.current && held < 240
      setPaused(false)
      if (!tapped) return
      const rect = e.currentTarget.getBoundingClientRect()
      const leftZone = e.clientX - rect.left < rect.width * 0.32
      if (leftZone) prev()
      else next()
    },
    [next, prev],
  )

  // ── keyboard support (desktop QA) ────────────────────────
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowRight') next()
      if (e.key === 'ArrowLeft') prev()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [next, prev, onClose])

  // ── owner: delete ────────────────────────────────────────
  const handleDelete = useCallback(async () => {
    if (!story || !isMine || deleting) return
    setDeleting(true)
    try {
      // compute the survivor story BEFORE mutating the cache
      const idx = flat.findIndex((f) => f.story.id === story.id)
      const survivors = flat.filter((f) => f.story.id !== story.id)
      const fallback = survivors[Math.min(Math.max(idx, 0), survivors.length - 1)] ?? null
      await apiJson(`/api/stories/${encodeURIComponent(story.id)}?requesterId=${encodeURIComponent(me.id)}`, {
        method: 'DELETE',
      })
      removeStoryFromCache(queryClient, me.id, story.id)
      toast.success('Status deleted')
      setConfirmDelete(false)
      if (fallback) goTo(fallback)
      else onClose()
    } catch {
      toast.error('Could not delete the status')
    } finally {
      setDeleting(false)
    }
  }, [story, isMine, deleting, flat, me.id, queryClient, goTo, onClose])

  // ── owner: viewers list ──────────────────────────────────
  const viewersQ = useQuery({
    queryKey: ['story-viewers', story?.id ?? 'none', me.id],
    enabled: viewersOpen && isMine && Boolean(story),
    staleTime: 5_000,
    queryFn: async (): Promise<{ viewers: Array<{ userId: string; name: string; username: string | null; color: string; viewedAt: string }> }> =>
      apiJson(`/api/stories/${encodeURIComponent(story?.id ?? '')}/view?requesterId=${encodeURIComponent(me.id)}`),
  })

  const si = group?.stories.findIndex((s) => s.id === position.storyId) ?? -1

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.94 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.94 }}
      transition={{ type: 'spring', stiffness: 380, damping: 34 }}
      style={{ y }}
      drag="y"
      dragConstraints={{ top: 0, bottom: 0 }}
      dragElastic={{ top: 0.02, bottom: 0.65 }}
      onDragStart={() => {
        dragMovedRef.current = true
        setDragging(true)
        setPaused(true)
      }}
      onDragEnd={(e, info) => {
        setDragging(false)
        setPaused(false)
        if (info.offset.y > 110 || info.velocity.y > 550) onClose()
      }}
      className="absolute inset-0 z-[80] overflow-hidden bg-zinc-950"
      role="dialog"
      aria-modal="true"
      aria-label={group ? `${group.user.name}'s status` : 'Status'}
    >
      {/* story stage */}
      <div
        className="absolute inset-0"
        onPointerDown={handlePointerDown}
        onPointerUp={handlePointerUp}
        onPointerCancel={() => setPaused(false)}
        onContextMenu={(e) => e.preventDefault()}
      >
        {story && story.kind === 'image' && story.imagePath ? (
          <img
            key={story.id}
            src={`/api/uploads/${encodeURIComponent(story.imagePath)}`}
            alt="Status photo"
            draggable={false}
            className="absolute inset-0 h-full w-full select-none object-contain"
          />
        ) : null}
        {story && story.kind === 'text' ? (
          <div
            key={story.id}
            className={cn('absolute inset-0 flex items-center justify-center bg-gradient-to-br px-8', gradientFor(story.background))}
          >
            <p className="max-h-[60dvh] w-full overflow-y-auto whitespace-pre-wrap text-center text-[26px] font-bold leading-snug text-white [text-shadow:0_1px_14px_rgba(0,0,0,0.3)]">
              {story.caption}
            </p>
          </div>
        ) : null}
        {story && story.kind === 'image' && story.caption ? (
          <p className="absolute inset-x-4 bottom-6 z-[5] mx-auto max-w-[90%] rounded-2xl bg-black/50 px-3.5 py-2 text-center text-[14px] font-medium leading-snug text-white backdrop-blur-sm">
            <span className="line-clamp-3 whitespace-pre-wrap">{story.caption}</span>
          </p>
        ) : null}
      </div>

      {/* top chrome: progress bars + header */}
      <div className="absolute inset-x-0 top-0 z-10 bg-gradient-to-b from-black/55 via-black/20 to-transparent pb-6 pt-[max(0.6rem,env(safe-area-inset-top))]">
        <div className="flex gap-1 px-3" aria-hidden>
          {group?.stories.map((s, i) => {
            const state = i < si ? 'done' : i === si ? 'run' : 'idle'
            return (
              <div key={s.id} className="h-[3px] flex-1 overflow-hidden rounded-full bg-white/25">
                {i === si ? (
                  <div ref={barRef} className="h-full w-full origin-left bg-white" style={{ transform: 'scaleX(0)' }} />
                ) : (
                  <div
                    className={cn('h-full w-full origin-left bg-white', state === 'done' ? 'scale-x-100' : 'scale-x-0')}
                  />
                )}
              </div>
            )
          })}
        </div>
        <div className="mt-2.5 flex items-center gap-2.5 px-3">
          {group ? <UserAvatar name={group.user.name} color={group.user.color} size={34} /> : null}
          <div className="min-w-0 flex-1">
            <p className="truncate text-[14px] font-semibold text-white">
              {group ? (group.mine ? 'My status' : group.user.name) : ''}
            </p>
            <p className="text-[11px] text-white/60">{story ? storyRelativeTime(story.createdAt) : ''}</p>
          </div>
          {isMine && story ? (
            <>
              <button
                type="button"
                onClick={() => setViewersOpen(true)}
                aria-label={`Viewers — ${story.viewCount}`}
                className="flex h-9 items-center gap-1.5 rounded-full bg-white/10 px-3 text-[12px] font-semibold text-white outline-none transition-colors hover:bg-white/20 active:scale-95"
              >
                <Eye className="size-4" aria-hidden />
                {story.viewCount}
              </button>
              <button
                type="button"
                onClick={() => setConfirmDelete(true)}
                aria-label="Delete status"
                className="flex size-9 items-center justify-center rounded-full text-white/90 outline-none transition-colors hover:bg-white/10 hover:text-rose-400 active:scale-90"
              >
                <Trash2 className="size-[18px]" aria-hidden />
              </button>
            </>
          ) : null}
          <button
            type="button"
            onClick={onClose}
            aria-label="Close status"
            className="flex size-9 items-center justify-center rounded-full text-white/90 outline-none transition-colors hover:bg-white/10 active:scale-90"
          >
            <X className="size-5" aria-hidden />
          </button>
        </div>
      </div>

      {/* hold hint (only while paused by hold, subtle) */}
      {paused && !dragging && !confirmDelete && !viewersOpen ? (
        <motion.p
          initial={{ opacity: 0 }}
          animate={{ opacity: 0.55 }}
          exit={{ opacity: 0 }}
          className="pointer-events-none absolute left-1/2 top-1/2 z-[5] -translate-x-1/2 -translate-y-1/2 rounded-full bg-black/45 px-3 py-1 text-[11px] font-semibold text-white"
          aria-hidden
        >
          paused
        </motion.p>
      ) : null}

      {/* delete confirm strip */}
      <AnimatePresence>
        {confirmDelete ? (
          <motion.div
            initial={{ opacity: 0, y: 24 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 24 }}
            transition={{ type: 'spring', stiffness: 420, damping: 32 }}
            className="absolute inset-x-4 bottom-6 z-20 flex items-center gap-2 rounded-3xl bg-zinc-900/95 p-2.5 shadow-2xl shadow-black/50 ring-1 ring-white/10 backdrop-blur-xl"
            role="alertdialog"
            aria-label="Delete this status?"
          >
            <p className="flex-1 pl-2 text-[13px] font-semibold text-white">Delete this status?</p>
            <button
              type="button"
              onClick={() => setConfirmDelete(false)}
              className="flex h-9 items-center rounded-full bg-white/10 px-4 text-[13px] font-semibold text-white outline-none transition-colors hover:bg-white/15 active:scale-95"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleDelete}
              disabled={deleting}
              className="flex h-9 items-center gap-1.5 rounded-full bg-rose-500 px-4 text-[13px] font-bold text-white outline-none transition-colors hover:bg-rose-400 active:scale-95 disabled:opacity-60"
            >
              {deleting ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : null}
              Delete
            </button>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* viewers sheet (mine only) */}
      <AnimatePresence>
        {viewersOpen && isMine ? (
          <>
            <motion.button
              type="button"
              aria-label="Close viewers"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setViewersOpen(false)}
              className="absolute inset-0 z-20 bg-black/40"
            />
            <motion.div
              initial={{ y: '100%' }}
              animate={{ y: 0 }}
              exit={{ y: '100%' }}
              transition={{ type: 'spring', stiffness: 380, damping: 36 }}
              className="absolute inset-x-0 bottom-0 z-30 flex max-h-[68%] flex-col rounded-t-3xl bg-zinc-900 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 ring-1 ring-white/10"
              role="dialog"
              aria-label="Viewers"
            >
              <div className="flex items-center gap-2 px-4 pb-1.5 pt-1">
                <h3 className="text-[15px] font-bold text-white">Viewers</h3>
                <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-emerald-500/20 px-1.5 text-[11px] font-bold text-emerald-300">
                  {story?.viewCount ?? 0}
                </span>
                <button
                  type="button"
                  onClick={() => setViewersOpen(false)}
                  aria-label="Close viewers"
                  className="ml-auto flex size-8 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-white/10 hover:text-white active:scale-90"
                >
                  <ChevronLeft className="size-5 rotate-90" aria-hidden />
                </button>
              </div>
              <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-1.5 pb-1">
                {viewersQ.isPending ? (
                  <div className="flex items-center justify-center gap-2 py-8" role="status" aria-label="Loading viewers">
                    <LoaderCircle className="size-4 animate-spin text-emerald-400" aria-hidden />
                    <span className="text-xs font-medium text-zinc-400">Loading viewers…</span>
                  </div>
                ) : (viewersQ.data?.viewers.length ?? 0) === 0 ? (
                  <div className="flex flex-col items-center gap-1.5 py-9 text-center">
                    <Eye className="size-6 text-zinc-600" aria-hidden />
                    <p className="text-[13px] font-semibold text-zinc-300">No viewers yet</p>
                    <p className="text-xs text-zinc-500">Contacts who watch your status appear here.</p>
                  </div>
                ) : (
                  viewersQ.data?.viewers.map((v) => (
                    <div key={v.userId} className="flex items-center gap-3 rounded-2xl px-2.5 py-2">
                      <UserAvatar name={v.name} color={v.color} size={34} />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[13.5px] font-semibold text-zinc-100">{v.name}</p>
                        <p className="truncate text-[11.5px] text-zinc-500">{v.username ? `@${v.username}` : 'Pulse user'}</p>
                      </div>
                      <span className="shrink-0 text-[11px] tabular-nums text-zinc-500">
                        {storyRelativeTime(v.viewedAt)}
                      </span>
                    </div>
                  ))
                )}
              </div>
            </motion.div>
          </>
        ) : null}
      </AnimatePresence>
    </motion.div>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — shared whiteboard sheet (Zoom/Miro-grade, Task R21-c).
// A full-height dark board over the chat: draw with mouse or
// touch, strokes persist as REAL WhiteboardStroke rows and sync
// across every member by 900ms delta-polling (?since= watermark
// + overlap window + id dedupe). "Clear board" wipes the rows
// and bumps a `resetAt` marker every client obeys.
//
// Wiring contract for chat-room (lead): mount once per room —
//   const wb = useWhiteboardSheet(conversationId, me.id)
//   ... {wb.node}
// and the /whiteboard slash entry opens it via the
// `pulse:open-whiteboard` CustomEvent (see slash-palette.tsx).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { Eraser, LoaderCircle, PenLine, Presentation, Undo2, WifiOff, X } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { WHITEBOARD_OPEN_EVENT } from '@/components/chat/slash-palette'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

// ── wire types (mirror of the REST contract) ─────────────────

export interface WhiteboardStroke {
  id: string
  userId: string
  color: string
  width: number
  /** normalized 0..1 pairs, e.g. [[0.1, 0.2], [0.35, 0.5]] */
  points: number[][]
  createdAt: string
}

interface WhiteboardGetResponse {
  strokes: WhiteboardStroke[]
  serverTime: number
  resetAt: number | null
}

interface WhiteboardPostResponse {
  ids: string[]
  created: number
  serverTime: number
}

interface WhiteboardUndoResponse {
  success: boolean
  removedId: string | null
  serverTime: number
}

interface WhiteboardDeleteResponse {
  success: boolean
  reset: boolean
  at: number
}

// ── constants ────────────────────────────────────────────────

const POLL_MS = 900
/** delta fetch overlap — swallows clock skew between poll cycles */
const SYNC_OVERLAP_MS = 1500
/** safety cap on committed strokes kept on-canvas (oldest dropped) */
const STROKES_CAP = 2000
const POINTS_MAX_PER_STROKE = 500

const STROKE_COLORS = [
  { id: 'emerald', value: '#10b981', label: 'Emerald' },
  { id: 'rose', value: '#f43f5e', label: 'Rose' },
  { id: 'amber', value: '#f59e0b', label: 'Amber' },
  { id: 'violet', value: '#8b5cf6', label: 'Violet' },
  { id: 'cyan', value: '#06b6d4', label: 'Cyan' },
  { id: 'chalk', value: '#f4f4f5', label: 'Chalk white' },
] as const

const STROKE_WIDTHS = [2, 5, 10] as const

/**
 * A committed stroke on the local canvas. Points are a FLAT
 * normalized array [x0, y0, x1, y1, …] — allocation-free to draw
 * and resize-safe (converted to pixels only inside the tracer).
 */
interface BoardStroke {
  id: string
  userId: string
  color: string
  width: number
  points: number[]
}

// ── canvas engine (module-level, allocation-free) ────────────

function clamp01(v: number): number {
  return Math.min(Math.max(v, 0), 1)
}

function flattenPoints(pairs: number[][]): number[] {
  const flat: number[] = []
  for (const pair of pairs) {
    if (Array.isArray(pair) && pair.length >= 2 && Number.isFinite(pair[0]) && Number.isFinite(pair[1])) {
      flat.push(clamp01(pair[0]), clamp01(pair[1]))
    }
  }
  return flat
}

function pairsFromFlat(flat: number[]): number[][] {
  const pairs: number[][] = []
  for (let i = 0; i + 1 < flat.length; i += 2) pairs.push([flat[i], flat[i + 1]])
  return pairs
}

function flatEquals(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    if (Math.abs(a[i] - b[i]) > 1e-9) return false
  }
  return true
}

/** Smooth quadratic polyline through segment midpoints (Zoom/Miro feel). */
function traceSmooth(
  ctx: CanvasRenderingContext2D,
  pts: number[],
  w: number,
  h: number,
): void {
  const n = pts.length / 2
  if (n === 0) return
  const X = (i: number) => pts[i * 2] * w
  const Y = (i: number) => pts[i * 2 + 1] * h
  if (n === 1) {
    ctx.moveTo(X(0), Y(0))
    ctx.lineTo(X(0) + 0.01, Y(0) + 0.01)
    return
  }
  ctx.moveTo(X(0), Y(0))
  if (n === 2) {
    ctx.lineTo(X(1), Y(1))
    return
  }
  for (let i = 1; i < n - 1; i++) {
    ctx.quadraticCurveTo(X(i), Y(i), (X(i) + X(i + 1)) / 2, (Y(i) + Y(i + 1)) / 2)
  }
  ctx.lineTo(X(n - 1), Y(n - 1))
}

/** One incremental live segment — cheap enough for every pointermove. */
function drawLiveSegment(
  ctx: CanvasRenderingContext2D,
  pts: number[],
  w: number,
  h: number,
  color: string,
  width: number,
): void {
  const n = pts.length / 2
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.strokeStyle = color
  ctx.lineWidth = width
  const X = (i: number) => pts[i * 2] * w
  const Y = (i: number) => pts[i * 2 + 1] * h
  if (n < 2) {
    ctx.beginPath()
    ctx.fillStyle = color
    ctx.arc(X(0), Y(0), width / 2, 0, Math.PI * 2)
    ctx.fill()
    return
  }
  ctx.beginPath()
  if (n === 2) {
    ctx.moveTo(X(0), Y(0))
    ctx.lineTo(X(1), Y(1))
  } else {
    const a = n - 3
    const b = n - 2
    const c = n - 1
    ctx.moveTo((X(a) + X(b)) / 2, (Y(a) + Y(b)) / 2)
    ctx.quadraticCurveTo(X(b), Y(b), (X(b) + X(c)) / 2, (Y(b) + Y(c)) / 2)
  }
  ctx.stroke()
}

// ── component ────────────────────────────────────────────────

export function WhiteboardSheet({
  open,
  onClose,
  conversationId,
  meId,
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  meId: string
}) {
  // canvas + engine refs
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const ctxRef = useRef<CanvasRenderingContext2D | null>(null)
  const sizeRef = useRef({ w: 0, h: 0, dpr: 1 })

  // board state lives in refs — the canvas is imperative
  const strokesRef = useRef<BoardStroke[]>([])
  const idsRef = useRef<Set<string>>(new Set())
  /** my strokes whose POST is still in flight (adopted by id later) */
  const pendingRef = useRef<BoardStroke[]>([])
  const activeRef = useRef<{ pointerId: number; color: string; width: number; pts: number[] } | null>(
    null,
  )
  /** last serverTime watermark for the ?since= delta poll */
  const sinceRef = useRef<number | null>(null)
  /** undefined = no baseline yet (first response never triggers a wipe) */
  const resetAtRef = useRef<number | null | undefined>(undefined)
  const localSeqRef = useRef(0)
  const clearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // UI state
  const [color, setColor] = useState<string>(STROKE_COLORS[0].value)
  const [width, setWidth] = useState<number>(STROKE_WIDTHS[1])
  const [synced, setSynced] = useState(false)
  const [offline, setOffline] = useState(false)
  const [strokeCount, setStrokeCount] = useState(0)
  const [undoBusy, setUndoBusy] = useState(false)
  const [clearBusy, setClearBusy] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const colorRef = useRef(color)
  const widthRef = useRef(width)

  // ── painting ────────────────────────────────────────────────

  const drawAll = useCallback(() => {
    const ctx = ctxRef.current
    if (!ctx) return
    const { w, h, dpr } = sizeRef.current
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    for (const stroke of strokesRef.current) {
      ctx.strokeStyle = stroke.color
      ctx.lineWidth = stroke.width
      ctx.beginPath()
      traceSmooth(ctx, stroke.points, w, h)
      ctx.stroke()
    }
    const live = activeRef.current
    if (live && live.pts.length > 0) {
      drawLiveSegment(ctx, live.pts, w, h, live.color, live.width)
    }
  }, [])

  const resizeCanvas = useCallback(() => {
    const wrap = wrapRef.current
    const canvas = canvasRef.current
    if (!wrap || !canvas) return
    const rect = wrap.getBoundingClientRect()
    const dpr = Math.min(window.devicePixelRatio || 1, 2.5)
    const w = Math.max(1, Math.round(rect.width))
    const h = Math.max(1, Math.round(rect.height))
    if (sizeRef.current.w === w && sizeRef.current.h === h && sizeRef.current.dpr === dpr) return
    sizeRef.current = { w, h, dpr }
    canvas.width = Math.round(w * dpr)
    canvas.height = Math.round(h * dpr)
    const ctx = canvas.getContext('2d')
    if (ctx) {
      ctxRef.current = ctx
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    drawAll()
  }, [drawAll])

  useEffect(() => {
    if (!open) return
    // one tick deferred so the sheet has laid out before measuring
    const kick = setTimeout(resizeCanvas, 0)
    const wrap = wrapRef.current
    if (!wrap || typeof ResizeObserver === 'undefined') {
      return () => clearTimeout(kick)
    }
    const ro = new ResizeObserver(resizeCanvas)
    ro.observe(wrap)
    return () => {
      clearTimeout(kick)
      ro.disconnect()
    }
  }, [open, resizeCanvas])

  // ── local board mutations ───────────────────────────────────

  const pushStroke = useCallback((stroke: BoardStroke) => {
    strokesRef.current.push(stroke)
    idsRef.current.add(stroke.id)
    // keep the canvas snappy: drop the oldest finished stroke beyond the cap
    if (strokesRef.current.length > STROKES_CAP) {
      const idx = strokesRef.current.findIndex((s) => !pendingRef.current.includes(s))
      if (idx >= 0) {
        const [dropped] = strokesRef.current.splice(idx, 1)
        idsRef.current.delete(dropped.id)
      }
    }
    setStrokeCount(strokesRef.current.length)
  }, [])

  const removeLocal = useCallback((id: string): boolean => {
    const list = strokesRef.current
    const idx = list.findIndex((s) => s.id === id)
    if (idx < 0) return false
    idsRef.current.delete(id)
    list.splice(idx, 1)
    setStrokeCount(list.length)
    return true
  }, [])

  const wipeBoard = useCallback(() => {
    strokesRef.current = []
    idsRef.current.clear()
    pendingRef.current = []
    setStrokeCount(0)
  }, [])

  // ── remote sync (delta poll + reset marker) ─────────────────

  const applyServerStroke = useCallback(
    (s: WhiteboardStroke) => {
      if (idsRef.current.has(s.id)) return // already drawn (own echo or re-fetch overlap)
      const flat = flattenPoints(s.points)
      if (flat.length < 2) return // corrupt row — skip honestly
      // adopt my own pending copy when the poll echoes it before the
      // POST response lands (both directions are covered)
      const pendingIdx = pendingRef.current.findIndex(
        (p) =>
          p.userId === s.userId &&
          p.color === s.color &&
          Math.abs(p.width - s.width) < 0.001 &&
          p.points.length === flat.length &&
          flatEquals(p.points, flat),
      )
      if (pendingIdx >= 0) {
        const mine = pendingRef.current[pendingIdx]
        pendingRef.current.splice(pendingIdx, 1)
        idsRef.current.delete(mine.id)
        mine.id = s.id
        idsRef.current.add(s.id)
        return
      }
      pushStroke({ id: s.id, userId: s.userId, color: s.color, width: s.width, points: flat })
    },
    [pushStroke],
  )

  const applyRemote = useCallback(
    (res: WhiteboardGetResponse) => {
      let changed = false
      const resetAt = typeof res.resetAt === 'number' ? res.resetAt : null
      if (resetAtRef.current !== undefined && resetAt !== resetAtRef.current) {
        // someone cleared the board — wipe and let the (post-clear)
        // strokes in this very response re-populate the canvas
        wipeBoard()
        changed = true
      }
      resetAtRef.current = resetAt
      const baseline = res.serverTime - SYNC_OVERLAP_MS
      sinceRef.current = sinceRef.current === null ? baseline : Math.max(sinceRef.current, baseline)
      for (const s of res.strokes ?? []) {
        const before = strokesRef.current.length
        applyServerStroke(s)
        if (strokesRef.current.length !== before) changed = true
      }
      if (changed) drawAll()
      setSynced(true)
      setOffline(false)
    },
    [applyServerStroke, drawAll, wipeBoard],
  )

  const whiteboardQuery = useQuery({
    queryKey: ['whiteboard', conversationId],
    enabled: open && conversationId.length > 0 && meId.length > 0,
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
    retry: 1,
    queryFn: async (): Promise<WhiteboardGetResponse> => {
      const params = new URLSearchParams({ requesterId: meId })
      if (sinceRef.current !== null) params.set('since', String(sinceRef.current))
      const res = await apiJson<WhiteboardGetResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/whiteboard?${params.toString()}`,
      )
      applyRemote(res)
      return res
    },
  })

  useEffect(() => {
    if (whiteboardQuery.isError) setOffline(true)
  }, [whiteboardQuery.isError])

  // conversation switch → full local reset (fresh snapshot on next poll)
  useEffect(() => {
    strokesRef.current = []
    idsRef.current.clear()
    pendingRef.current = []
    activeRef.current = null
    sinceRef.current = null
    resetAtRef.current = undefined
    setStrokeCount(0)
    setSynced(false)
    setConfirmClear(false)
    drawAll()
  }, [conversationId, drawAll])

  // ── drawing (pointer events, single active pointer) ─────────

  const normalizeEvent = (e: React.PointerEvent<HTMLCanvasElement>): [number, number] => {
    const canvas = canvasRef.current
    if (!canvas) return [0.5, 0.5]
    const rect = canvas.getBoundingClientRect()
    return [clamp01((e.clientX - rect.left) / rect.width), clamp01((e.clientY - rect.top) / rect.height)]
  }

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!e.isPrimary || activeRef.current) return // multi-touch: one stroke at a time
    e.preventDefault()
    const [x, y] = normalizeEvent(e)
    activeRef.current = { pointerId: e.pointerId, color: colorRef.current, width: widthRef.current, pts: [x, y] }
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // capture unsupported — moves still flow while over the canvas
    }
    const ctx = ctxRef.current
    if (ctx) {
      drawLiveSegment(ctx, activeRef.current.pts, sizeRef.current.w, sizeRef.current.h, colorRef.current, widthRef.current)
    }
    haptic(6)
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const active = activeRef.current
    if (!active || e.pointerId !== active.pointerId) return
    e.preventDefault()
    const [x, y] = normalizeEvent(e)
    const { w, h } = sizeRef.current
    const n = active.pts.length
    const dx = (x - active.pts[n - 2]) * w
    const dy = (y - active.pts[n - 1]) * h
    if (dx * dx + dy * dy < 1.44) return // ignore sub-pixel jitter (< ~1.2px)
    active.pts.push(x, y)
    const ctx = ctxRef.current
    if (ctx) drawLiveSegment(ctx, active.pts, w, h, active.color, active.width)
  }

  const syncStroke = useCallback(
    async (board: BoardStroke) => {
      try {
        const res = await apiJson<WhiteboardPostResponse>(
          `/api/conversations/${encodeURIComponent(conversationId)}/whiteboard`,
          {
            method: 'POST',
            body: JSON.stringify({
              requesterId: meId,
              strokes: [{ color: board.color, width: board.width, points: pairsFromFlat(board.points) }],
            }),
          },
        )
        const serverId = res.ids[0] ?? null
        const pendingIdx = pendingRef.current.findIndex((s) => s.id === board.id)
        if (pendingIdx >= 0) {
          const pending = pendingRef.current[pendingIdx]
          pendingRef.current.splice(pendingIdx, 1)
          idsRef.current.delete(pending.id)
          if (serverId && !idsRef.current.has(serverId)) {
            pending.id = serverId
            idsRef.current.add(serverId)
          }
        } else if (serverId) {
          // pending copy vanished (board was cleared mid-flight) — the
          // stroke still exists server-side, so mirror server truth
          idsRef.current.add(serverId)
          if (!strokesRef.current.some((s) => s.id === serverId)) {
            pushStroke({ ...board, id: serverId })
            drawAll()
          }
        }
        if (sinceRef.current !== null) {
          sinceRef.current = Math.max(sinceRef.current, res.serverTime - SYNC_OVERLAP_MS)
        }
      } catch {
        // never reached the server — remove the local ghost honestly
        removeLocal(board.id)
        drawAll()
        toast.error('Stroke did not sync — check your connection.')
      }
    },
    [conversationId, meId, drawAll, pushStroke, removeLocal],
  )

  const finalizeStroke = useCallback(
    (active: { color: string; width: number; pts: number[] }) => {
      let pts = active.pts
      // a tap becomes a dot: nudge a second point so the stroke survives
      // the server's 2-point minimum and renders as a round cap
      if (pts.length < 4) {
        const off = 1.2 / Math.max(sizeRef.current.w, 1)
        pts = [pts[0], pts[1], Math.min(pts[0] + off, 1), Math.min(pts[1] + off, 1)]
      }
      // respect the 500-point cap — downsample, always keep the last point
      const pointCount = pts.length / 2
      if (pointCount > POINTS_MAX_PER_STROKE) {
        const keepEvery = Math.ceil(pointCount / POINTS_MAX_PER_STROKE)
        const down: number[] = []
        for (let i = 0; i < pointCount; i += keepEvery) down.push(pts[i * 2], pts[i * 2 + 1])
        const last = pointCount - 1
        if (down[down.length - 2] !== pts[last * 2] || down[down.length - 1] !== pts[last * 2 + 1]) {
          down.push(pts[last * 2], pts[last * 2 + 1])
        }
        pts = down
      }
      const localId = `pending:${localSeqRef.current++}`
      const board: BoardStroke = { id: localId, userId: meId, color: active.color, width: active.width, points: pts }
      pushStroke(board)
      pendingRef.current.push(board)
      // crisp final pass: full redraw renders the smoothed polyline
      drawAll()
      void syncStroke(board)
    },
    [meId, pushStroke, drawAll, syncStroke],
  )

  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const active = activeRef.current
    if (!active || e.pointerId !== active.pointerId) return
    activeRef.current = null
    e.preventDefault()
    finalizeStroke(active)
  }

  const handlePointerCancel = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const active = activeRef.current
    if (!active || e.pointerId !== active.pointerId) return
    activeRef.current = null
    if (active.pts.length > 3) {
      finalizeStroke(active) // gesture mostly completed — keep the work
    } else {
      drawAll() // discard the dab
    }
  }

  // ── undo / clear ────────────────────────────────────────────

  const undoMine = useCallback(async () => {
    if (undoBusy) return
    if (pendingRef.current.length > 0) {
      toast('Hold on — still syncing your last stroke.')
      return
    }
    if (!strokesRef.current.some((s) => s.userId === meId)) {
      toast('Nothing of yours to undo yet.')
      return
    }
    setUndoBusy(true)
    haptic(10)
    try {
      const res = await apiJson<WhiteboardUndoResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/whiteboard`,
        { method: 'POST', body: JSON.stringify({ action: 'undo', requesterId: meId }) },
      )
      if (res.removedId) {
        removeLocal(res.removedId)
        drawAll()
      } else {
        toast('Nothing of yours to undo yet.')
      }
      if (sinceRef.current !== null) {
        sinceRef.current = Math.max(sinceRef.current, res.serverTime - SYNC_OVERLAP_MS)
      }
    } catch {
      toast.error('Undo failed — check your connection.')
    } finally {
      setUndoBusy(false)
    }
  }, [undoBusy, meId, conversationId, removeLocal, drawAll])

  const clearBoard = useCallback(async () => {
    // two-tap confirm — a wipe is destructive for the whole room
    if (!confirmClear) {
      setConfirmClear(true)
      haptic(12)
      if (clearTimerRef.current) clearTimeout(clearTimerRef.current)
      clearTimerRef.current = setTimeout(() => setConfirmClear(false), 2600)
      return
    }
    if (clearTimerRef.current) {
      clearTimeout(clearTimerRef.current)
      clearTimerRef.current = null
    }
    setConfirmClear(false)
    setClearBusy(true)
    haptic(18)
    try {
      const res = await apiJson<WhiteboardDeleteResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/whiteboard?requesterId=${encodeURIComponent(meId)}`,
        { method: 'DELETE' },
      )
      wipeBoard()
      // only post-clear strokes from now on — with a small overlap window
      sinceRef.current = Math.max(res.at - SYNC_OVERLAP_MS, 0)
      drawAll()
      toast.success('Board cleared for everyone.')
    } catch {
      toast.error('Could not clear the board — check your connection.')
    } finally {
      setClearBusy(false)
    }
  }, [confirmClear, meId, conversationId, wipeBoard, drawAll])

  useEffect(() => {
    return () => {
      if (clearTimerRef.current) clearTimeout(clearTimerRef.current)
    }
  }, [])

  const statusLine = offline
    ? 'Reconnecting…'
    : !synced
      ? 'Syncing…'
      : `${strokeCount} ${strokeCount === 1 ? 'stroke' : 'strokes'} · live`

  return (
    <Drawer
      open={open}
      handleOnly // the canvas owns pointer events; only the handle drags to dismiss
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto h-[97dvh] max-h-[97dvh] max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 px-0 pb-0 [&>div:first-child]:bg-white/20 dark:border-white/10 dark:bg-zinc-950">
        <DrawerTitle className="sr-only">Shared whiteboard</DrawerTitle>
        <DrawerDescription className="sr-only">
          Draw together in real time — every stroke syncs to everyone in this chat
        </DrawerDescription>

        <motion.div
          initial={{ opacity: 0, y: 28, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 420, damping: 34, mass: 0.9 }}
          style={{ willChange: 'transform' }}
          className="flex min-h-0 flex-1 flex-col"
        >
          {/* header */}
          <header className="flex shrink-0 items-center gap-2.5 border-b border-white/10 px-4 pb-3 pt-1">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-2xl bg-emerald-500/15 text-emerald-400">
              <Presentation className="size-4" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Whiteboard</p>
              <p className="flex items-center gap-1.5 text-[11px] font-medium leading-tight text-zinc-500">
                <span
                  aria-hidden
                  className={cn(
                    'size-1.5 rounded-full',
                    offline ? 'bg-rose-500' : synced ? 'animate-pulse bg-emerald-400' : 'bg-amber-400',
                  )}
                />
                {statusLine}
              </p>
            </div>
            <button
              type="button"
              aria-label="Close whiteboard"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          {/* canvas */}
          <div ref={wrapRef} className="relative min-h-0 flex-1 overflow-hidden">
            <div
              aria-hidden
              className="absolute inset-0"
              style={{
                backgroundImage: 'radial-gradient(rgba(255,255,255,0.09) 1px, transparent 1px)',
                backgroundSize: '22px 22px',
              }}
            />
            <canvas
              ref={canvasRef}
              role="img"
              aria-label="Shared whiteboard canvas"
              className="absolute inset-0 h-full w-full touch-none select-none"
              style={{ transform: 'translateZ(0)' }}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              onPointerCancel={handlePointerCancel}
            />
            {!synced ? (
              <div
                className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-3"
                role="status"
                aria-label="Loading the board"
              >
                <LoaderCircle className="size-7 animate-spin text-emerald-400" aria-hidden />
                <p className="text-[13px] font-medium text-zinc-500">Loading the board…</p>
              </div>
            ) : strokeCount === 0 ? (
              <div className="pointer-events-none absolute inset-x-0 bottom-14 flex flex-col items-center gap-2 text-center">
                <span className="flex size-11 items-center justify-center rounded-2xl bg-white/5 text-zinc-500">
                  <PenLine className="size-5" aria-hidden />
                </span>
                <p className="max-w-[240px] text-[12.5px] font-medium leading-relaxed text-zinc-500">
                  The board is empty — draw something, everyone in this chat sees it live.
                </p>
              </div>
            ) : null}
          </div>

          {/* toolbar */}
          <div
            className="shrink-0 border-t border-white/10 px-3 pt-3"
            style={{ paddingBottom: 'max(0.7rem, env(safe-area-inset-bottom))' }}
          >
            <div className="flex items-center justify-between gap-2">
              <div role="radiogroup" aria-label="Stroke color" className="flex items-center gap-1.5">
                {STROKE_COLORS.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    role="radio"
                    aria-checked={color === c.value}
                    aria-label={`${c.label} color`}
                    onClick={() => {
                      setColor(c.value)
                      colorRef.current = c.value
                      haptic(6)
                    }}
                    className={cn(
                      'flex size-9 items-center justify-center rounded-full outline-none transition-all duration-150 active:scale-90',
                      color === c.value
                        ? 'ring-2 ring-emerald-400 ring-offset-2 ring-offset-zinc-950'
                        : 'ring-1 ring-white/15 hover:ring-white/30',
                    )}
                  >
                    <span className="size-5 rounded-full" style={{ backgroundColor: c.value }} />
                  </button>
                ))}
              </div>
              <div aria-hidden className="h-7 w-px shrink-0 bg-white/10" />
              <div role="radiogroup" aria-label="Stroke width" className="flex items-center gap-1">
                {STROKE_WIDTHS.map((w) => (
                  <button
                    key={w}
                    type="button"
                    role="radio"
                    aria-checked={width === w}
                    aria-label={`${w} pixel brush`}
                    onClick={() => {
                      setWidth(w)
                      widthRef.current = w
                      haptic(6)
                    }}
                    className={cn(
                      'flex size-9 items-center justify-center rounded-full outline-none transition-all duration-150 active:scale-90',
                      width === w
                        ? 'bg-emerald-500/15 ring-2 ring-emerald-400'
                        : 'ring-1 ring-white/15 hover:ring-white/30',
                    )}
                  >
                    <span
                      className="rounded-full bg-zinc-100"
                      style={{ width: w + 4, height: w + 4 }}
                    />
                  </button>
                ))}
              </div>
            </div>

            <div className="mt-2.5 flex gap-2">
              <button
                type="button"
                aria-label="Undo my last stroke"
                onClick={() => void undoMine()}
                disabled={undoBusy}
                className="flex h-11 flex-1 items-center justify-center gap-1.5 rounded-2xl border border-white/10 bg-white/5 text-[13px] font-bold text-zinc-100 outline-none ring-emerald-400/60 transition-all duration-150 hover:bg-white/10 focus-visible:ring-2 active:scale-[0.98] disabled:opacity-50"
              >
                <Undo2 className="size-4" aria-hidden />
                Undo
              </button>
              <button
                type="button"
                aria-label={confirmClear ? 'Tap again to clear the whole board' : 'Clear board for everyone'}
                onClick={() => void clearBoard()}
                disabled={clearBusy}
                className={cn(
                  'flex h-11 flex-1 items-center justify-center gap-1.5 rounded-2xl text-[13px] font-bold outline-none ring-emerald-400/60 transition-all duration-150 focus-visible:ring-2 active:scale-[0.98] disabled:opacity-50',
                  confirmClear
                    ? 'bg-rose-500 text-white shadow-lg shadow-rose-600/25'
                    : 'border border-white/10 bg-white/5 text-rose-400 hover:bg-rose-500/10',
                )}
              >
                <Eraser className="size-4" aria-hidden />
                {confirmClear ? 'Tap again' : 'Clear board'}
              </button>
            </div>
          </div>
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

/**
 * Drop-in wiring for chat-room (lead, 2 lines):
 *   const whiteboard = useWhiteboardSheet(conversationId, me.id)
 *   ... {whiteboard.node}
 * Listens for the `pulse:open-whiteboard` CustomEvent dispatched by
 * the /whiteboard slash-palette entry.
 */
export function useWhiteboardSheet(conversationId: string, meId: string) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(WHITEBOARD_OPEN_EVENT, handler)
    return () => window.removeEventListener(WHITEBOARD_OPEN_EVENT, handler)
  }, [])

  const node = (
    <WhiteboardSheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      meId={meId}
    />
  )

  return { open, setOpen, node }
}

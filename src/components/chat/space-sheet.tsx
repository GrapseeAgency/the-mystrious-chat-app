// ─────────────────────────────────────────────────────────────
// Pulse — Spatial presence map (R24-c "Beyond Chat" wave 3).
// Gather.town-style presence over the pulse-socket service:
// a shared 2-D office you move through, where position IS the
// social signal ("who is near me right now").
//
// REAL realtime, zero mocks:
//  · positions live in the socket service's in-memory spaceRooms
//    (`space:{conversationId}` rooms) — the genuine product, the
//    same trust model as the existing voice/stage rooms.
//  · the server clamps moves to 0..1 and throttles to one accepted
//    move per 80 ms; this client also self-throttles while dragging.
//  · presence is deliberately EPHEMERAL (Gather parity): players
//    idle >5 min are pruned server-side, disconnects clean up
//    instantly. Nothing about presence is stored.
//  · if you are alone the sheet says so — that is real presence,
//    not a placeholder.
//
// The sheet joins on open and emits space:leave on close/unmount.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { io, type Socket } from 'socket.io-client'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Map as MapIcon, UsersRound, X, Zap } from 'lucide-react'
import { toast } from 'sonner'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { gradientFor } from '@/lib/pulse-utils'
import { spring } from '@/lib/motion'
import { Button } from '@/components/ui/button'

export const SPACE_OPEN_EVENT = 'pulse:open-space'

const RELAY_CONNECT_TIMEOUT_MS = 8_000
const CLIENT_MOVE_THROTTLE_MS = 90
/** Euclidean distance (normalized map units) considered "nearby". */
const NEARBY_RADIUS = 0.18

export interface SpaceMe {
  id: string
  name: string
  username: string | null
  color: string
}

interface SpacePlayerDot {
  id: string
  name: string
  color: string
  x: number
  y: number
}

/** Mirrors the server's `space:state` payload. */
interface SpaceState {
  conversationId: string
  players: SpacePlayerDot[]
}

const INITIALS_FALLBACK = '?'

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return INITIALS_FALLBACK
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
}

// ── static floor plan chrome (decorative geometry, not data) ──
// Drawn once per canvas resize: outer walls, two meeting rooms,
// a lounge, desks — Gather-style top-down office in zinc strokes.
function drawFloorPlan(ctx: CanvasRenderingContext2D, w: number, h: number) {
  ctx.clearRect(0, 0, w, h)

  // floor
  const floor = ctx.createLinearGradient(0, 0, 0, h)
  floor.addColorStop(0, 'rgba(24,24,27,0.55)')
  floor.addColorStop(1, 'rgba(39,39,42,0.55)')
  ctx.fillStyle = floor
  ctx.fillRect(0, 0, w, h)

  const wall = 'rgba(161,161,170,0.5)'
  const accent = 'rgba(16,185,129,0.35)'
  ctx.lineWidth = Math.max(2, w * 0.006)
  ctx.lineCap = 'round'

  // outer walls
  ctx.strokeStyle = wall
  ctx.strokeRect(w * 0.04, h * 0.05, w * 0.92, h * 0.9)

  // meeting room (top-left) + door gap
  ctx.beginPath()
  ctx.moveTo(w * 0.04, h * 0.38)
  ctx.lineTo(w * 0.4, h * 0.38)
  ctx.lineTo(w * 0.4, h * 0.05)
  ctx.stroke()
  ctx.strokeStyle = accent
  ctx.beginPath()
  ctx.moveTo(w * 0.18, h * 0.38)
  ctx.lineTo(w * 0.3, h * 0.38)
  ctx.stroke()

  // focus booth (top-right)
  ctx.strokeStyle = wall
  ctx.beginPath()
  ctx.moveTo(w * 0.62, h * 0.05)
  ctx.lineTo(w * 0.62, h * 0.32)
  ctx.lineTo(w * 0.96, h * 0.32)
  ctx.stroke()
  ctx.strokeStyle = accent
  ctx.beginPath()
  ctx.moveTo(w * 0.62, h * 0.12)
  ctx.lineTo(w * 0.62, h * 0.24)
  ctx.stroke()

  // lounge divider (bottom)
  ctx.strokeStyle = wall
  ctx.beginPath()
  ctx.moveTo(w * 0.04, h * 0.7)
  ctx.lineTo(w * 0.34, h * 0.7)
  ctx.lineTo(w * 0.34, h * 0.95)
  ctx.stroke()

  // desks (focus booth)
  ctx.strokeStyle = 'rgba(113,113,122,0.45)'
  ;[
    [0.68, 0.12, 0.86, 0.12],
    [0.68, 0.2, 0.86, 0.2],
  ].forEach(([x1, y1, x2, y2]) => {
    ctx.beginPath()
    ctx.moveTo(w * x1, h * y1)
    ctx.lineTo(w * x2, h * y2)
    ctx.stroke()
  })

  // meeting table (top-left room)
  ctx.beginPath()
  ctx.ellipse(w * 0.22, h * 0.2, w * 0.1, h * 0.07, 0, 0, Math.PI * 2)
  ctx.stroke()

  // rug (lounge)
  ctx.strokeStyle = accent
  ctx.beginPath()
  ctx.roundRect(w * 0.5, h * 0.78, w * 0.34, h * 0.12, w * 0.02)
  ctx.stroke()

  // labels
  ctx.fillStyle = 'rgba(161,161,170,0.55)'
  ctx.font = `${Math.max(10, Math.round(w * 0.028))}px ui-sans-serif, system-ui`
  ctx.fillText('MEET', w * 0.07, h * 0.11)
  ctx.fillText('FOCUS', w * 0.68, h * 0.09)
  ctx.fillText('LOUNGE', w * 0.52, h * 0.75)
}

// ── one smoothed avatar ──
function SpaceAvatar({
  player,
  isMe,
  nearby,
  reduced,
}: {
  player: SpacePlayerDot
  isMe: boolean
  nearby: boolean
  reduced: boolean | null
}) {
  return (
    <motion.div
      className="absolute z-10"
      initial={false}
      animate={{ left: `${player.x * 100}%`, top: `${player.y * 100}%` }}
      transition={reduced ? { duration: 0 } : { type: 'spring', stiffness: 210, damping: 26, mass: 0.9 }}
      style={{ transform: 'translate(-50%, -50%)' }}
    >
      <div
        className={cn(
          'flex items-center justify-center rounded-full shadow-lg shadow-black/40 ring-2',
          isMe ? 'size-9 ring-emerald-400' : 'size-8 ring-white/20',
          nearby && !isMe && 'ring-emerald-400/80',
        )}
        style={{ background: gradientFor(player.color) }}
        aria-hidden
      >
        <span className="text-[10px] font-bold text-white select-none">{initialsOf(player.name)}</span>
      </div>
      <div
        className={cn(
          'mt-1 max-w-[84px] truncate rounded-full bg-zinc-950/85 px-1.5 py-0.5 text-center text-[9px] font-medium text-zinc-200',
          isMe && 'text-emerald-300',
        )}
      >
        {isMe ? 'You' : player.name}
      </div>
      {isMe ? (
        <motion.span
          aria-hidden
          className="absolute inset-0 -m-2 rounded-full border border-emerald-400/50"
          animate={reduced ? undefined : { scale: [1, 1.25, 1], opacity: [0.7, 0.2, 0.7] }}
          transition={{ duration: 2.2, repeat: Infinity, ease: 'easeInOut' }}
        />
      ) : null}
    </motion.div>
  )
}

// ── the sheet ──
export function SpaceSheetUI({
  conversationId,
  me,
  onClose,
}: {
  conversationId: string
  me: SpaceMe
  onClose: () => void
}) {
  const reduced = useReducedMotion()
  const [connected, setConnected] = useState(false)
  const [players, setPlayers] = useState<SpacePlayerDot[]>([])
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const sockRef = useRef<Socket | null>(null)
  const lastMoveSentRef = useRef(0)
  const mapRef = useRef<HTMLDivElement | null>(null)

  // optimistic local target for MY dot (server state reconciles over it)
  const [myTarget, setMyTarget] = useState<{ x: number; y: number } | null>(null)

  const sendMove = useCallback(
    (x: number, y: number) => {
      const sock = sockRef.current
      if (!sock) return
      const now = Date.now()
      if (now - lastMoveSentRef.current < CLIENT_MOVE_THROTTLE_MS) return
      lastMoveSentRef.current = now
      setMyTarget({ x, y })
      sock.emit('space:move', { conversationId, x, y })
    },
    [conversationId],
  )

  // socket lifecycle: join on open, leave+disconnect on unmount
  useEffect(() => {
    const sock = io('/?XTransformPort=3003', {
      path: '/',
      transports: ['websocket', 'polling'],
      timeout: RELAY_CONNECT_TIMEOUT_MS,
      reconnectionAttempts: 6,
    })
    sockRef.current = sock

    const onConnect = () => {
      setConnected(true)
      sock.emit('space:join', {
        conversationId,
        user: { id: me.id, name: me.name, username: me.username, color: me.color },
      })
    }
    const onState = (raw: unknown) => {
      const data = (raw ?? {}) as SpaceState
      if (data.conversationId !== conversationId || !Array.isArray(data.players)) return
      setPlayers(data.players)
    }
    const onDisconnect = () => setConnected(false)

    sock.on('connect', onConnect)
    sock.on('space:state', onState)
    sock.on('disconnect', onDisconnect)

    return () => {
      sock.emit('space:leave', { conversationId })
      sock.disconnect()
      sockRef.current = null
    }
  }, [conversationId, me.id, me.name, me.username, me.color])

  // floor-plan canvas painting (DPR-aware, on resize)
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const paint = () => {
      const rect = canvas.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.round(rect.width * dpr)
      canvas.height = Math.round(rect.height * dpr)
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      drawFloorPlan(ctx, rect.width, rect.height)
    }
    paint()
    const ro = new ResizeObserver(paint)
    ro.observe(canvas)
    return () => ro.disconnect()
  }, [])

  // tap / drag to move (normalized coordinates)
  const pointToNormalized = (clientX: number, clientY: number) => {
    const rect = mapRef.current?.getBoundingClientRect()
    if (!rect || rect.width === 0 || rect.height === 0) return null
    return {
      x: Math.max(0, Math.min(1, (clientX - rect.left) / rect.width)),
      y: Math.max(0, Math.min(1, (clientY - rect.top) / rect.height)),
    }
  }
  const draggingRef = useRef(false)
  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const pos = pointToNormalized(e.clientX, e.clientY)
    if (!pos) return
    draggingRef.current = true
    haptic(12)
    sendMove(pos.x, pos.y)
  }
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return
    const pos = pointToNormalized(e.clientX, e.clientY)
    if (pos) sendMove(pos.x, pos.y)
  }
  const endDrag = () => {
    draggingRef.current = false
  }

  // resolve my dot (server-authoritative, optimistic target wins for smoothness)
  const meDot: SpacePlayerDot = useMemo(() => {
    const fromServer = players.find((p) => p.id === me.id)
    if (myTarget) {
      return { id: me.id, name: me.name, color: me.color, x: myTarget.x, y: myTarget.y }
    }
    return fromServer ?? { id: me.id, name: me.name, color: me.color, x: 0.5, y: 0.5 }
  }, [players, me.id, me.name, me.color, myTarget])

  const others = useMemo(
    () => players.filter((p) => p.id !== me.id),
    [players, me.id],
  )

  const nearby = useMemo(
    () =>
      others.filter(
        (p) => Math.hypot(p.x - meDot.x, p.y - meDot.y) <= NEARBY_RADIUS,
      ),
    [others, meDot.x, meDot.y],
  )

  return (
    <motion.div
      className="fixed inset-0 z-[70] flex items-end justify-center"
      role="dialog"
      aria-label="Spatial presence map"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
    >
      {/* backdrop */}
      <button
        aria-label="Close spatial map"
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={onClose}
      />

      <motion.div
        className="relative w-full sm:max-w-[420px] rounded-t-3xl border border-zinc-800 bg-zinc-950/95 shadow-2xl shadow-black/60"
        initial={reduced ? { y: '100%' } : { y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={spring.snappy}
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
      >
        {/* header */}
        <div className="flex items-center gap-2 border-b border-zinc-800/80 px-4 pb-3 pt-4">
          <div className="flex size-9 items-center justify-center rounded-2xl bg-emerald-500/15">
            <MapIcon className="size-5 text-emerald-400" aria-hidden />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="text-sm font-semibold text-zinc-100">Space</h2>
            <p className="truncate text-[11px] text-zinc-500">
              {connected ? 'Live — move around, get near people' : 'Connecting to the room…'}
            </p>
          </div>
          <span
            className={cn(
              'flex items-center gap-1 rounded-full px-2 py-1 text-[10px] font-semibold',
              connected ? 'bg-emerald-500/15 text-emerald-300' : 'bg-zinc-800 text-zinc-400',
            )}
          >
            <UsersRound className="size-3" aria-hidden />
            {players.length} in room
          </span>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Close spatial map"
            onClick={onClose}
            className="size-9 shrink-0 rounded-xl text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
          >
            <X className="size-4" aria-hidden />
          </Button>
        </div>

        {/* map */}
        <div
          ref={mapRef}
          className="relative mx-4 my-4 aspect-[4/3.4] touch-none overflow-hidden rounded-2xl border border-zinc-800"
          style={{ cursor: 'crosshair' }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerLeave={endDrag}
          aria-label="Office map — tap to move"
        >
          <canvas ref={canvasRef} className="absolute inset-0 size-full" aria-hidden />

          {/* others */}
          {others.map((p) => (
            <SpaceAvatar
              key={p.id}
              player={p}
              isMe={false}
              nearby={nearby.some((n) => n.id === p.id)}
              reduced={reduced}
            />
          ))}
          {/* me */}
          <SpaceAvatar player={meDot} isMe nearby={false} reduced={reduced} />
        </div>

        {/* nearby rail */}
        <div className="border-t border-zinc-800/80 px-4 py-3">
          {others.length === 0 ? (
            <p className="py-1 text-center text-xs text-zinc-500">
              No one else here right now — invite people to the room.
            </p>
          ) : nearby.length === 0 ? (
            <p className="py-1 text-center text-xs text-zinc-500">
              {others.length} {others.length === 1 ? 'person' : 'people'} in the space — move closer to gather.
            </p>
          ) : (
            <div className="flex items-center gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              <span className="flex shrink-0 items-center gap-1 rounded-full bg-emerald-500/15 px-2.5 py-1 text-[10px] font-bold text-emerald-300">
                <Zap className="size-3" aria-hidden /> NEARBY
              </span>
              {nearby.map((p) => (
                <motion.span
                  key={p.id}
                  layout
                  transition={spring.snappy}
                  className="flex shrink-0 items-center gap-1.5 rounded-full bg-zinc-800/90 py-1 pl-1 pr-2.5 text-[11px] font-medium text-zinc-200"
                >
                  <span
                    className="flex size-5 items-center justify-center rounded-full text-[8px] font-bold text-white"
                    style={{ background: gradientFor(p.color) }}
                    aria-hidden
                  >
                    {initialsOf(p.name)}
                  </span>
                  {p.name}
                </motion.span>
              ))}
            </div>
          )}
        </div>
      </motion.div>
    </motion.div>
  )
}

// ── the contract hook (mounted by chat-room; opens via CustomEvent) ──

export function useSpaceSheet(conversationId: string, me: SpaceMe) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(SPACE_OPEN_EVENT, handler)
    return () => window.removeEventListener(SPACE_OPEN_EVENT, handler)
  }, [])

  const node = (
    <AnimatePresence>
      {open ? (
        <SpaceSheetUI key="space-sheet" conversationId={conversationId} me={me} onClose={() => setOpen(false)} />
      ) : null}
    </AnimatePresence>
  )

  return { open, setOpen, node }
}

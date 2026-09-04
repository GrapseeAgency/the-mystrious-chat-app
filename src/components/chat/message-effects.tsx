// ─────────────────────────────────────────────────────────────
// Pulse — full-screen message effects (iMessage-grade).
// One shared <canvas> instance; a hand-rolled rAF particle engine.
// Effects: confetti · lasers · echo · sparkles.
// Transform/paint only — zero layout, zero DOM churn. Honors
// reducedMotion upstream (chat-room never triggers when enabled).
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef } from 'react'

export type MessageEffectName = 'confetti' | 'lasers' | 'echo' | 'sparkles'

export const MESSAGE_EFFECT_NAMES: readonly MessageEffectName[] = [
  'confetti',
  'lasers',
  'echo',
  'sparkles',
]

export function isMessageEffect(value: unknown): value is MessageEffectName {
  return typeof value === 'string' && (MESSAGE_EFFECT_NAMES as readonly string[]).includes(value)
}

/** Normalized (0..1) bubble origin inside the chat screen. */
export interface EffectOrigin {
  x: number
  y: number
}

export interface ActiveEffect {
  effect: MessageEffectName
  origin: EffectOrigin
  /** bumps on every trigger so identical back-to-back effects still replay */
  nonce: number
}

const EFFECT_DURATION_MS: Record<MessageEffectName, number> = {
  confetti: 2600,
  lasers: 1900,
  echo: 1700,
  sparkles: 2200,
}

/** Tasteful Pulse palette — emerald/teal/amber/rose/violet, no blues. */
const CONFETTI_COLORS = [
  '#10b981',
  '#14b8a6',
  '#f59e0b',
  '#f43f5e',
  '#8b5cf6',
  '#84cc16',
  '#ec4899',
  '#facc15',
]

interface ConfettiPiece {
  x: number
  y: number
  vx: number
  vy: number
  w: number
  h: number
  rot: number
  vr: number
  color: string
  wobble: number
}

interface LaserBeam {
  baseAngle: number
  sweep: number
  hue: number
  speed: number
  width: number
}

interface EchoRing {
  delayMs: number
}

interface Sparkle {
  x: number
  y: number
  size: number
  phase: number
  twinkle: number
  drift: number
  color: string
}

interface ParticleWorld {
  confetti: ConfettiPiece[]
  lasers: LaserBeam[]
  rings: EchoRing[]
  sparkles: Sparkle[]
}

function rand(min: number, max: number): number {
  return min + Math.random() * (max - min)
}

function buildWorld(effect: MessageEffectName, w: number, h: number, origin: EffectOrigin): ParticleWorld {
  const ox = origin.x * w
  const oy = origin.y * h
  if (effect === 'confetti') {
    const pieces: ConfettiPiece[] = Array.from({ length: 120 }, () => ({
      x: rand(-20, w + 20),
      y: rand(-h * 0.35, -20),
      vx: rand(-0.55, 0.55),
      vy: rand(1.6, 3.6),
      w: rand(5, 9),
      h: rand(8, 14),
      rot: rand(0, Math.PI * 2),
      vr: rand(-0.16, 0.16),
      color: CONFETTI_COLORS[Math.floor(Math.random() * CONFETTI_COLORS.length)],
      wobble: rand(0, Math.PI * 2),
    }))
    return { confetti: pieces, lasers: [], rings: [], sparkles: [] }
  }
  if (effect === 'lasers') {
    const beams: LaserBeam[] = Array.from({ length: 8 }, (_, i) => ({
      baseAngle: (i / 8) * Math.PI * 2,
      sweep: rand(0.5, 0.9),
      hue: (i * 42 + 90) % 360, // greens → ambers → pinks → violets, no blues
      speed: rand(0.9, 1.5),
      width: rand(1.4, 3.2),
    }))
    return { confetti: [], lasers: beams, rings: [], sparkles: [] }
  }
  if (effect === 'echo') {
    const rings: EchoRing[] = [{ delayMs: 0 }, { delayMs: 240 }, { delayMs: 480 }]
    return { confetti: [], lasers: [], rings, sparkles: [] }
  }
  // sparkles — 40 twinkling stars clustered around the bubble origin
  const spread = Math.min(w, h) * 0.34
  const sparkleColors = ['#ffffff', '#fde68a', '#a7f3d0', '#fbcfe8', '#ddd6fe']
  const sparkles: Sparkle[] = Array.from({ length: 40 }, () => {
    const angle = rand(0, Math.PI * 2)
    const radius = Math.sqrt(Math.random()) * spread
    return {
      x: ox + Math.cos(angle) * radius,
      y: oy + Math.sin(angle) * radius * 0.8,
      size: rand(3, 8),
      phase: rand(0, Math.PI * 2),
      twinkle: rand(0.004, 0.011),
      drift: rand(-0.12, 0.12),
      color: sparkleColors[Math.floor(Math.random() * sparkleColors.length)],
    }
  })
  return { confetti: [], lasers: [], rings: [], sparkles }
}

/** Draw a 4-point star path (diamond sparkle). */
function traceStar(ctx: CanvasRenderingContext2D, x: number, y: number, size: number): void {
  ctx.beginPath()
  ctx.moveTo(x, y - size)
  ctx.quadraticCurveTo(x, y, x + size, y)
  ctx.quadraticCurveTo(x, y, x, y + size)
  ctx.quadraticCurveTo(x, y, x - size, y)
  ctx.quadraticCurveTo(x, y, x, y - size)
  ctx.closePath()
}

export function MessageEffectsLayer({
  active,
  onDone,
}: {
  /** currently playing effect (null = idle); queue lives upstream in chat-room */
  active: ActiveEffect | null
  onDone: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const onDoneRef = useRef(onDone)
  useEffect(() => {
    onDoneRef.current = onDone
  })

  // keep the backing store matched to the host element (dpr-aware)
  useEffect(() => {
    const canvas = canvasRef.current
    const parent = canvas?.parentElement
    if (!canvas || !parent) return
    const fit = () => {
      const rect = parent.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.max(1, Math.round(rect.width * dpr))
      canvas.height = Math.max(1, Math.round(rect.height * dpr))
      canvas.style.width = `${rect.width}px`
      canvas.style.height = `${rect.height}px`
    }
    fit()
    window.addEventListener('resize', fit)
    return () => window.removeEventListener('resize', fit)
  }, [])

  // the engine — one rAF loop per effect run, auto-stops, cleans up fully
  useEffect(() => {
    if (active === null) return
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const w = canvas.width / dpr
    const h = canvas.height / dpr
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)

    const world = buildWorld(active.effect, w, h, active.origin)
    const duration = EFFECT_DURATION_MS[active.effect]
    const start = performance.now()
    let raf = 0
    let running = true

    const paintConfetti = (t: number) => {
      for (const p of world.confetti) {
        p.x += p.vx + Math.sin(p.wobble + t * 0.004) * 0.5
        p.y += p.vy
        p.vy = Math.min(p.vy + 0.05, 5.2) // gravity
        p.rot += p.vr
        if (p.y > h + 24 || p.x < -30 || p.x > w + 30) continue
        ctx.save()
        ctx.translate(p.x, p.y)
        ctx.rotate(p.rot)
        ctx.globalAlpha = 1
        ctx.fillStyle = p.color
        ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h)
        ctx.restore()
      }
    }

    const paintLasers = (t: number) => {
      const cx = w / 2
      const cy = h / 2
      const diag = Math.hypot(w, h)
      ctx.save()
      ctx.globalCompositeOperation = 'lighter'
      for (const beam of world.lasers) {
        const angle = beam.baseAngle + Math.sin(t * 0.001 * beam.speed + beam.baseAngle * 3) * beam.sweep
        const hue = (beam.hue + t * 0.06) % 360
        const fade = Math.min(1, t / 320) * Math.min(1, Math.max(0, 1 - (t - (duration - 450)) / 450))
        if (fade <= 0) continue
        const grad = ctx.createLinearGradient(cx, cy, cx + Math.cos(angle) * diag, cy + Math.sin(angle) * diag)
        grad.addColorStop(0, `hsla(${hue}, 90%, 62%, ${0.5 * fade})`)
        grad.addColorStop(0.35, `hsla(${hue}, 95%, 70%, ${0.28 * fade})`)
        grad.addColorStop(1, 'hsla(0, 0%, 100%, 0)')
        ctx.strokeStyle = grad
        ctx.lineWidth = beam.width
        ctx.lineCap = 'round'
        ctx.shadowBlur = 14
        ctx.shadowColor = `hsla(${hue}, 95%, 60%, ${0.65 * fade})`
        ctx.beginPath()
        ctx.moveTo(cx, cy)
        ctx.lineTo(cx + Math.cos(angle) * diag, cy + Math.sin(angle) * diag)
        ctx.stroke()
      }
      ctx.restore()
    }

    const paintEcho = (t: number) => {
      const maxR = Math.min(w, h) * 0.48
      ctx.save()
      for (const ring of world.rings) {
        const local = t - ring.delayMs
        if (local <= 0) continue
        const progress = local / 1150
        if (progress >= 1) continue
        const r = maxR * (1 - Math.pow(1 - progress, 2.4)) // ease-out expansion
        const alpha = (1 - progress) * 0.9
        ctx.globalAlpha = alpha
        ctx.lineWidth = 8 * (1 - progress) + 1.5
        ctx.strokeStyle = 'rgba(16, 185, 129, 0.85)'
        ctx.beginPath()
        ctx.arc(active.origin.x * w, active.origin.y * h, r, 0, Math.PI * 2)
        ctx.stroke()
        ctx.globalAlpha = alpha * 0.55
        ctx.lineWidth = 2
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)'
        ctx.beginPath()
        ctx.arc(active.origin.x * w, active.origin.y * h, r * 0.94, 0, Math.PI * 2)
        ctx.stroke()
      }
      ctx.restore()
    }

    const paintSparkles = (t: number) => {
      ctx.save()
      for (const s of world.sparkles) {
        const tw = 0.35 + 0.65 * Math.abs(Math.sin(t * s.twinkle + s.phase))
        const y = s.y - t * 0.012 * (s.drift + 0.6)
        const x = s.x + Math.sin(t * 0.002 + s.phase) * 6
        ctx.globalAlpha = tw
        ctx.fillStyle = s.color
        ctx.shadowBlur = 6
        ctx.shadowColor = s.color
        traceStar(ctx, x, y, s.size)
        ctx.fill()
      }
      ctx.restore()
    }

    const frame = (now: number) => {
      if (!running) return
      const t = now - start
      ctx.clearRect(0, 0, w, h)
      if (active.effect === 'confetti') paintConfetti(t)
      else if (active.effect === 'lasers') paintLasers(t)
      else if (active.effect === 'echo') paintEcho(t)
      else paintSparkles(t)
      if (t >= duration) {
        running = false
        ctx.clearRect(0, 0, w, h)
        onDoneRef.current()
        return
      }
      raf = requestAnimationFrame(frame)
    }
    raf = requestAnimationFrame(frame)

    return () => {
      running = false
      cancelAnimationFrame(raf)
      ctx.clearRect(0, 0, w, h)
    }
  }, [active])

  return (
    <canvas
      ref={canvasRef}
      aria-hidden
      className="pointer-events-none absolute inset-0 z-[55]"
      style={{ willChange: 'contents' }}
    />
  )
}

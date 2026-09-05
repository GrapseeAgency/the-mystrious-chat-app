// ─────────────────────────────────────────────────────────────
// Pulse premium FX (R22) — global particle layer.
// A single fixed full-screen canvas that listens for the app-wide
// `pulse:particle-burst` CustomEvent (contract in src/lib/motion.ts)
// and renders confetti / hearts / stars / spark bursts with
// additive ("lighter") compositing so overlapping particles glow.
//
// Engineering notes:
// · ZERO idle cost — the rAF loop only runs while particles are
//   alive, and is cancelled while document.hidden.
// · Device-pixel-ratio aware backing store, ResizeObserver-driven.
// · Hard cap of 400 simultaneous particles (oldest dropped).
// · prefers-reduced-motion → bursts are skipped entirely.
// · Everything lives in refs — no React state per particle.
//
// R26-e coordination contract: when prefs 'fx.webglMode' selects a
// non-off ambient mode AND the WebGLAmbient canvas (webgl-glow.tsx)
// is actually alive, this DOM particle layer renders nothing so the
// two full-screen FX systems never overdraw simultaneously. If WebGL
// is unavailable (or the ambient is unmounted) the burst layer keeps
// working exactly as before. External API unchanged (mounted bare by
// app-root.tsx).
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useEffect, useRef } from 'react'
import {
  PARTICLE_BURST_EVENT,
  type ParticleBurstDetail,
  type ParticleKind,
  prefersReducedMotion,
} from '@/lib/motion'
import { usePrefs } from '@/lib/prefs'
import {
  PREF_KEY_WEBGL_MODE,
  isWebglMode,
  useWebglAmbientAvailable,
} from '@/components/fx/webgl-glow'

const MAX_PARTICLES = 400
const DEFAULT_COUNT = 80

const CONFETTI_COLORS = ['#10b981', '#14b8a6', '#f59e0b', '#fb7185', '#8b5cf6', '#ffffff']
const HEART_COLORS = ['#fb7185', '#f43f5e', '#fda4af', '#ff6b81']
const STAR_COLORS = ['#fde68a', '#ffffff', '#a7f3d0', '#99f6e4']
const BURST_COLORS = ['#10b981', '#5eead4', '#ffffff', '#fbbf24']

interface Particle {
  kind: ParticleKind
  /** viewport px */
  x: number
  y: number
  vx: number
  vy: number
  /** remaining / total lifetime in frames */
  life: number
  maxLife: number
  /** core size px (3–7) */
  size: number
  color: string
  /** rotation + angular velocity (confetti) */
  rot: number
  vrot: number
  /** per-frame velocity multiplier (<1 = slows down) */
  drag: number
  /** downward acceleration (confetti) — negative = buoyant lift */
  gravity: number
  /** hearts sway amplitude/phase */
  sway: number
  phase: number
  /** stars twinkle speed */
  twinkle: number
}

function rand(min: number, max: number): number {
  return min + Math.random() * (max - min)
}

function pick<T>(arr: readonly T[]): T {
  return arr[(Math.random() * arr.length) | 0]
}

/** Spawn one particle of `kind` from the burst point. */
function makeParticle(kind: ParticleKind, x: number, y: number): Particle {
  const size = rand(3, 7)
  switch (kind) {
    case 'confetti': {
      // upward cone ± ~60° — the classic cannon
      const angle = -Math.PI / 2 + rand(-1.05, 1.05)
      const speed = rand(4, 11.5)
      return {
        kind,
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        life: rand(70, 130),
        maxLife: 130,
        size,
        color: pick(CONFETTI_COLORS),
        rot: rand(0, Math.PI * 2),
        vrot: rand(-0.28, 0.28),
        drag: 0.985,
        gravity: 0.16,
        sway: 0,
        phase: 0,
        twinkle: 0,
      }
    }
    case 'hearts': {
      const angle = -Math.PI / 2 + rand(-0.55, 0.55)
      const speed = rand(1.2, 3.2)
      return {
        kind,
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - rand(0.4, 1.1), // float-up drift
        life: rand(90, 160),
        maxLife: 160,
        size: rand(4, 8),
        color: pick(HEART_COLORS),
        rot: 0,
        vrot: 0,
        drag: 0.992,
        gravity: -0.004, // gentle lift, no fall
        sway: rand(0.018, 0.05),
        phase: rand(0, Math.PI * 2),
        twinkle: 0,
      }
    }
    case 'stars': {
      const angle = rand(0, Math.PI * 2)
      const speed = rand(1.5, 4.5)
      return {
        kind,
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - rand(0.5, 1.4), // float-up drift
        life: rand(70, 130),
        maxLife: 130,
        size: rand(3.5, 7),
        color: pick(STAR_COLORS),
        rot: rand(0, Math.PI),
        vrot: rand(-0.05, 0.05),
        drag: 0.99,
        gravity: -0.003,
        sway: 0,
        phase: rand(0, Math.PI * 2),
        twinkle: rand(0.15, 0.35),
      }
    }
    case 'burst': {
      // radial spark lines — every direction, fast, short-lived
      const angle = rand(0, Math.PI * 2)
      const speed = rand(5, 13)
      return {
        kind,
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        life: rand(26, 52),
        maxLife: 52,
        size,
        color: pick(BURST_COLORS),
        rot: 0,
        vrot: 0,
        drag: 0.94,
        gravity: 0.02,
        sway: 0,
        phase: 0,
        twinkle: 0,
      }
    }
  }
}

// ── draw helpers (all run under globalCompositeOperation='lighter') ──

function drawConfetti(ctx: CanvasRenderingContext2D, p: Particle, alpha: number): void {
  ctx.save()
  ctx.translate(p.x, p.y)
  ctx.rotate(p.rot)
  ctx.globalAlpha = alpha
  ctx.fillStyle = p.color
  // rectangle with a ribbon-like shimmer (height pulses as it tumbles)
  const h = p.size * 0.7 * (0.55 + 0.45 * Math.abs(Math.sin(p.rot * 1.7)))
  ctx.fillRect(-p.size * 0.9, -h / 2, p.size * 1.8, h)
  ctx.restore()
}

function drawHeart(ctx: CanvasRenderingContext2D, p: Particle, alpha: number, frame: number): void {
  const swayX = Math.sin(frame * p.sway + p.phase) * 6
  ctx.save()
  ctx.translate(p.x + swayX, p.y)
  ctx.globalAlpha = alpha
  ctx.fillStyle = p.color
  ctx.font = `${Math.round(p.size * 2.4)}px system-ui, sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText('♥', 0, 0)
  ctx.restore()
}

function drawStar(ctx: CanvasRenderingContext2D, p: Particle, alpha: number, frame: number): void {
  // 4-point sparkle with a twinkle scale pulse
  const tw = 0.68 + 0.32 * Math.sin(frame * p.twinkle * Math.PI * 2 + p.phase)
  const r = p.size * 1.7 * tw
  const inner = r * 0.32
  ctx.save()
  ctx.translate(p.x, p.y)
  ctx.rotate(p.rot)
  ctx.globalAlpha = alpha
  ctx.fillStyle = p.color
  ctx.beginPath()
  for (let i = 0; i < 4; i++) {
    const outerA = (i * Math.PI) / 2 - Math.PI / 2
    const innerA = outerA + Math.PI / 4
    ctx.lineTo(Math.cos(outerA) * r, Math.sin(outerA) * r)
    ctx.lineTo(Math.cos(innerA) * inner, Math.sin(innerA) * inner)
  }
  ctx.closePath()
  ctx.fill()
  ctx.restore()
}

function drawBurst(ctx: CanvasRenderingContext2D, p: Particle, alpha: number): void {
  // spark line trailing backwards along its velocity
  ctx.save()
  ctx.globalAlpha = alpha
  ctx.strokeStyle = p.color
  ctx.lineWidth = Math.max(1, p.size * 0.38)
  ctx.lineCap = 'round'
  ctx.beginPath()
  ctx.moveTo(p.x, p.y)
  ctx.lineTo(p.x - p.vx * 2.4, p.y - p.vy * 2.4)
  ctx.stroke()
  ctx.restore()
}

export const ParticleLayer = memo(function ParticleLayer() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  // R26-e: same prefs key the WebGLAmbient reads — one owner of the
  // full-screen FX surface at a time (and only when it truly renders).
  const webglModeRaw = usePrefs((s) => (s.prefs as unknown as Record<string, unknown>)[PREF_KEY_WEBGL_MODE])
  const ambientAlive = useWebglAmbientAvailable()
  const webglAmbientActive = isWebglMode(webglModeRaw) && webglModeRaw !== 'off' && ambientAlive

  useEffect(() => {
    if (webglAmbientActive) return // WebGL ambient owns the background — layer disabled
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const particles: Particle[] = []
    const state = { w: 0, h: 0, raf: 0, frame: 0 }

    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      const w = Math.max(1, Math.round(rect.width))
      const h = Math.max(1, Math.round(rect.height))
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.round(w * dpr)
      canvas.height = Math.round(h * dpr)
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      state.w = w
      state.h = h
    }
    resize()

    const ro = new ResizeObserver(() => {
      resize()
    })
    ro.observe(canvas)

    const step = () => {
      ctx.clearRect(0, 0, state.w, state.h)
      // additive compositing — overlapping particles glow like light
      ctx.globalCompositeOperation = 'lighter'
      state.frame++
      for (let i = particles.length - 1; i >= 0; i--) {
        const p = particles[i]
        p.life--
        if (p.life <= 0 || p.y > state.h + 40 || p.x < -40 || p.x > state.w + 40) {
          particles.splice(i, 1)
          continue
        }
        // physics: drag + gravity/lift (+ hearts sway injected at draw time)
        p.vx *= p.drag
        p.vy = p.vy * p.drag + p.gravity
        p.x += p.vx
        p.y += p.vy
        p.rot += p.vrot

        const t = p.life / p.maxLife
        const alpha = t > 0.75 ? 1 : t / 0.75 // hold, then linear fade-out

        if (p.kind === 'confetti') drawConfetti(ctx, p, alpha)
        else if (p.kind === 'hearts') drawHeart(ctx, p, alpha, state.frame)
        else if (p.kind === 'stars') drawStar(ctx, p, alpha, state.frame)
        else drawBurst(ctx, p, alpha)
      }
      ctx.globalCompositeOperation = 'source-over'
      ctx.globalAlpha = 1
    }

    const tick = () => {
      state.raf = 0
      step()
      if (particles.length > 0 && !document.hidden) {
        state.raf = requestAnimationFrame(tick)
      } else if (particles.length === 0) {
        // loop is dead — leave a clean transparent canvas behind
        ctx.clearRect(0, 0, state.w, state.h)
      }
    }

    const kick = () => {
      if (state.raf === 0 && !document.hidden && particles.length > 0) {
        state.raf = requestAnimationFrame(tick)
      }
    }

    const onBurst = (event: Event) => {
      if (prefersReducedMotion()) return
      const detail = (event as CustomEvent<ParticleBurstDetail>).detail ?? {}
      const kind: ParticleKind = detail.kind ?? 'confetti'
      const count = Math.max(1, Math.min(Math.round(detail.count ?? DEFAULT_COUNT), MAX_PARTICLES))
      const x = (detail.x ?? 0.5) * state.w
      const y = (detail.y ?? 0.6) * state.h

      for (let i = 0; i < count; i++) particles.push(makeParticle(kind, x, y))
      if (particles.length > MAX_PARTICLES) {
        particles.splice(0, particles.length - MAX_PARTICLES) // drop oldest
      }
      kick()
    }

    const onVisibility = () => {
      if (document.hidden) {
        if (state.raf !== 0) {
          cancelAnimationFrame(state.raf)
          state.raf = 0
        }
      } else {
        kick()
      }
    }

    window.addEventListener(PARTICLE_BURST_EVENT, onBurst)
    document.addEventListener('visibilitychange', onVisibility)

    return () => {
      window.removeEventListener(PARTICLE_BURST_EVENT, onBurst)
      document.removeEventListener('visibilitychange', onVisibility)
      ro.disconnect()
      if (state.raf !== 0) cancelAnimationFrame(state.raf)
      state.raf = 0
      particles.length = 0
    }
  }, [webglAmbientActive])

  if (webglAmbientActive) return null

  return (
    <canvas
      ref={canvasRef}
      aria-hidden
      className="pointer-events-none fixed inset-0 z-[95] h-full w-full"
    />
  )
})

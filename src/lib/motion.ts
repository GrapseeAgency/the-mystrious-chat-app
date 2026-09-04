/**
 * Pulse premium motion system (R22) — single source of truth for springs,
 * easings and the app-wide particle FX contract. Every surface imports from
 * here so the whole app moves with one physical language.
 *
 * Principles (from the Telegram/iMessage/Discord motion research):
 * - Springs over durations: mass/stiffness/damping, velocity-aware.
 * - Non-linear cubic-bezier easings for anything that isn't a spring.
 * - Compositor-only properties (transform/opacity) + will-change isolation.
 * - Squash-and-stretch: fast enters overshoot, exits are quicker than enters.
 */
import type { Transition } from 'framer-motion'

/** Spring presets — pick by intent, never hand-roll one-off transitions. */
export const spring = {
  /** UI chrome: tabs, pills, indicators. Fast settle, no wobble. */
  snappy: { type: 'spring', stiffness: 500, damping: 34, mass: 0.9 },
  /** Sheets, cards, layout shifts. A touch of life without bounce. */
  soft: { type: 'spring', stiffness: 300, damping: 28, mass: 1 },
  /** Playful pops: badges, reactions, bubbles. Visible overshoot. */
  bouncy: { type: 'spring', stiffness: 620, damping: 20, mass: 0.85 },
  /** Ambient drift: parallax, hero glow. Barely-there motion. */
  gentle: { type: 'spring', stiffness: 140, damping: 22, mass: 1.1 },
} satisfies Record<string, Transition>

/** Signature cubic-beziers for non-spring (opacity/blur/color) transitions. */
export const ease = {
  /** "Swift out" — content appearing. */
  out: [0.16, 1, 0.3, 1] as [number, number, number, number],
  /** Balanced in-out for crossfades/morphs. */
  inOut: [0.65, 0, 0.35, 1] as [number, number, number, number],
  /** Back-overshoot for stickers/emoji-scale pops. */
  overshoot: [0.34, 1.56, 0.64, 1] as [number, number, number, number],
}

/** Standard stagger recipe for list/tile entrances. */
export const stagger = (i: number, step = 0.04, cap = 10) =>
  Math.min(i, cap) * step

/** Press micro-interaction — pair with `whileTap` for tactile buttons. */
export const pressTap = { scale: 0.94 }
export const pressSpring = spring.snappy

// ── App-wide particle FX contract (decoupled via a window event so any
// surface can fire bursts without importing the FX layer) ────────────────

export type ParticleKind = 'confetti' | 'hearts' | 'stars' | 'burst'

export interface ParticleBurstDetail {
  /** viewport x 0..1 (default 0.5) */
  x?: number
  /** viewport y 0..1 (default 0.6) */
  y?: number
  kind?: ParticleKind
  /** particle count (default 80) */
  count?: number
}

export const PARTICLE_BURST_EVENT = 'pulse:particle-burst'

/** Fire a full-screen particle celebration from anywhere in the app. */
export function fireParticles(detail: ParticleBurstDetail = {}): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<ParticleBurstDetail>(PARTICLE_BURST_EVENT, { detail }))
}

/** Reduced-motion respect for every crew to reuse. */
export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
}

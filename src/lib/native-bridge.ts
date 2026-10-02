// Pulse Native Bridge contract (server + client safe).
// The backend speaks to NATIVE clients (Android Compose, iOS SwiftUI,
// installed PWA shells) through this one vocabulary: every surface that
// can trigger device hardware (vibration, view transitions, full-screen
// particle effects) reads the same values the server publishes.
//
// Two delivery paths carry it:
//   1. GET /api/native/manifest   - full catalog, fetched once per boot.
//   2. realtime `message:new`     - per-message `native` block (parse-free:
//      recipients act on it directly, no payload JSON spelunking).
//
// Values are intentionally boring primitives (strings + numbers) so
// Kotlin/Swift parsers stay trivial.

/** Full-screen message effect ids (mirror of the web canvas engine). */
export const NATIVE_EFFECTS = ['confetti', 'lasers', 'echo', 'sparkles'] as const
export type NativeEffectId = (typeof NATIVE_EFFECTS)[number]

export interface NativeEffectSpec {
  id: NativeEffectId
  /** human label for settings/debug surfaces */
  label: string
  /** vibration pattern in ms ([-, wait, ...] - Android VibrationEffect shape) */
  hapticPattern: number[]
  /** 0..1 normalized amplitude for the haptic */
  hapticIntensity: number
  /** named spring/curve the client should animate with */
  motionCurve: 'spring' | 'ease-out' | 'linear'
  /** total effect runtime in ms (client cleans up after this) */
  durationMs: number
}

export const NATIVE_EFFECT_SPECS: readonly NativeEffectSpec[] = [
  {
    id: 'confetti',
    label: 'Confetti',
    hapticPattern: [0, 18, 40, 18],
    hapticIntensity: 0.7,
    motionCurve: 'spring',
    durationMs: 2600,
  },
  {
    id: 'lasers',
    label: 'Lasers',
    hapticPattern: [0, 8, 30, 8, 30, 14],
    hapticIntensity: 0.9,
    motionCurve: 'linear',
    durationMs: 1900,
  },
  {
    id: 'echo',
    label: 'Echo',
    hapticPattern: [0, 12],
    hapticIntensity: 0.5,
    motionCurve: 'ease-out',
    durationMs: 1700,
  },
  {
    id: 'sparkles',
    label: 'Sparkles',
    hapticPattern: [0, 6, 24, 6, 24, 6],
    hapticIntensity: 0.4,
    motionCurve: 'spring',
    durationMs: 2200,
  },
]

/** Shared haptic vocabulary for non-effect moments (tab switches, sends...). */
export const NATIVE_HAPTICS = {
  selection: { pattern: [8], intensity: 0.3 },
  send: { pattern: [10], intensity: 0.4 },
  receive: { pattern: [12], intensity: 0.4 },
  tabSwitch: { pattern: [8], intensity: 0.3 },
  success: { pattern: [10, 40, 10], intensity: 0.5 },
  warning: { pattern: [20, 60, 20], intensity: 0.6 },
} as const

export type NativeHapticMoment = keyof typeof NATIVE_HAPTICS

export function isNativeEffect(value: unknown): value is NativeEffectId {
  return typeof value === 'string' && (NATIVE_EFFECTS as readonly string[]).includes(value)
}

export function nativeEffectSpec(id: NativeEffectId): NativeEffectSpec {
  return NATIVE_EFFECT_SPECS.find((e) => e.id === id) ?? NATIVE_EFFECT_SPECS[0]
}

/**
 * Parse a message `payload` JSON blob and extract the native action block
 * (effect spec) if one rides along. Returns null for plain messages so
 * relays can omit the block entirely.
 */
export function nativeBlockFromPayload(payload: string | null): {
  effect?: NativeEffectSpec
} | null {
  if (!payload) return null
  try {
    const parsed = JSON.parse(payload) as { effect?: unknown }
    if (!isNativeEffect(parsed?.effect)) return null
    return { effect: nativeEffectSpec(parsed.effect) }
  } catch {
    return null
  }
}

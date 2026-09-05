// ─────────────────────────────────────────────────────────────
// Pulse — XP economy constants (client-safe, zero deps).
// Single source of truth so the REST routes and any UI copy
// reference the same numbers (imported by the messages route).
// ─────────────────────────────────────────────────────────────

/**
 * Daily XP ceiling for message-XP (R31-a). A sender earns at most this
 * much XP from chat sends per UTC day; the cap refills at midnight UTC.
 * Exported so UI copy can quote the number ("Daily XP cap reached").
 */
export const XP_DAILY_CAP = 250

/** XP awarded per sent message (Twitch-style channel points, R24-b). */
export const XP_PER_MESSAGE = 2

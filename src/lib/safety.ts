// ─────────────────────────────────────────────────────────────
// Pulse — Signal-style safety numbers (R35-a).
//
// A safety number is the fingerprint two users compare in person to
// confirm their channel is really end-to-end (Signal's "safety number"
// paradigm). For Pulse the number is DERIVED from the pair of user ids:
// deterministic, identical for (a,b) and (b,a), stable across process
// restarts, and stable for as long as both accounts exist.
//
// Server-focused module: it pulls node:crypto, so do NOT import it from
// client components — the UI renders the groups the API already formatted
// (and can split the raw digit string itself with a trivial local helper).
// The module stays PURE (no db import) so scripts and tests can use it too.
// ─────────────────────────────────────────────────────────────
import { createHash } from 'node:crypto'

/** Layout — Signal's canonical format: 12 groups of 5 digits = 60 digits. */
export const SAFETY_DIGIT_GROUPS = 12
export const SAFETY_GROUP_DIGITS = 5
export const SAFETY_TOTAL_DIGITS = SAFETY_DIGIT_GROUPS * SAFETY_GROUP_DIGITS

/**
 * Application pepper folded into every digest so the number is meaningful
 * only inside Pulse (rotating it re-keys every pair at once — a deliberate
 * operational escape hatch, never done lightly).
 */
const SAFETY_PEPPER = 'pulse-safety-pepper-v1'

/**
 * Derive the shared 60-digit safety number for a pair of user ids.
 *
 * - Symmetric: deriveSafetyNumber(a, b) === deriveSafetyNumber(b, a) — the
 *   ids are sorted before hashing, so both sides see the same number.
 * - Deterministic: plain sha256 over "min|max|pepper" (sorted with the
 *   default lexicographic order, stable for ASCII cuids) expanded to 60
 *   decimal digits via exact BigInt modulo — identical across processes,
 *   restarts and machines.
 * - Not a secret: like Signal's number it is meant to be compared out loud,
 *   not kept hidden; verification is the state stored in the DB, not the
 *   digits themselves.
 */
export function deriveSafetyNumber(a: string, b: string): string {
  const [first, second] = [a, b].sort()
  const digestHex = createHash('sha256')
    .update(`${first}|${second}|${SAFETY_PEPPER}`)
    .digest('hex')

  // 256 bits → uniform 60-digit decimal: exact BigInt modulo 10^60,
  // zero-padded so leading zeros are preserved (the format demands
  // exactly SAFETY_TOTAL_DIGITS digits every time). No BigInt literals —
  // the project targets pre-ES2020; the constructor calls are equivalent.
  const modulus = BigInt(10) ** BigInt(SAFETY_TOTAL_DIGITS)
  const digits = (BigInt(`0x${digestHex}`) % modulus)
    .toString()
    .padStart(SAFETY_TOTAL_DIGITS, '0')

  return formatSafetyNumber(digits)
}

/** Raw 60-digit string → "##### ##### …" (12 groups joined by single spaces). */
export function formatSafetyNumber(digits: string): string {
  return formatSafetyGroups(digits).join(' ')
}

/**
 * Pure unit-style helper: split a raw 60-digit string into exactly 12
 * five-digit group strings. Non-digits are ignored; short input is
 * zero-padded on the left so the layout constant always holds.
 */
export function formatSafetyGroups(digits: string): string[] {
  const clean = digits
    .replace(/\D/g, '')
    .padStart(SAFETY_TOTAL_DIGITS, '0')
    .slice(-SAFETY_TOTAL_DIGITS)
  const groups: string[] = []
  for (let i = 0; i < SAFETY_DIGIT_GROUPS; i += 1) {
    groups.push(clean.slice(i * SAFETY_GROUP_DIGITS, (i + 1) * SAFETY_GROUP_DIGITS))
  }
  return groups
}

// Pulse account-pair comparison codes. These numbers hash public account IDs,
// not device identity keys. A matching code or saved checkmark does NOT prove
// end-to-end encryption, detect a compromised server, or authenticate a device.
// Both accounts see the same stable code; the owner's saved marker is private.
//
// Server-focused module: it pulls node:crypto, so do NOT import it from
// client components - the UI renders the groups the API already formatted
// (and can split the raw digit string itself with a trivial local helper).
// The module stays PURE (no db import) so scripts and tests can use it too.
import { createHash } from 'node:crypto'

/** Display layout: 12 groups of 5 digits = 60 digits. */
export const SAFETY_DIGIT_GROUPS = 12
export const SAFETY_GROUP_DIGITS = 5
export const SAFETY_TOTAL_DIGITS = SAFETY_DIGIT_GROUPS * SAFETY_GROUP_DIGITS

/**
 * Public domain-separation string folded into every digest so the number is meaningful
 * only inside Pulse (changing it changes every comparison code - a deliberate
 * operational escape hatch, never done lightly).
 */
const SAFETY_PEPPER = 'pulse-safety-pepper-v1'

/**
 * Derive the shared 60-digit safety number for a pair of user ids.
 *
 * - Symmetric: deriveSafetyNumber(a, b) === deriveSafetyNumber(b, a) - the
 *   ids are sorted before hashing, so both sides see the same number.
 * - Deterministic: plain sha256 over "min|max|pepper" (sorted with the
 *   default lexicographic order, stable for ASCII cuids) expanded to 60
 *   decimal digits via exact BigInt modulo - identical across processes,
 *   restarts and machines.
 * - Not a secret: the number is derived entirely from public input and may be compared out loud,
 *   not kept hidden; verification is the state stored in the DB, not the
 *   digits themselves.
 */
export function deriveSafetyNumber(a: string, b: string): string {
  const [first, second] = [a, b].sort()
  const digestHex = createHash('sha256')
    .update(`${first}|${second}|${SAFETY_PEPPER}`)
    .digest('hex')

  // 256 bits → 60-digit decimal: exact BigInt modulo 10^60,
  // zero-padded so leading zeros are preserved (the format demands
  // exactly SAFETY_TOTAL_DIGITS digits every time). No BigInt literals -
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

// /api session tokens - master spec §3.11 (A-1/A-2), Wave 8.
//
// Model: the server issues a random 32-byte token (hex) at identity
// create and reclaim-login. Only the sha256 hash is stored
// (User.sessionTokenHash) - the raw token lives in the native
// Keystore/Keychain and travels as `Authorization: Bearer <token>`
// plus socket `join{userId, token}`.
//
// Migration semantics (spec: "optional-verify during migration so
// web keeps working"): a MISSING token is accepted (web + pre-token
// natives keep working); a PRESENT-but-invalid token is rejected 401.
// Web adoption of the token is phase 2 (separate product decision).
import { createHash, randomBytes } from 'crypto'

/** 32 random bytes, hex - the raw token handed to the client exactly once. */
export function generateSessionToken(): string {
  return randomBytes(32).toString('hex')
}

/** Stored form - sha256 hex of the raw token. Never logged, never returned. */
export function hashSessionToken(raw: string): string {
  return createHash('sha256').update(raw, 'utf8').digest('hex')
}

/** Bearer header parser - returns the raw token or null (absent = migration-accepted). */
export function bearerToken(req: Request): string | null {
  const header = req.headers.get('authorization') ?? ''
  const match = /^Bearer\s+(.+)$/i.exec(header.trim())
  return match ? match[1].trim() : null
}

// R52 - token grace list. A single stored hash made every login a
// global log-out: the demo identity is shared across devices + QA
// (phone, web preview, scripts) and each reclaim rotated the hash,
// instantly 401-ing every other session, which the native clients
// answer by tearing the device session down (the "can barely log in,
// demo isn't working" loop). Now a login keeps the previous hash in
// User.legacyTokenHashes (JSON array, newest first, capped) and every
// validator accepts the primary hash OR any grace-listed one.

const LEGACY_TOKEN_CAP = 8

/** Parse the stored legacy-hash JSON array; tolerant of garbage/null. */
export function parseLegacyTokenHashes(json: string | null | undefined): string[] {
  if (!json) return []
  try {
    const parsed: unknown = JSON.parse(json)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((v): v is string => typeof v === 'string' && v.length > 0)
  } catch {
    return []
  }
}

/**
 * Rotate bookkeeping: push the outgoing primary hash onto the grace list
 * (newest first, dedup, capped) and return the serialized array for
 * User.legacyTokenHashes.
 */
export function pushLegacyTokenHash(previousHash: string | null | undefined, legacyJson: string | null | undefined): string {
  const list = parseLegacyTokenHashes(legacyJson)
  if (previousHash) {
    const without = list.filter((h) => h !== previousHash)
    without.unshift(previousHash)
    return JSON.stringify(without.slice(0, LEGACY_TOKEN_CAP))
  }
  return JSON.stringify(list.slice(0, LEGACY_TOKEN_CAP))
}

/**
 * Acceptance check used by every validator (proxy, internal verify):
 * the presented raw token matches the user's primary hash OR any
 * grace-listed previous hash.
 */
export function tokenAccepted(
  user: { sessionTokenHash: string | null; legacyTokenHashes?: string | null } | null | undefined,
  rawToken: string,
): boolean {
  if (!user) return false
  const hash = hashSessionToken(rawToken)
  if (user.sessionTokenHash && user.sessionTokenHash === hash) return true
  return parseLegacyTokenHashes(user.legacyTokenHashes).includes(hash)
}

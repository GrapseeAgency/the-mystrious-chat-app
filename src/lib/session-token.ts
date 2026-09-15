// ─────────────────────────────────────────────────────────────
// /api session tokens — master spec §3.11 (A-1/A-2), Wave 8.
//
// Model: the server issues a random 32-byte token (hex) at identity
// create and reclaim-login. Only the sha256 hash is stored
// (User.sessionTokenHash) — the raw token lives in the native
// Keystore/Keychain and travels as `Authorization: Bearer <token>`
// plus socket `join{userId, token}`.
//
// Migration semantics (spec: "optional-verify during migration so
// web keeps working"): a MISSING token is accepted (web + pre-token
// natives keep working); a PRESENT-but-invalid token is rejected 401.
// Web adoption of the token is phase 2 (separate product decision).
// ─────────────────────────────────────────────────────────────
import { createHash, randomBytes } from 'crypto'

/** 32 random bytes, hex — the raw token handed to the client exactly once. */
export function generateSessionToken(): string {
  return randomBytes(32).toString('hex')
}

/** Stored form — sha256 hex of the raw token. Never logged, never returned. */
export function hashSessionToken(raw: string): string {
  return createHash('sha256').update(raw, 'utf8').digest('hex')
}

/** Bearer header parser — returns the raw token or null (absent = migration-accepted). */
export function bearerToken(req: Request): string | null {
  const header = req.headers.get('authorization') ?? ''
  const match = /^Bearer\s+(.+)$/i.exec(header.trim())
  return match ? match[1].trim() : null
}

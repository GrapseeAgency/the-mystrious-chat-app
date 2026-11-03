// /api session token - WEB client adoption (Wave 8 §3.11 phase 2,
// Section 16 item 4: legacy web credential migration).
//
// The server hands every identity a 32-byte hex credential (raw shown once,
// sha256 stored) and the hardened routes (conversations, messages, settings,
// uploads, ...) reject headerless calls with 401 (wallet-authorization.ts:
// "401 without a bearer" - they deliberately do NOT inherit the proxy's
// headerless migration window). Native apps already persist the token in
// Keystore/Keychain and attach `Authorization: Bearer` on EVERY request
// (Wave8ApiTest pins that on every request family). The shared web apiJson
// callers previously supplied no credentials at all, which is exactly why
// this adoption exists.
//
// Storage model:
//   - the raw token for a user id lives in localStorage under
//     `pulse.token.v2.<userId>` so every tab of the same browser REUSES one
//     mint instead of rotating on every boot. Rotation still happens once
//     per browser per identity (login = reclaim = rotate), and the R52 grace
//     list (User.legacyTokenHashes, cap 8) absorbs the cross-device churn:
//     last login wins the primary hash, earlier sessions keep working until
//     the cap evicts them.
//   - identity creation captures the token from the 201 response directly.
//   - reclaim-style sign-ins (deep link, "log in again", the silent demo
//     identity, rehydrated sessions that predate this adoption) reuse the
//     stored token when one exists and only mint when none does.
//   - explicit sign-out drops the stored credential for that user.

const TOKEN_PREFIX = 'pulse.token.v2.'
const TOKEN_MAX = 64

/** Read the stored raw token for a user; null when absent or storage is unreadable. */
export function readStoredToken(userId: string): string | null {
  if (typeof window === 'undefined' || !userId) return null
  try {
    const token = window.localStorage.getItem(TOKEN_PREFIX + userId)
    if (typeof token !== 'string' || token.length === 0 || token.length > TOKEN_MAX) return null
    return token
  } catch {
    return null
  }
}

/** Persist (or clear, on null) the raw token for a user. Storage failures are honest no-ops. */
export function storeSessionToken(userId: string, token: string | null): void {
  if (typeof window === 'undefined' || !userId) return
  try {
    if (typeof token === 'string' && token.length > 0) {
      window.localStorage.setItem(TOKEN_PREFIX + userId, token)
    } else {
      window.localStorage.removeItem(TOKEN_PREFIX + userId)
    }
  } catch {
    /* private mode / quota - requests stay headerless and the 401s speak */
  }
}

/** Sign-out: drop the credential for this user (a later sign-in mints fresh). */
export function clearSessionToken(userId: string): void {
  storeSessionToken(userId, null)
}

/**
 * Mint (rotate) a credential for an existing name via POST /api/users/login.
 * Returns null on any failure - callers keep the headerless window honestly
 * instead of inventing a token. Never throws.
 */
export async function mintSessionToken(name: string): Promise<string | null> {
  if (typeof window === 'undefined' || !name.trim()) return null
  try {
    const res = await fetch('/api/users/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
      cache: 'no-store',
    })
    if (!res.ok) return null
    const body = (await res.json().catch(() => null)) as { token?: unknown } | null
    if (typeof body?.token !== 'string' || body.token.length === 0 || body.token.length > TOKEN_MAX) {
      return null
    }
    return body.token
  } catch {
    return null
  }
}

/**
 * Make sure the active user HAS a stored credential: reuse the stored token
 * when one exists; mint (one rotation, absorbed by the grace list) only when
 * none does. Await this before setUser so the shell's first authenticated
 * query already finds the token.
 */
export async function ensureSessionCredential(user: { id: string; name: string }): Promise<void> {
  if (readStoredToken(user.id)) return
  const token = await mintSessionToken(user.name)
  if (token) storeSessionToken(user.id, token)
}

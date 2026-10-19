import { db } from '@/lib/db'
import { bearerToken, tokenAccepted } from '@/lib/session-token'

/** Monetary v2 routes bind the existing native credential to the debit owner.
 * Do not inherit the legacy proxy's token-valid-for-any-user semantics. */
export async function requireWalletOwner(req: Request, userId: unknown): Promise<Response | null> {
  return requireActionOwner(req, userId, 'wallet')
}

export async function requireActionOwner(req: Request, userId: unknown, action: string): Promise<Response | null> {
  const token = bearerToken(req)
  if (!token) return Response.json({ error: `A ${action} session credential is required.` }, { status: 401 })
  if (typeof userId !== 'string' || !userId) return Response.json({ error: 'An action owner is required.' }, { status: 400 })
  const owner = await db.user.findUnique({ where: { id: userId }, select: { sessionTokenHash: true, legacyTokenHashes: true } })
  if (!tokenAccepted(owner, token)) return Response.json({ error: `This session does not own the requested ${action} action.` }, { status: 403 })
  return null
}

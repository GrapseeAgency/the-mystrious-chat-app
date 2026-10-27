import { db } from '@/lib/db'
import { bearerToken, tokenAccepted, hashSessionToken } from '@/lib/session-token'

/** All monetary entry points bind the existing native credential to the debit owner.
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


/** Read endpoints without a claimed user ID derive the actor from the credential,
 * never from a room ID, cursor or a proxy's "some valid user" decision. */
export async function requireActionActor(req: Request, action: string): Promise<{ userId: string } | Response> {
  const token = bearerToken(req)
  if (!token) return Response.json({ error: `A ${action} session credential is required.` }, { status: 401 })
  const hash = hashSessionToken(token)
  const candidates = await db.user.findMany({ where: { OR: [
    { sessionTokenHash: hash }, { legacyTokenHashes: { contains: JSON.stringify(hash) } },
  ] }, select: { id: true, sessionTokenHash: true, legacyTokenHashes: true }, take: 2 })
  if (candidates.length !== 1 || !tokenAccepted(candidates[0], token)) {
    return Response.json({ error: `This session cannot identify the ${action} owner.` }, { status: 403 })
  }
  return { userId: candidates[0].id }
}

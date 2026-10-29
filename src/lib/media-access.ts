// /lib/media-access - R44 section-5 "Media URL authorization/revocation".
// Previously reachable media URLs had NO authenticated lifetime: once a
// native had fetched /api/uploads/<file> the bytes stayed fetchable forever,
// for anyone, with no membership re-check and no revocation on delete.
// This module closes the code half: a PRESENT credential must prove live
// access (membership in a conversation that still carries the file on a
// non-deleted, non-expired message, OR ownership of the profile/story asset)
// before GET serves anything. Headerless requests keep the documented
// migration window (session-token.ts: a MISSING token is accepted, a
// PRESENT-but-invalid one is rejected 401) so the web and pre-token natives
// do not break; the natives attach their token on every media fetch.
import { db } from '@/lib/db'
import { bearerToken, tokenAccepted, hashSessionToken } from '@/lib/session-token'

export type MediaAccessDecision =
  | { kind: 'open' } // migration window: no credential presented
  | { kind: 'granted' } // credential presented AND proven
  | { kind: 'denied'; status: 401 | 403; reason: string }

/** The credential must resolve to exactly one user (requireActionActor parity). */
async function actorOf(token: string): Promise<{ id: string } | null> {
  const hash = hashSessionToken(token)
  const candidates = await db.user.findMany({ where: { OR: [
    { sessionTokenHash: hash },
    { legacyTokenHashes: { contains: JSON.stringify(hash) } },
  ] }, select: { id: true, sessionTokenHash: true, legacyTokenHashes: true }, take: 2 })
  if (candidates.length !== 1 || !tokenAccepted(candidates[0], token)) return null
  return { id: candidates[0].id }
}

/**
 * GET /api/uploads/<file> authorization. Rules:
 *  - no Authorization header → 'open' (web/pre-token migration window, same
 *    semantics every other optional-verify endpoint uses)
 *  - token does not identify a user → 401
 *  - file still referenced by a live message in a conversation the actor
 *    participates in → 'granted' (tombstoned/expired messages stop counting:
 *    the delete/expiry IS the revocation)
 *  - file is the actor's own avatar/cover, their story image, or a photo of a
 *    conversation they participate in → 'granted' (asset owners keep access)
 *  - anything else → 403
 * Membership, deletedAt and expiresAt are re-checked on EVERY request: a
 * URL that worked yesterday can 403 today (left the room / message deleted /
 * TTL elapsed). That is the "authenticated lifetime" the checklist item asks
 * for; combined with the existing private,no-store headers it bounds the
 * previously-forever fetch window.
 */
export async function mediaAccessDecision(req: Request, file: string, clock: () => Date = () => new Date()): Promise<MediaAccessDecision> {
  const token = bearerToken(req)
  if (!token) return { kind: 'open' }
  const actor = await actorOf(token)
  if (!actor) return { kind: 'denied', status: 401, reason: 'This session is no longer valid.' }

  const now = clock()
  // A live message still carrying the file in a room the actor belongs to.
  const liveMessage = await db.message.findFirst({ where: {
    AND: [
      { OR: [{ imagePath: file }, { audioPath: file }, { filePath: file }] },
      { deletedAt: null },
      { conversation: { participants: { some: { userId: actor.id } } } },
    ],
  }, select: { id: true, expiresAt: true } })
  if (liveMessage && !(liveMessage.expiresAt && liveMessage.expiresAt <= now)) return { kind: 'granted' }

  // Own profile assets (avatar/cover) stay reachable for the owner.
  const ownAsset = await db.user.findFirst({ where: {
    id: actor.id, OR: [{ avatar: file }, { coverImage: file }],
  }, select: { id: true } })
  if (ownAsset) return { kind: 'granted' }

  // Live stories by anyone the actor shares a conversation with (the same
  // social-graph audience rule the /api/stories feed applies). A story's
  // 24h expiry is its own revocation: past it, the image stops serving.
  const liveStory = await db.statusStory.findFirst({ where: {
    imagePath: file, expiresAt: { gt: now },
    user: { participations: { some: { conversation: { participants: { some: { userId: actor.id } } } } } },
  }, select: { id: true } })
  if (liveStory) return { kind: 'granted' }
  // The actor's own stories: reachable while live; the 24h expiry revokes
  // even the owner (parity with the /api/stories feed, which hard-filters
  // expired rows for everyone including the author).
  const ownStory = await db.statusStory.findFirst({ where: {
    userId: actor.id, imagePath: file, expiresAt: { gt: now },
  }, select: { id: true } })
  if (ownStory) return { kind: 'granted' }

  // Group/channel photos of rooms the actor is in.
  const roomPhoto = await db.conversation.findFirst({ where: {
    photo: file, participants: { some: { userId: actor.id } },
  }, select: { id: true } })
  if (roomPhoto) return { kind: 'granted' }

  return { kind: 'denied', status: 403, reason: 'This media is no longer shared with you.' }
}

import type { PrismaClient } from '../../prisma/generated-client'

/**
 * Section 11 item 10 — explicit game-carrier lifetime policy.
 *
 * A match/tournament rides a REAL chat message (kind 'game'). Before this
 * contract the resource outlived its carrier silently: deleting or expiring
 * the bubble left a live, playable board floating in history. The decided
 * policy:
 *
 *  - deleting or expiring a MATCH carrier abandons the still-active match
 *    (status 'abandoned', the schema's own verdict): history stays readable,
 *    but no further moves are accepted and no rewards are awarded;
 *  - a TOURNAMENT carrier is a season container, not a turn-based resource:
 *    removing its announcement bubble does NOT finish the season (players may
 *    still accrue standings), matching how the card already keeps polling;
 *  - creation replay of a deleted/expired carrier stays an honest 410
 *    creation_gone (Section 10 contract, unchanged).
 *
 * Corrupt/unreadable payloads are ignored: a historic carrier that lost its
 * JSON must not crash the delete path.
 */
export function matchIdsFromPayloads(payloads: Array<string | null>): string[] {
  const ids: string[] = []
  for (const raw of payloads) {
    if (!raw) continue
    try {
      const parsed: unknown = JSON.parse(raw)
      const matchId = (parsed as { matchId?: unknown })?.matchId
      if (typeof matchId === 'string' && matchId.length > 0 && matchId.length <= 64) ids.push(matchId)
    } catch { /* historic corrupt payload - skip */ }
  }
  return [...new Set(ids)]
}

type MatchUpdater = { gameMatch: { updateMany(args: { where: { id: { in: string[] }, status: 'active' }, data: { status: 'abandoned' } }): Promise<{ count: number }> } }

/** Inside the caller's tx: abandon active matches whose carrier just died. */
export async function abandonMatchesForCarriers(tx: MatchUpdater, matchIds: string[]) {
  if (matchIds.length === 0) return 0
  const result = await tx.gameMatch.updateMany({
    where: { id: { in: matchIds }, status: 'active' },
    data: { status: 'abandoned' },
  })
  return result.count
}

/** Read-side sweep used by the disappearing-message purge: abandon active
 * matches carried by messages about to be hard-deleted. */
export async function abandonMatchesForExpiredCarriers(db: PrismaClient, conversationId: string, before: Date) {
  const rows = await db.message.findMany({
    where: { conversationId, kind: 'game', expiresAt: { lte: before }, deletedAt: null },
    select: { payload: true },
  })
  return abandonMatchesForCarriers(db, matchIdsFromPayloads(rows.map(row => row.payload)))
}

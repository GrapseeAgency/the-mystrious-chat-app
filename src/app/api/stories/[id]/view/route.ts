// Owner viewer list (paginated) and audience-authorized, idempotent view mark.
import { randomUUID } from 'node:crypto'
import { Prisma } from '../../../../../../prisma/generated-client'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import {
  encodeStoryCursor, parseStoryCursor, parseStoryPageLimit, STORY_VIEWERS_PAGE_MAX,
  privateStoryResponse, storyAudience, storyUnblocked, storyReceiptDisclosed,
  storyViewerInAuthorAudience, storyJson,
} from '@/lib/story-access'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

/**
 * GET /api/stories/{id}/view?requesterId=&limit=&cursor= - owner-only.
 * Section 9 item 3: a viewer's receipt is disclosed ONLY while that viewer's
 * live storyViewReceipts column is on (and the pair is unblocked) - the list
 * and every view count share ONE predicate, so they can never disagree.
 * Section 9 item 2: `limit` (1..100) walks viewer rows oldest-first with a
 * (createdAt,userId) keyset cursor; legacy callers keep the full shape.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  const limit = parseStoryPageLimit(url.searchParams.get('limit'), STORY_VIEWERS_PAGE_MAX)
  if (url.searchParams.has('limit') && limit === null) {
    return storyJson({ error: `limit must be an integer 1..${STORY_VIEWERS_PAGE_MAX}.` }, { status: 400 })
  }
  const cursor = parseStoryCursor(url.searchParams.get('cursor'))
  if (url.searchParams.has('cursor') && !cursor) {
    return storyJson({ error: 'cursor is invalid.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)
  return db.$transaction(async tx => {
    const rows = await tx.$queryRaw<Array<{ userId: string }>>(
      Prisma.sql`SELECT userId FROM StatusStory WHERE id = ${id} AND expiresAt > ${Date.now()} LIMIT 1`,
    )
    if (!rows[0]) return storyJson({ error: 'Story not found.' }, { status: 404 })
    if (rows[0].userId !== requesterId) return storyJson({ error: 'Only the owner can see viewers.' }, { status: 403 })
    const receiptVisible = storyReceiptDisclosed(Prisma.sql`v.userId`)
    // The owner IS the requester here (403 gate above) - their audience bounds disclosure.
    const viewerAudience = storyViewerInAuthorAudience(Prisma.sql`${requesterId}`, Prisma.sql`v.userId`)
    const pageGuard = limit
      ? Prisma.sql`LIMIT ${limit + 1}`
      : Prisma.empty
    const views = await tx.$queryRaw<Array<{ userId: string; name: string; username: string | null; color: string; createdAt: number | bigint }>>(
      cursor
        ? Prisma.sql`SELECT v.userId, u.name, u.username, u.color, v.createdAt
                 FROM StoryView v JOIN User u ON u.id = v.userId
                 WHERE v.storyId = ${id}
                   AND ${storyUnblocked(Prisma.sql`${requesterId}`, Prisma.sql`v.userId`)}
                   AND ${receiptVisible} AND ${viewerAudience}
                   AND (v.createdAt > ${cursor.atMs} OR (v.createdAt = ${cursor.atMs} AND v.userId > ${cursor.id}))
                 ORDER BY v.createdAt ASC, v.userId ASC ${pageGuard}`
        : Prisma.sql`SELECT v.userId, u.name, u.username, u.color, v.createdAt
                 FROM StoryView v JOIN User u ON u.id = v.userId
                 WHERE v.storyId = ${id}
                   AND ${storyUnblocked(Prisma.sql`${requesterId}`, Prisma.sql`v.userId`)}
                   AND ${receiptVisible} AND ${viewerAudience}
                 ORDER BY v.createdAt ASC, v.userId ASC ${pageGuard}`,
    )
    let nextCursor: string | null = null
    let hasMore = false
    let page = views
    if (limit !== null) {
      hasMore = views.length > limit
      page = hasMore ? views.slice(0, limit) : views
      // Same contract as the feed: the final page carries nextCursor: null.
      const last = page[page.length - 1]
      nextCursor = hasMore && last ? encodeStoryCursor(last.createdAt, last.userId) : null
    }
    return storyJson({
      viewers: page.map(v => ({
        userId: v.userId, name: v.name, username: v.username, color: v.color,
        viewedAt: new Date(Number(v.createdAt)).toISOString(),
      })),
      nextCursor,
      hasMore,
    })
  }, { isolationLevel: 'Serializable' })
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)
  return db.$transaction(async tx => {
    const rows = await tx.$queryRaw<Array<{ id: string; userId: string }>>(
      Prisma.sql`SELECT s.id, s.userId FROM StatusStory s
                 WHERE s.id = ${id} AND s.expiresAt > ${Date.now()}
                   AND ${storyAudience(requesterId, Prisma.sql`s.userId`)} LIMIT 1`,
    )
    const story = rows[0]
    // Hidden, expired and nonexistent stories are indistinguishable to a non-audience caller.
    if (!story) return storyJson({ error: 'Story not found.' }, { status: 404 })
    const owner = story.userId === requesterId
    if (!owner) await tx.$executeRaw(
      Prisma.sql`INSERT INTO StoryView (id, storyId, userId, createdAt)
                 VALUES (${randomUUID()}, ${id}, ${requesterId}, ${Date.now()})
                 ON CONFLICT(storyId, userId) DO NOTHING`,
    )
    // The viewer's own receipt visibility (Section 9 item 3) - returned so a
    // client can honestly show "viewing without a receipt" instead of guessing.
    const mine = await tx.$queryRaw<Array<{ receipts: number | bigint }>>(
      Prisma.sql`SELECT CASE WHEN storyViewReceipts THEN 1 ELSE 0 END AS receipts
                 FROM User WHERE id = ${requesterId} LIMIT 1`,
    )
    const counts = await tx.$queryRaw<Array<{ n: number | bigint }>>(
      Prisma.sql`SELECT COUNT(*) AS n FROM StoryView v JOIN User viewer ON viewer.id = v.userId WHERE v.storyId = ${id}
                 AND ${storyUnblocked(Prisma.sql`${story.userId}`, Prisma.sql`v.userId`)}
                 AND ${storyReceiptDisclosed(Prisma.sql`v.userId`)}
                 AND ${storyViewerInAuthorAudience(Prisma.sql`${story.userId}`, Prisma.sql`v.userId`)}`,
    )
    return storyJson({
      viewCount: Number(counts[0]?.n ?? 0),
      receiptVisible: Number(mine[0]?.receipts ?? 1) === 1,
      ...(owner ? { owner: true } : {}),
    })
  }, { isolationLevel: 'Serializable' })
}

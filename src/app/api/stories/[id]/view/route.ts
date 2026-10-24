// Owner viewer list and audience-authorized, idempotent view mark.
import { randomUUID } from 'node:crypto'
import { Prisma } from '../../../../../../prisma/generated-client'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { privateStoryResponse, storyAudience, storyUnblocked, storyJson } from '@/lib/story-access'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const requesterId = new URL(req.url).searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)
  return db.$transaction(async tx => {
    const rows = await tx.$queryRaw<Array<{ userId: string }>>(
      Prisma.sql`SELECT userId FROM StatusStory WHERE id = ${id} AND expiresAt > ${Date.now()} LIMIT 1`,
    )
    if (!rows[0]) return storyJson({ error: 'Story not found.' }, { status: 404 })
    if (rows[0].userId !== requesterId) return storyJson({ error: 'Only the owner can see viewers.' }, { status: 403 })
    const views = await tx.$queryRaw<Array<{ userId: string; name: string; username: string | null; color: string; createdAt: number | bigint }>>(
      Prisma.sql`SELECT v.userId, u.name, u.username, u.color, v.createdAt
                 FROM StoryView v JOIN User u ON u.id = v.userId
                 WHERE v.storyId = ${id} AND ${storyUnblocked(Prisma.sql`${requesterId}`, Prisma.sql`v.userId`)}
                 ORDER BY v.createdAt ASC, v.userId ASC`,
    )
    return storyJson({ viewers: views.map(v => ({
      userId: v.userId, name: v.name, username: v.username, color: v.color,
      viewedAt: new Date(Number(v.createdAt)).toISOString(),
    })) })
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
    const counts = await tx.$queryRaw<Array<{ n: number | bigint }>>(
      Prisma.sql`SELECT COUNT(*) AS n FROM StoryView v JOIN User viewer ON viewer.id = v.userId WHERE v.storyId = ${id}
                 AND ${storyUnblocked(Prisma.sql`${story.userId}`, Prisma.sql`v.userId`)}`,
    )
    return storyJson({ viewCount: Number(counts[0]?.n ?? 0), ...(owner ? { owner: true } : {}) })
  }, { isolationLevel: 'Serializable' })
}

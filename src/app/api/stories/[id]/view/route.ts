// ─────────────────────────────────────────────────────────────
// /api/stories/[id]/view — viewers list + mark-as-viewed
//
// Raw SQL instead of typed delegates: StatusStory/StoryView were pushed
// after the running dev server cached its Prisma client (see /api/stories).
// ─────────────────────────────────────────────────────────────
import { randomUUID } from 'node:crypto'
import { NextResponse } from 'next/server'
import { Prisma } from '@prisma/client'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/stories/[id]/view?requesterId=
 * Owner-only: who watched this story (oldest first).
 * → { viewers: [{ userId, name, username, color, viewedAt }] }
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const storyRows = await db.$queryRaw<Array<{ id: string; userId: string }>>(
    Prisma.sql`SELECT id, userId FROM StatusStory WHERE id = ${id} LIMIT 1`,
  )
  const story = storyRows[0]
  if (!story) {
    return NextResponse.json({ error: 'Story not found.' }, { status: 404 })
  }
  if (story.userId !== requesterId) {
    return NextResponse.json({ error: 'Only the owner can see viewers.' }, { status: 403 })
  }

  const views = await db.$queryRaw<Array<{ userId: string; createdAt: number | bigint }>>(
    Prisma.sql`SELECT userId, createdAt FROM StoryView WHERE storyId = ${id} ORDER BY createdAt ASC`,
  )

  const userIds = views.map((v) => v.userId)
  const users = userIds.length
    ? await db.user.findMany({
        where: { id: { in: userIds } },
        select: { id: true, name: true, username: true, color: true },
      })
    : []
  const userById = new Map(users.map((u) => [u.id, u]))

  return NextResponse.json({
    viewers: views.flatMap((v) => {
      const user = userById.get(v.userId)
      if (!user) return []
      return [
        {
          userId: user.id,
          name: user.name,
          username: user.username,
          color: user.color,
          viewedAt: new Date(Number(v.createdAt)).toISOString(),
        },
      ]
    }),
  })
}

/**
 * POST /api/stories/[id]/view  body { requesterId }
 * Marks the story viewed (idempotent upsert). The owner never records a
 * self-view. Expired stories are rejected — the feed never surfaces them.
 * → { viewCount, owner?: true }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const storyRows = await db.$queryRaw<Array<{ id: string; userId: string; expiresAt: number | bigint }>>(
    Prisma.sql`SELECT id, userId, expiresAt FROM StatusStory WHERE id = ${id} LIMIT 1`,
  )
  const story = storyRows[0]
  if (!story || Number(story.expiresAt) <= Date.now()) {
    return NextResponse.json({ error: 'Story not found.' }, { status: 404 })
  }

  if (story.userId === requesterId) {
    const viewCount = await countViews(id)
    return NextResponse.json({ viewCount, owner: true })
  }

  await db.$executeRaw(
    Prisma.sql`INSERT INTO StoryView (id, storyId, userId, createdAt)
               VALUES (${randomUUID()}, ${id}, ${requesterId}, ${Date.now()})
               ON CONFLICT(storyId, userId) DO NOTHING`,
  )
  const viewCount = await countViews(id)
  return NextResponse.json({ viewCount })
}

/** SQLite COUNT(*) arrives as number (or bigint) — normalize. */
async function countViews(storyId: string): Promise<number> {
  const rows = await db.$queryRaw<Array<{ n: number | bigint }>>(
    Prisma.sql`SELECT COUNT(*) as n FROM StoryView WHERE storyId = ${storyId}`,
  )
  return Number(rows[0]?.n ?? 0)
}

// ─────────────────────────────────────────────────────────────
// /api/stories — BeReal/Telegram/WhatsApp-style 24h status stories
//
// NOTE ON RAW SQL: this route talks to the real StatusStory/StoryView
// tables through `db.$queryRaw` / `db.$executeRaw` instead of the typed
// delegates. The tables were pushed AFTER the long-running dev server
// process generated its (cached) Prisma client, so `db.statusStory` is
// not yet defined in-process — raw SQL hits the identical schema and
// keeps working unchanged once a fresh client is loaded.
// SQLite DateTime columns are stored as INTEGER epoch milliseconds.
// ─────────────────────────────────────────────────────────────
import { randomUUID } from 'node:crypto'
import { NextResponse } from 'next/server'
import { Prisma } from '@prisma/client'
import { db } from '@/lib/db'
import { AVATAR_COLORS, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Stories live exactly 24h — hard-filtered by expiresAt everywhere. */
const STORY_TTL_MS = 24 * 60 * 60 * 1000

export const STORY_CAPTION_MAX = 280

/** Uploads filenames only (uuid.ext) — matches /api/uploads/[file] whitelist. */
const STORY_IMAGE_RE = /^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp)$/

// ── Row types + serialization ───────────────────────────────

interface StoryRow {
  id: string
  userId: string
  imagePath: string | null
  caption: string
  background: string
  createdAt: number | bigint
  expiresAt: number | bigint
}

interface StoryItem {
  id: string
  kind: 'image' | 'text'
  imagePath: string | null
  caption: string
  background: string
  createdAt: string
  expiresAt: string
  viewCount: number
  viewedByMe: boolean
}

function iso(value: number | bigint): string {
  return new Date(Number(value)).toISOString()
}

async function viewCountsFor(
  storyIds: string[],
  requesterId: string,
): Promise<Map<string, { count: number; mine: boolean }>> {
  const map = new Map<string, { count: number; mine: boolean }>()
  if (storyIds.length === 0) return map
  const rows = await db.$queryRaw<Array<{ storyId: string; userId: string }>>(
    Prisma.sql`SELECT storyId, userId FROM StoryView WHERE storyId IN (${Prisma.join(storyIds)})`,
  )
  for (const row of rows) {
    const entry = map.get(row.storyId) ?? { count: 0, mine: false }
    entry.count += 1
    if (row.userId === requesterId) entry.mine = true
    map.set(row.storyId, entry)
  }
  return map
}

function mapStory(story: StoryRow, requesterId: string, views: { count: number; mine: boolean }): StoryItem {
  return {
    id: story.id,
    kind: story.imagePath ? 'image' : 'text',
    imagePath: story.imagePath ?? null,
    caption: story.caption,
    background: story.background,
    createdAt: iso(story.createdAt),
    expiresAt: iso(story.expiresAt),
    viewCount: views.count,
    viewedByMe: views.mine,
  }
}

/**
 * GET /api/stories?requesterId=
 * Active (expiresAt > now) stories of the requester + every user who shares
 * at least one conversation with them (real social graph via participant
 * overlap). Grouped per user: my own group first with `mine: true`, then
 * contacts by newest story. `allSeen` = every story already viewed by me.
 * → { groups: [{ user: {id,name,username,color}, mine, allSeen, stories: [...] }] }
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const requester = await db.user.findUnique({ where: { id: requesterId }, select: { id: true } })
  if (!requester) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  // Social graph: users who share ≥1 conversation with the requester.
  const myConvs = await db.conversationParticipant.findMany({
    where: { userId: requesterId },
    select: { conversationId: true },
  })
  const convIds = myConvs.map((c) => c.conversationId)
  const contactRows =
    convIds.length > 0
      ? await db.conversationParticipant.findMany({
          where: { conversationId: { in: convIds }, userId: { not: requesterId } },
          select: { userId: true },
          distinct: ['userId'],
        })
      : []
  const audience = [requesterId, ...contactRows.map((c) => c.userId)]

  const nowMs = Date.now()
  const rows = await db.$queryRaw<StoryRow[]>(
    Prisma.sql`SELECT id, userId, imagePath, caption, background, createdAt, expiresAt
               FROM StatusStory
               WHERE expiresAt > ${nowMs} AND userId IN (${Prisma.join(audience)})
               ORDER BY createdAt ASC`,
  )

  const storyIds = rows.map((r) => r.id)
  const views = await viewCountsFor(storyIds, requesterId)

  // Group per user, oldest story first inside each group.
  const byUser = new Map<string, StoryItem[]>()
  for (const row of rows) {
    const list = byUser.get(row.userId) ?? []
    list.push(mapStory(row, requesterId, views.get(row.id) ?? { count: 0, mine: false }))
    byUser.set(row.userId, list)
  }

  interface StoryGroup {
    user: { id: string; name: string; username: string | null; color: string }
    mine: boolean
    allSeen: boolean
    stories: StoryItem[]
  }

  const groupUserIds = [...byUser.keys()]
  const users = groupUserIds.length
    ? await db.user.findMany({
        where: { id: { in: groupUserIds } },
        select: { id: true, name: true, username: true, color: true },
      })
    : []
  const userById = new Map(users.map((u) => [u.id, u]))

  const others: StoryGroup[] = []
  let mine: StoryGroup | null = null
  for (const userId of groupUserIds) {
    const stories = byUser.get(userId) as StoryItem[]
    const user = userById.get(userId)
    if (!user) continue
    const group: StoryGroup = {
      user,
      mine: userId === requesterId,
      allSeen: stories.every((s) => s.viewedByMe),
      stories,
    }
    if (group.mine) mine = group
    else others.push(group)
  }
  // Contacts with the freshest story first.
  others.sort(
    (a, b) =>
      Date.parse(b.stories[b.stories.length - 1].createdAt) -
      Date.parse(a.stories[a.stories.length - 1].createdAt),
  )

  const groups = mine ? [mine, ...others] : others
  return NextResponse.json({ groups })
}

/**
 * POST /api/stories  body { requesterId, caption?, background?, imagePath? }
 * Text story: caption required (≤280) + background palette key (default emerald).
 * Image story: imagePath required (from POST /api/uploads), caption optional.
 * Lives 24h. → 201 { story }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const requester = await db.user.findUnique({ where: { id: requesterId }, select: { id: true } })
  if (!requester) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const caption = strField(body.caption)
  if (caption.length > STORY_CAPTION_MAX) {
    return NextResponse.json(
      { error: `Caption must be ${STORY_CAPTION_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  const rawImage = strField(body.imagePath)
  const hasImage = rawImage.length > 0
  if (hasImage && !STORY_IMAGE_RE.test(rawImage)) {
    return NextResponse.json({ error: 'imagePath must be an uploaded image filename.' }, { status: 400 })
  }
  if (!hasImage && caption.length === 0) {
    return NextResponse.json(
      { error: 'A status needs a photo or some text.' },
      { status: 400 },
    )
  }

  // Gradient background is a text-story affordance — validated against the palette.
  let background = 'emerald'
  if (!hasImage) {
    const requested = strField(body.background)
    if (requested.length > 0) {
      if (!(AVATAR_COLORS as readonly string[]).includes(requested)) {
        return NextResponse.json({ error: 'Unknown background gradient.' }, { status: 400 })
      }
      background = requested
    }
  }

  const nowMs = Date.now()
  const id = randomUUID()
  await db.$executeRaw(
    Prisma.sql`INSERT INTO StatusStory (id, userId, imagePath, caption, background, createdAt, expiresAt)
               VALUES (${id}, ${requesterId}, ${hasImage ? rawImage : null}, ${caption}, ${background}, ${nowMs}, ${nowMs + STORY_TTL_MS})`,
  )

  return NextResponse.json(
    {
      story: {
        id,
        kind: hasImage ? ('image' as const) : ('text' as const),
        imagePath: hasImage ? rawImage : null,
        caption,
        background,
        createdAt: new Date(nowMs).toISOString(),
        expiresAt: new Date(nowMs + STORY_TTL_MS).toISOString(),
        viewCount: 0,
        viewedByMe: false,
      },
    },
    { status: 201 },
  )
}

// /api/stories - BeReal/Telegram/WhatsApp-style 24h status stories
//
// NOTE ON RAW SQL: this route talks to the real StatusStory/StoryView
// tables through `db.$queryRaw` / `db.$executeRaw` instead of the typed
// delegates. The tables were pushed AFTER the long-running dev server
// process generated its (cached) Prisma client, so `db.statusStory` is
// not yet defined in-process - raw SQL hits the identical schema and
// keeps working unchanged once a fresh client is loaded.
// SQLite DateTime columns are stored as INTEGER epoch milliseconds.
import { randomUUID } from 'node:crypto'
import { Prisma } from '../../../../prisma/generated-client'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { privateStoryResponse, storyAudience, storyUnblocked, storyJson } from '@/lib/story-access'
import { AVATAR_COLORS, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Stories live exactly 24h - hard-filtered by expiresAt everywhere. */
const STORY_TTL_MS = 24 * 60 * 60 * 1000

export const STORY_CAPTION_MAX = 280

/** Uploads filenames only (uuid.ext) - matches /api/uploads/[file] whitelist. */
const STORY_IMAGE_RE = /^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp)$/

// Row types + serialization 

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

async function viewCountsFor(tx: Prisma.TransactionClient, requesterId: string, nowMs: number): Promise<Map<string, { count: number; mine: boolean }>> {
  // Aggregate in SQLite, not by loading every viewer of every story into memory.
  // The fixed-parameter audience predicate avoids an unbounded IN (...) id list.
  const rows = await tx.$queryRaw<Array<{ storyId: string; n: number | bigint; mine: number | bigint }>>(
    Prisma.sql`SELECT v.storyId, COUNT(*) AS n,
                 MAX(CASE WHEN v.userId = ${requesterId} THEN 1 ELSE 0 END) AS mine
               FROM StoryView v JOIN StatusStory s ON s.id = v.storyId
               JOIN User viewer ON viewer.id = v.userId
               WHERE s.expiresAt > ${nowMs} AND ${storyAudience(requesterId, Prisma.sql`s.userId`)}
                 AND ${storyUnblocked(Prisma.sql`s.userId`, Prisma.sql`v.userId`)}
               GROUP BY v.storyId`,
  )
  return new Map(rows.map(row => [row.storyId, { count: Number(row.n), mine: Number(row.mine) === 1 }]))
}

function mapStory(story: StoryRow, views: { count: number; mine: boolean }): StoryItem {
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
    return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  }

  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)

  const groups = await db.$transaction(async tx => {
    const nowMs = Date.now()
    const rows = await tx.$queryRaw<Array<StoryRow & { name: string; username: string | null; color: string }>>(
      Prisma.sql`SELECT s.id, s.userId, s.imagePath, s.caption, s.background, s.createdAt, s.expiresAt,
                        u.name, u.username, u.color
                 FROM StatusStory s JOIN User u ON u.id = s.userId
                 WHERE s.expiresAt > ${nowMs} AND ${storyAudience(requesterId, Prisma.sql`s.userId`)}
                 ORDER BY s.createdAt ASC, s.id ASC`,
    )
    const views = await viewCountsFor(tx, requesterId, nowMs)

    // Group per user, oldest story first inside each group.
    const byUser = new Map<string, StoryItem[]>()
    for (const row of rows) {
      const list = byUser.get(row.userId) ?? []
      list.push(mapStory(row, views.get(row.id) ?? { count: 0, mine: false }))
      byUser.set(row.userId, list)
    }

    interface StoryGroup {
      user: { id: string; name: string; username: string | null; color: string }
      mine: boolean
      allSeen: boolean
      stories: StoryItem[]
    }

    const userById = new Map(rows.map(row => [row.userId, {
      id: row.userId, name: row.name, username: row.username, color: row.color,
    }]))

    const others: StoryGroup[] = []
    let mine: StoryGroup | null = null
    for (const userId of byUser.keys()) {
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

    return mine ? [mine, ...others] : others
  }, { isolationLevel: 'Serializable' })
  return storyJson({ groups })
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
    return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  }

  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)

  const caption = strField(body.caption)
  if (caption.length > STORY_CAPTION_MAX) {
    return storyJson(
      { error: `Caption must be ${STORY_CAPTION_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  const rawImage = strField(body.imagePath)
  const hasImage = rawImage.length > 0
  if (hasImage && !STORY_IMAGE_RE.test(rawImage)) {
    return storyJson({ error: 'imagePath must be an uploaded image filename.' }, { status: 400 })
  }
  if (!hasImage && caption.length === 0) {
    return storyJson(
      { error: 'A status needs a photo or some text.' },
      { status: 400 },
    )
  }

  // Gradient background is a text-story affordance - validated against the palette.
  let background = 'emerald'
  if (!hasImage) {
    const requested = strField(body.background)
    if (requested.length > 0) {
      if (!(AVATAR_COLORS as readonly string[]).includes(requested)) {
        return storyJson({ error: 'Unknown background gradient.' }, { status: 400 })
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

  return storyJson(
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

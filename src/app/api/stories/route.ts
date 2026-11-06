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
import {
  encodeStoryCursor, parseStoryCursor, parseStoryPageLimit, STORY_FEED_PAGE_MAX,
  privateStoryResponse, storyAudience, storyUnblocked, storyViewerInAuthorAudience, storyReceiptDisclosed, storyJson,
} from '@/lib/story-access'
import { AVATAR_COLORS, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Stories live exactly 24h - hard-filtered by expiresAt everywhere. */
const STORY_TTL_MS = 24 * 60 * 60 * 1000

export const STORY_CAPTION_MAX = 280

/** Uploads filenames only (uuid.ext) - matches /api/uploads/[file] whitelist. */
const STORY_IMAGE_RE = /^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp)$/

/** Section 9 item 1: the client journals this identity BEFORE posting, so a
 * lost-ACK replay carries the SAME value and lands on the SAME story row.
 * Same acceptance window as folder/phrase clientIds (Section 8). */
const STORY_CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/

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

async function viewCountsFor(tx: Prisma.TransactionClient, requesterId: string, nowMs: number, storyIds?: string[]): Promise<Map<string, { count: number; mine: boolean }>> {
  // Aggregate in SQLite, not by loading every viewer of every story into memory.
  // The fixed-parameter audience predicate avoids an unbounded IN (...) id list.
  // Paged feeds pass the (bounded, <= page max) row ids so a page never pays
  // for the whole audience.
  if (storyIds && storyIds.length === 0) return new Map()
  const scoped = storyIds
    ? Prisma.sql`AND v.storyId IN (${Prisma.join(storyIds.map(id => Prisma.sql`${id}`))})`
    : Prisma.empty
  // Two distinct signals (Section 9 item 3): COUNT sums only DISCLOSED
  // receipts (receipts-off viewers stay invisible to every count, aligned
  // with the owner's viewer list), while `mine` reads the viewer's OWN row
  // unconditionally so their seen-state never depends on their own privacy.
  const rows = await tx.$queryRaw<Array<{ storyId: string; n: number | bigint; mine: number | bigint }>>(
    Prisma.sql`SELECT v.storyId,
                 SUM(CASE WHEN ${storyReceiptDisclosed(Prisma.sql`v.userId`)} THEN 1 ELSE 0 END) AS n,
                 MAX(CASE WHEN v.userId = ${requesterId} THEN 1 ELSE 0 END) AS mine
               FROM StoryView v JOIN StatusStory s ON s.id = v.storyId
               JOIN User viewer ON viewer.id = v.userId
               WHERE s.expiresAt > ${nowMs} AND ${storyAudience(requesterId, Prisma.sql`s.userId`)}
                 AND ${storyUnblocked(Prisma.sql`s.userId`, Prisma.sql`v.userId`)}
                 AND ${storyViewerInAuthorAudience(Prisma.sql`s.userId`, Prisma.sql`v.userId`)}
                 ${scoped}
               GROUP BY v.storyId`,
  )
  return new Map(rows.map(row => [row.storyId, { count: Number(row.n ?? 0), mine: Number(row.mine) === 1 }]))
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

interface StoryGroup {
  user: { id: string; name: string; username: string | null; color: string }
  mine: boolean
  allSeen: boolean
  stories: StoryItem[]
}

function groupFeed(rows: Array<StoryRow & { name: string; username: string | null; color: string }>, views: Map<string, { count: number; mine: boolean }>, requesterId: string): StoryGroup[] {
  // Rows arrive NEWEST-FIRST (the keyset walk direction): group insertion
  // order is therefore "freshest story first" across authors, while stories
  // inside each group are re-sorted oldest-first (viewer playback order).
  const byUser = new Map<string, StoryItem[]>()
  for (const row of rows) {
    const list = byUser.get(row.userId) ?? []
    list.push(mapStory(row, views.get(row.id) ?? { count: 0, mine: false }))
    byUser.set(row.userId, list)
  }
  const userById = new Map(rows.map(row => [row.userId, {
    id: row.userId, name: row.name, username: row.username, color: row.color,
  }]))
  const others: StoryGroup[] = []
  let mine: StoryGroup | null = null
  for (const [userId, stories] of byUser) {
    const user = userById.get(userId)
    if (!user) continue
    const group: StoryGroup = {
      user,
      mine: userId === requesterId,
      allSeen: stories.every((s) => s.viewedByMe),
      stories: [...stories].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id.localeCompare(b.id)),
    }
    if (group.mine) mine = group
    else others.push(group)
  }
  return mine ? [mine, ...others] : others
}

/**
 * GET /api/stories?requesterId=&limit=&cursor=
 * Active (expiresAt > now) stories of the requester + every user who shares
 * at least one conversation with them (real social graph via participant
 * overlap). Grouped per user: my own group first with `mine: true`, then
 * contacts by newest story. `allSeen` = every story already viewed by me.
 * → { groups: [{ user: {id,name,username,color}, mine, allSeen, stories: [...] }] }
 *
 * Section 9 item 2: `limit` (1..60) walks STORY ROWS newest-first with a
 * (createdAt,id) keyset cursor - one bounded page per request, never an
 * unbounded bind list or a whole-audience scan. `nextCursor`/`hasMore`
 * continue the walk; legacy callers (no limit) keep the exact full shape.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  }
  const limit = parseStoryPageLimit(url.searchParams.get('limit'), STORY_FEED_PAGE_MAX)
  if (url.searchParams.has('limit') && limit === null) {
    return storyJson({ error: `limit must be an integer 1..${STORY_FEED_PAGE_MAX}.` }, { status: 400 })
  }
  const cursor = parseStoryCursor(url.searchParams.get('cursor'))
  if (url.searchParams.has('cursor') && !cursor) {
    return storyJson({ error: 'cursor is invalid.' }, { status: 400 })
  }

  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)

  const payload = await db.$transaction(async tx => {
    const nowMs = Date.now()
    const rows = await tx.$queryRaw<Array<StoryRow & { name: string; username: string | null; color: string }>>(
      cursor
        ? Prisma.sql`SELECT s.id, s.userId, s.imagePath, s.caption, s.background, s.createdAt, s.expiresAt,
                        u.name, u.username, u.color
                 FROM StatusStory s JOIN User u ON u.id = s.userId
                 WHERE s.expiresAt > ${nowMs} AND ${storyAudience(requesterId, Prisma.sql`s.userId`)}
                   AND (s.createdAt < ${cursor.atMs} OR (s.createdAt = ${cursor.atMs} AND s.id < ${cursor.id}))
                 ORDER BY s.createdAt DESC, s.id DESC
                 ${limit ? Prisma.sql`LIMIT ${limit + 1}` : Prisma.empty}`
        : Prisma.sql`SELECT s.id, s.userId, s.imagePath, s.caption, s.background, s.createdAt, s.expiresAt,
                        u.name, u.username, u.color
                 FROM StatusStory s JOIN User u ON u.id = s.userId
                 WHERE s.expiresAt > ${nowMs} AND ${storyAudience(requesterId, Prisma.sql`s.userId`)}
                 ORDER BY s.createdAt DESC, s.id DESC
                 ${limit ? Prisma.sql`LIMIT ${limit + 1}` : Prisma.empty}`,
    )
    // Legacy (no limit): full feed, unchanged shape. Paged: LIMIT fetches one
    // extra row purely as the hasMore probe - it never reaches the response.
    let nextCursor: string | null = null
    let hasMore = false
    let page = rows
    if (limit !== null) {
      hasMore = rows.length > limit
      page = hasMore ? rows.slice(0, limit) : rows
      // The cursor is only meaningful while a next page exists - the final
      // page reports nextCursor: null next to hasMore: false.
      const last = page[page.length - 1]
      nextCursor = hasMore && last ? encodeStoryCursor(last.createdAt, last.id) : null
    }
    // Oldest-first stories inside every group; group order follows the walk.
    const views = await viewCountsFor(tx, requesterId, nowMs, limit !== null ? page.map(r => r.id) : undefined)
    const groups = groupFeed(page, views, requesterId)
    return { groups, nextCursor, hasMore }
  }, { isolationLevel: 'Serializable' })
  return storyJson(payload)
}

/**
 * POST /api/stories  body { requesterId, caption?, background?, imagePath?, clientId? }
 * Text story: caption required (≤280) + background palette key (default emerald).
 * Image story: imagePath required (from POST /api/uploads), caption optional.
 * Lives 24h. → 201 { story, replayed: false }
 *
 * Section 9 item 1 (durable lost-ACK identity): with `clientId`, the exact
 * (userId, clientId) is THE publication identity - a replay returns the SAME
 * stored story with 200 { story, replayed: true }, mismatched reuse of the
 * identity with different content is a 409, and the DB unique constraint
 * (userId, clientId) makes two racing replays converge on one row.
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

  // Section 9 item 1: journal-backed identity (16-128 [A-Za-z0-9_-], the
  // Section 8 folder/phrase window). Omitting it keeps the legacy path.
  let clientId: string | null = null
  if (body.clientId !== undefined && body.clientId !== null) {
    if (typeof body.clientId !== 'string' || !STORY_CLIENT_ID_RE.test(body.clientId)) {
      return storyJson({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
    }
    clientId = body.clientId
  }

  const nowMs = Date.now()
  const id = randomUUID()
  // The (userId, clientId) unique index arbitrates racing replays: the loser
  // re-reads the winner's row and either replays it or refuses the reuse.
  // $executeRaw surfaces SQLite's extended code (2067), not P2002, so both
  // shapes are matched - but ONLY for this exact column pair.
  const isIdentityConflict = (error: unknown): boolean => {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') return true
    const message = error instanceof Error ? error.message : String(error)
    return message.includes('UNIQUE constraint failed: StatusStory.userId, StatusStory.clientId')
  }
  try {
    await db.$executeRaw(
      Prisma.sql`INSERT INTO StatusStory (id, userId, imagePath, caption, background, createdAt, expiresAt, clientId)
                 VALUES (${id}, ${requesterId}, ${hasImage ? rawImage : null}, ${caption}, ${background}, ${nowMs}, ${nowMs + STORY_TTL_MS}, ${clientId})`,
    )
  } catch (error) {
    if (!isIdentityConflict(error) || !clientId) throw error
    const existing = await db.$queryRaw<Array<StoryRow>>(
      Prisma.sql`SELECT id, userId, imagePath, caption, background, createdAt, expiresAt
                 FROM StatusStory WHERE userId = ${requesterId} AND clientId = ${clientId} LIMIT 1`,
    )
    const prior = existing[0]
    if (!prior) return storyJson({ error: 'Publication identity is busy. Retry with a fresh identity.' }, { status: 409 })
    const same = prior.caption === caption &&
      (prior.imagePath ?? '') === (hasImage ? rawImage : '') &&
      prior.background === background
    if (!same) {
      return storyJson({ error: 'This publication identity already produced a different status.' }, { status: 409 })
    }
    return storyJson(
      { story: mapStory(prior, { count: 0, mine: false }), replayed: true },
      { status: 200 },
    )
  }

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
      replayed: false,
    },
    { status: 201 },
  )
}

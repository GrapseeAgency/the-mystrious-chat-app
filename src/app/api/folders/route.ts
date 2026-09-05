// ─────────────────────────────────────────────────────────────
// /api/folders — Signal/Beeper-style chat folders (Task R24-a)
//
// Contracts:
//   GET  ?userId=X
//        → { folders: FolderSummary[] } ordered by position asc.
//          Each folder carries conversationIds ordered by
//          FolderConversation.position (rail order). Folders are
//          user-scoped rows — no participant guard needed.
//   POST { userId, name, emoji? }
//        → name trimmed 1..24 chars (400 otherwise); emoji optional
//          (default "📂", capped at 16 chars); position = max+1
//          within the user's folders. Writes a LogEvent kind
//          "system" / message "folder created". → 201 { folder }
//
// FolderSummary shape lives in src/lib/types.ts — serialized here.
// (Next route files may only export handlers, so the tiny serializer
// is duplicated per file rather than shared.)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import type { FolderSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

const NAME_MIN = 1
const NAME_MAX = 24
const EMOJI_MAX = 16
const DEFAULT_EMOJI = '📂'

const FOLDER_FULL_INCLUDE = {
  entries: {
    orderBy: { position: 'asc' as const },
    select: { conversationId: true },
  },
} as const

/** Folder row (+ordered entries) → wire DTO matching FolderSummary. */
function serializeFolder(row: {
  id: string
  name: string
  emoji: string
  position: number
  entries: Array<{ conversationId: string }>
}): FolderSummary {
  return {
    id: row.id,
    name: row.name,
    emoji: row.emoji,
    position: row.position,
    conversationIds: row.entries.map((e) => e.conversationId),
  }
}

/**
 * GET /api/folders?userId=X
 * → { folders } ordered by position; entries in rail order. Single
 * round-trip (nested include) so the rail resolves fast.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json(
      { error: 'userId query parameter is required.' },
      { status: 400 },
    )
  }

  const folders = await db.folder.findMany({
    where: { userId },
    orderBy: { position: 'asc' },
    include: FOLDER_FULL_INCLUDE,
  })

  return NextResponse.json(
    { folders: folders.map(serializeFolder) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

/**
 * POST /api/folders { userId, name, emoji? }
 * → 201 { folder }. Duplicate folder names are allowed by design
 * (no unique constraint) — users may want e.g. two "Work" folders.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)

  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const name = strField(body.name)
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    return NextResponse.json(
      { error: `Folder name must be ${NAME_MIN}-${NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  const emojiRaw = strField(body.emoji)
  if (emojiRaw.length > EMOJI_MAX) {
    return NextResponse.json(
      { error: `emoji must be ${EMOJI_MAX} characters or fewer.` },
      { status: 400 },
    )
  }
  const emoji = emojiRaw || DEFAULT_EMOJI

  // The row is FK-bound to a real user — validate up front so a bad
  // id yields a clean 404 instead of a raw Prisma FK 500.
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const agg = await db.folder.aggregate({
    where: { userId },
    _max: { position: true },
  })
  const position = (agg._max.position ?? -1) + 1

  const created = await db.folder.create({
    data: { userId, name, emoji, position },
    include: FOLDER_FULL_INCLUDE,
  })

  await db.logEvent.create({
    data: {
      userId,
      kind: 'system',
      message: 'folder created',
      meta: JSON.stringify({ folderId: created.id, name: created.name }),
    },
  })

  return NextResponse.json({ folder: serializeFolder(created) }, { status: 201 })
}

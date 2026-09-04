// ─────────────────────────────────────────────────────────────
// /api/folders/[id] — single folder mutations (Task R24-a)
//
// Contracts:
//   PATCH  { name?, emoji?, position? }
//          → only provided fields change. name trimmed 1..24
//            (400 otherwise); emoji trimmed 1..16 (400 otherwise);
//            position non-negative integer. → 200 { folder } ·
//            404 unknown folder.
//   DELETE → removes the folder; FolderConversation entries cascade
//            (schema onDelete: Cascade — the chats themselves are
//            NOT touched, only membership rows). → 200 { ok: true }
//            · 404 unknown folder.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import type { FolderSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const NAME_MIN = 1
const NAME_MAX = 24
const EMOJI_MAX = 16

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
 * PATCH /api/folders/[id] — rename / re-emoji / reorder.
 * Fields are optional; only the provided ones change.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)

  const folder = await db.folder.findUnique({ where: { id }, select: { id: true } })
  if (!folder) {
    return NextResponse.json({ error: 'Folder not found.' }, { status: 404 })
  }

  const data: { name?: string; emoji?: string; position?: number } = {}

  if (body.name !== undefined) {
    const name = strField(body.name)
    if (name.length < NAME_MIN || name.length > NAME_MAX) {
      return NextResponse.json(
        { error: `Folder name must be ${NAME_MIN}-${NAME_MAX} characters.` },
        { status: 400 },
      )
    }
    data.name = name
  }

  if (body.emoji !== undefined) {
    const emoji = strField(body.emoji)
    if (emoji.length === 0 || emoji.length > EMOJI_MAX) {
      return NextResponse.json(
        { error: `emoji must be 1-${EMOJI_MAX} characters.` },
        { status: 400 },
      )
    }
    data.emoji = emoji
  }

  if (body.position !== undefined) {
    const raw = typeof body.position === 'number' ? body.position : Number(body.position)
    if (!Number.isInteger(raw) || raw < 0) {
      return NextResponse.json(
        { error: 'position must be a non-negative integer.' },
        { status: 400 },
      )
    }
    data.position = raw
  }

  const updated = await db.folder.update({
    where: { id },
    data,
    include: FOLDER_FULL_INCLUDE,
  })

  return NextResponse.json({ folder: serializeFolder(updated) })
}

/**
 * DELETE /api/folders/[id] — cascades FolderConversation entries via
 * the schema-level onDelete: Cascade. Conversations survive.
 */
export async function DELETE(_req: Request, { params }: RouteCtx) {
  const { id } = await params

  const folder = await db.folder.findUnique({ where: { id }, select: { id: true } })
  if (!folder) {
    return NextResponse.json({ error: 'Folder not found.' }, { status: 404 })
  }

  await db.folder.delete({ where: { id } })

  return NextResponse.json({ ok: true })
}

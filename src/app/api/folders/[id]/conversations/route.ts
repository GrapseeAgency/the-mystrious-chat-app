// ─────────────────────────────────────────────────────────────
// /api/folders/[id]/conversations — folder membership (Task R24-a)
//
// Contract:
//   PUT { conversationIds: string[] }
//        → FULL replace (Beeper-style sync): existing
//          FolderConversation rows for the folder are deleted, then
//          recreated with position = array index (rail order).
//          Duplicates are collapsed; every id must reference a real
//          conversation (400 otherwise) · 404 unknown folder.
//          An empty array clears the folder. → 200 { folder }
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson } from '@/lib/serializers'
import type { FolderSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

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
 * PUT /api/folders/[id]/conversations { conversationIds }
 * Full-replace membership inside one transaction so the folder is
 * never observed half-synced.
 */
export async function PUT(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)

  const folder = await db.folder.findUnique({ where: { id }, select: { id: true } })
  if (!folder) {
    return NextResponse.json({ error: 'Folder not found.' }, { status: 404 })
  }

  if (!Array.isArray(body.conversationIds)) {
    return NextResponse.json(
      { error: 'conversationIds must be an array of conversation ids.' },
      { status: 400 },
    )
  }

  // Collapse duplicates while preserving first-appearance order —
  // createMany would otherwise trip @@unique([folderId, conversationId]).
  const ids = Array.from(
    new Set(
      body.conversationIds
        .filter((v): v is string => typeof v === 'string')
        .map((v) => v.trim())
        .filter((v) => v !== ''),
    ),
  )

  if (ids.length > 0) {
    const found = await db.conversation.findMany({
      where: { id: { in: ids } },
      select: { id: true },
    })
    if (found.length !== ids.length) {
      return NextResponse.json(
        { error: 'One or more conversation ids do not exist.' },
        { status: 400 },
      )
    }
  }

  await db.$transaction(async (tx) => {
    await tx.folderConversation.deleteMany({ where: { folderId: id } })
    if (ids.length > 0) {
      await tx.folderConversation.createMany({
        data: ids.map((conversationId, position) => ({ folderId: id, conversationId, position })),
      })
    }
  })

  const updated = await db.folder.findUnique({
    where: { id },
    include: FOLDER_FULL_INCLUDE,
  })
  if (!updated) {
    return NextResponse.json({ error: 'Folder not found.' }, { status: 404 })
  }

  return NextResponse.json({ folder: serializeFolder(updated) })
}

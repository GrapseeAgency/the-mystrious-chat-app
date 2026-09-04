// ─────────────────────────────────────────────────────────────
// /api/stories/[id] — delete my own status story (cascades views)
//
// Raw SQL instead of the typed delegate: the StatusStory table was pushed
// after the running dev server cached its Prisma client (see /api/stories).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { Prisma } from '@prisma/client'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * DELETE /api/stories/[id]?requesterId=
 * Owner-only removal — StoryView rows are removed explicitly (schema also
 * cascades). → { ok: true } | 400 missing requesterId | 404 unknown | 403 not owner
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const rows = await db.$queryRaw<Array<{ id: string; userId: string }>>(
    Prisma.sql`SELECT id, userId FROM StatusStory WHERE id = ${id} LIMIT 1`,
  )
  const story = rows[0]
  if (!story) {
    return NextResponse.json({ error: 'Story not found.' }, { status: 404 })
  }
  if (story.userId !== requesterId) {
    return NextResponse.json({ error: 'Only the owner can delete this status.' }, { status: 403 })
  }

  await db.$executeRaw(Prisma.sql`DELETE FROM StoryView WHERE storyId = ${id}`)
  await db.$executeRaw(Prisma.sql`DELETE FROM StatusStory WHERE id = ${id}`)
  return NextResponse.json({ ok: true })
}

// Owner-only, atomic story + viewer removal.
import { Prisma } from '../../../../../prisma/generated-client'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { privateStoryResponse, storyJson } from '@/lib/story-access'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const requesterId = new URL(req.url).searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) return storyJson({ error: 'requesterId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, requesterId, 'story')
  if (denied) return privateStoryResponse(denied)
  return db.$transaction(async tx => {
    const rows = await tx.$queryRaw<Array<{ userId: string }>>(
      Prisma.sql`SELECT userId FROM StatusStory WHERE id = ${id} LIMIT 1`,
    )
    if (!rows[0]) return storyJson({ error: 'Story not found.' }, { status: 404 })
    if (rows[0].userId !== requesterId) return storyJson({ error: 'Only the owner can delete this status.' }, { status: 403 })
    await tx.$executeRaw(Prisma.sql`DELETE FROM StoryView WHERE storyId = ${id}`)
    await tx.$executeRaw(Prisma.sql`DELETE FROM StatusStory WHERE id = ${id}`)
    return storyJson({ ok: true })
  }, { isolationLevel: 'Serializable' })
}

import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson } from '@/lib/serializers'
import { withFolderOwner, FolderError, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderSelection, readFolderListRev } from '@/lib/folder-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

/** Section 8 item 9: full ordered membership of one folder, page by page.
 * Keyset over (position, id); default page 200, max 500 - the rail snapshot
 * caps at 200 and flags truncation, this endpoint yields the rest. */
export async function GET(req: Request, { params }: RouteCtx) {
  try {
    const { id } = await params
    const url = new URL(req.url)
    const limitRaw = url.searchParams.get('limit')
    const limit = Math.min(Math.max(limitRaw ? Number.parseInt(limitRaw, 10) || 0 : 200, 1), 500)
    const afterPosRaw = url.searchParams.get('afterPosition')
    const afterId = url.searchParams.get('afterId')?.trim() ?? ''
    if (afterId && (afterPosRaw === null || !Number.isSafeInteger(Number(afterPosRaw))))
      throw new FolderError(400, 'afterId requires afterPosition.')
    const result = await withFolderOwner(db, req, { folderId: id }, async (tx, owner) => {
      const rows = await tx.folderConversation.findMany({
        where: { folderId: id,
          ...(afterId ? { OR: [
            { position: { gt: Number(afterPosRaw) } },
            { position: Number(afterPosRaw), conversationId: { gt: afterId } },
          ] } : {}) },
        orderBy: [{ position: 'asc' as const }, { conversationId: 'asc' as const }],
        take: limit + 1,
        select: { conversationId: true, position: true },
      })
      const hasMore = rows.length > limit
      const page = rows.slice(0, limit)
      const last = page[page.length - 1]
      return { ...folderScope(owner, await readFolderListRev(tx, owner)),
        conversationIds: page.map(row => row.conversationId),
        nextCursor: hasMore && last ? { position: last.position, conversationId: last.conversationId } : null,
        hasMore }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

/** Full ordered replacement: authorization, membership validation, write and receipt
 * share a transaction. A foreign/removed/invalid chat rejects the entire replacement. */
export async function PUT(req: Request, { params }: RouteCtx) {
  try {
    const { id } = await params
    const body = await safeJson(req)
    const result = await withFolderOwner(db, req, { folderId: id }, async (tx, owner) => {
      const ids = folderSelection(body.conversationIds)
      const count = await tx.conversationParticipant.count({ where: { userId: owner, conversationId: { in: ids } } })
      if (count !== ids.length) throw new FolderError(403, 'One or more chats are unavailable to this folder owner.')
      await tx.folderConversation.deleteMany({ where: { folderId: id } })
      if (ids.length) await tx.folderConversation.createMany({ data: ids.map((conversationId, position) => ({ folderId: id, conversationId, position })) })
      // Section 8 item 9: membership edits take the folder's next revision.
      const folder = await tx.folder.update({ where: { id }, data: { rev: { increment: 1 } }, include: folderInclude(owner) })
      return { ...folderScope(owner, await readFolderListRev(tx, owner)), folder: folderWire(folder) }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

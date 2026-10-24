import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson } from '@/lib/serializers'
import { withFolderOwner, FolderError, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderSelection } from '@/lib/folder-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

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
      const folder = await tx.folder.findUniqueOrThrow({ where: { id }, include: folderInclude(owner) })
      return { ...folderScope(owner), folder: folderWire(folder) }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

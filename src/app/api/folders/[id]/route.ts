import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { folderIconId } from '@/lib/icon-ids'
import { withFolderOwner, FolderError, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderName } from '@/lib/folder-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function PATCH(req: Request, { params }: RouteCtx) {
  try {
    const { id } = await params
    const body = await safeJson(req)
    const result = await withFolderOwner(db, req, { folderId: id }, async (tx, owner) => {
      const data: { name?: string; emoji?: string } = {}
      if (body.name !== undefined) data.name = folderName(body.name)
      if (body.emoji !== undefined) data.emoji = folderIconId(strField(body.emoji))
      if (body.position !== undefined) {
        const position = body.position
        if (typeof position !== 'number' || !Number.isSafeInteger(position) || position < 0) {
          throw new FolderError(400, 'position must be a non-negative integer.')
        }
        const ordered = await tx.folder.findMany({ where: { userId: owner }, orderBy: [{ position: 'asc' }, { id: 'asc' }], select: { id: true } })
        if (position >= ordered.length) throw new FolderError(400, 'position is outside the folder list.')
        const remaining = ordered.filter(row => row.id !== id)
        remaining.splice(position, 0, { id })
        // A move is one atomic operation, not two independent swaps that can fail halfway.
        for (const [index, row] of remaining.entries()) await tx.folder.update({ where: { id: row.id }, data: { position: index } })
      }
      const updated = await tx.folder.update({ where: { id }, data, include: folderInclude(owner) })
      return { ...folderScope(owner), folder: folderWire(updated) }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}
export async function DELETE(req: Request, { params }: RouteCtx) {
  try {
    const { id } = await params
    const result = await withFolderOwner(db, req, { folderId: id }, async (tx, owner) => {
      await tx.folder.delete({ where: { id } })
      return { ...folderScope(owner), folderId: id, deleted: true, ok: true }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

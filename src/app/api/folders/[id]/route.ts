import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { folderIconId } from '@/lib/icon-ids'
import { withFolderOwner, FolderError, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderName, bumpFolderListRev, readFolderListRev } from '@/lib/folder-operation'
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
      let moved = false
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
        moved = true
      }
      // Section 8 item 9: every accepted edit takes the folder's next revision
      // (label/content) and, for reorders, the rail's list revision too.
      const updated = await tx.folder.update({ where: { id }, data: { ...data, rev: { increment: 1 } }, include: folderInclude(owner) })
      const listRev = moved ? await bumpFolderListRev(tx, owner) : await readFolderListRev(tx, owner)
      return { ...folderScope(owner, listRev), folder: folderWire(updated) }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}
export async function DELETE(req: Request, { params }: RouteCtx) {
  try {
    const { id } = await params
    const result = await withFolderOwner(db, req, { folderId: id }, async (tx, owner) => {
      await tx.folder.delete({ where: { id } })
      // Section 8 item 9: a deletion is a rail-level change - the list
      // revision moves so other devices know to drop the folder.
      const listRev = await bumpFolderListRev(tx, owner)
      return { ...folderScope(owner, listRev), folderId: id, deleted: true, ok: true }
    })
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

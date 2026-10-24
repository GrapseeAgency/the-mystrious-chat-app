import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { folderIconId } from '@/lib/icon-ids'
import { withFolderOwner, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderName } from '@/lib/folder-operation'
export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  try {
    const userId = new URL(req.url).searchParams.get('userId') ?? ''
    const result = await withFolderOwner(db, req, { userId }, async (tx, owner) => ({
      ...folderScope(owner), folders: (await tx.folder.findMany({ where: { userId: owner },
        orderBy: [{ position: 'asc' }, { id: 'asc' }], include: folderInclude(owner) })).map(folderWire),
    }))
    return NextResponse.json(result, { headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}
export async function POST(req: Request) {
  try {
    const body = await safeJson(req)
    const result = await withFolderOwner(db, req, { userId: strField(body.userId) }, async (tx, owner) => {
      const name = folderName(body.name)
      const agg = await tx.folder.aggregate({ where: { userId: owner }, _max: { position: true } })
      const created = await tx.folder.create({ data: { userId: owner, name, emoji: folderIconId(strField(body.emoji)),
        position: (agg._max.position ?? -1) + 1 }, include: folderInclude(owner) })
      await tx.logEvent.create({ data: { userId: owner, kind: 'system', message: 'folder created',
        meta: JSON.stringify({ folderId: created.id, name }) } })
      return { ...folderScope(owner), folder: folderWire(created) }
    })
    return NextResponse.json(result, { status: 201, headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

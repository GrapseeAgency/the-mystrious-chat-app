import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { folderIconId } from '@/lib/icon-ids'
import { withFolderOwner, folderErrorResponse, folderHeaders, folderInclude, folderWire, folderScope, folderName, folderCreateOnce, folderRequestHash, FOLDER_CLIENT_ID_RE, readFolderListRev, bumpFolderListRev } from '@/lib/folder-operation'
export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  try {
    const userId = new URL(req.url).searchParams.get('userId') ?? ''
    const result = await withFolderOwner(db, req, { userId }, async (tx, owner) => ({
      ...folderScope(owner, await readFolderListRev(tx, owner)),
      folders: (await tx.folder.findMany({ where: { userId: owner },
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
      const emoji = folderIconId(strField(body.emoji))
      // Section 8 item 8: durable creation receipt. An uncertain ACK retried
      // with the SAME clientId replays the SAME folder instead of duplicating.
      let clientId: string | null = null
      if (body.clientId !== undefined && body.clientId !== null) {
        if (typeof body.clientId !== 'string' || !FOLDER_CLIENT_ID_RE.test(body.clientId))
          throw new FolderError(400, 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.')
        clientId = body.clientId
      }
      const agg = await tx.folder.aggregate({ where: { userId: owner }, _max: { position: true } })
      const position = (agg._max.position ?? -1) + 1
      const outcome = await folderCreateOnce(tx, owner, clientId, folderRequestHash(name, emoji), async () =>
        tx.folder.create({ data: { userId: owner, name, emoji, position }, include: folderInclude(owner) }))
      if (!outcome.replayed) {
        await tx.logEvent.create({ data: { userId: owner, kind: 'system', message: 'folder created',
          meta: JSON.stringify({ folderId: (outcome.result as { id: string }).id, name }) } })
      }
      return { ...folderScope(owner, outcome.listRev), replayed: outcome.replayed,
        folder: folderWire(outcome.result as Parameters<typeof folderWire>[0]) }
    })
    return NextResponse.json(result, { status: result.replayed ? 200 : 201, headers: folderHeaders })
  } catch (error) { return folderErrorResponse(error) }
}

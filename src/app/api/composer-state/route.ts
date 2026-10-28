import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { readComposer, writeComposer, composerFailure, type ComposerKind } from '@/lib/composer-operation'
import { safeJson, strField, memberIdsOf, notifySocket, mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
export const dynamic = 'force-dynamic'
const headers = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }
export async function GET(req: Request) {
  const q = new URL(req.url).searchParams, owner = q.get('userId') ?? ''
  const denied = await requireActionOwner(req, owner, 'composer state')
  if (denied) return denied
  try { return Response.json(await readComposer(db, owner, q.get('kind') as ComposerKind, q.get('targetId') ?? '', q.get('rootId') ?? ''), { headers }) }
  catch (error) { return composerFailure(error) }
}
export async function PATCH(req: Request) {
  const body = await safeJson(req), owner = strField(body.userId)
  const denied = await requireActionOwner(req, owner, 'composer write')
  if (denied) return denied
  try {
    const result = await writeComposer(db, owner, body.kind as ComposerKind, body.targetId as string, (body.rootId == null ? '' : body.rootId) as string, body.text as string,
      { clientId: body.clientId as string, expectedRevision: body.expectedRevision as string | undefined, predecessorClientId: body.predecessorClientId as string | undefined })
    if (result.changed && result.kind === 'edit') {
      // The receipt's canonical text is authoritative; hints only trigger repair.
      // Read the current row so a concurrent later edit cannot emit an older body.
      const message = await db.message.findUnique({ where: { id: result.targetId }, include: MESSAGE_FULL_INCLUDE })
      if (message) {
        const recipients = await memberIdsOf(result.conversationId)
        await notifySocket('message:edited', recipients, { type: 'message:edited', conversationId: result.conversationId, recipientIds: recipients, message: mapMessage(message) })
      }
    }
    return Response.json(result, { headers })
  } catch (error) { return composerFailure(error) }
}

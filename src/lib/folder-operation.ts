import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { bearerToken, tokenAccepted } from './session-token'

export class FolderError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export function folderErrorResponse(error: unknown): Response {
  if (error instanceof FolderError) return Response.json({ error: error.message }, { status: error.status })
  if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
    return Response.json({ error: 'Folders changed or are busy. Refresh before retrying.' }, { status: 409 })
  }
  throw error
}
export const folderHeaders = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }
/** Resolve ownership, authorize and perform the entire read/write in one snapshot.
 * Folder IDs are not credentials. Never trust a supplied owner for an existing ID. */
export async function withFolderOwner<T>(db: PrismaClient, req: Request,
  target: { userId: string } | { folderId: string },
  action: (tx: Prisma.TransactionClient, userId: string) => Promise<T>): Promise<T> {
  const token = bearerToken(req)
  if (!token) throw new FolderError(401, 'A folder session credential is required.')
  return db.$transaction(async tx => {
    let userId: string
    if ('userId' in target) userId = target.userId
    else {
      const folder = await tx.folder.findUnique({ where: { id: target.folderId }, select: { userId: true } })
      if (!folder) throw new FolderError(404, 'Folder not found.')
      userId = folder.userId
    }
    if (!userId) throw new FolderError(400, 'A folder owner is required.')
    const owner = await tx.user.findUnique({ where: { id: userId }, select: { sessionTokenHash: true, legacyTokenHashes: true } })
    if (!tokenAccepted(owner, token)) throw new FolderError(403, 'This session does not own the requested folder.')
    return action(tx, userId)
  }, { isolationLevel: 'Serializable' })
}
export function folderInclude(userId: string) {
  return { entries: {
    where: { conversation: { participants: { some: { userId } } } },
    orderBy: [{ position: 'asc' as const }, { id: 'asc' as const }], select: { conversationId: true },
  } }
}
export function folderWire(row: { id: string; name: string; emoji: string; position: number; entries: { conversationId: string }[] }) {
  return { id: row.id, name: row.name, emoji: row.emoji, position: row.position,
    conversationIds: row.entries.map(entry => entry.conversationId) }
}
export function folderScope(userId: string) { return { folderVersion: 1, userId } }
export function folderName(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.trim().length > 24) throw new FolderError(400, 'Folder name must be 1-24 characters.')
  return value.trim()
}
export function folderSelection(value: unknown): string[] {
  // Invalid entries must not silently turn a replacement into a destructive clear.
  if (!Array.isArray(value) || value.some(id => typeof id !== 'string' || !id.trim())) {
    throw new FolderError(400, 'conversationIds must contain non-empty conversation ids.')
  }
  return [...new Set(value.map((id: string) => id.trim()))]
}

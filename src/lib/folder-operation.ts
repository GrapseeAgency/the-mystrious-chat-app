import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { createHash } from 'node:crypto'
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
    // Section 8 item 9: very large membership lists are bounded here; the
    // full ordered membership stays available page-by-page at
    // GET /api/folders/[id]/conversations (folderWire flags the truncation).
    take: 200,
  } }
}
export function folderWire(row: { id: string; name: string; emoji: string; position: number; rev?: number; entries: { conversationId: string }[] }) {
  return { id: row.id, name: row.name, emoji: row.emoji, position: row.position,
    rev: row.rev ?? 0,
    conversationIds: row.entries.map(entry => entry.conversationId),
    // Section 8 item 9: honest truncation flag for huge folders.
    truncated: row.entries.length >= 200 }
}
/** Section 8 items 8/9: the folder rail carries a per-user monotonic list
 * revision (create/delete/reorder) alongside each folder's own rev (content
 * and label edits). Cross-device writes are totally ordered by these. */
export async function bumpFolderListRev(tx: Prisma.TransactionClient, userId: string): Promise<number> {
  await tx.$executeRaw`INSERT INTO UserListRevision (userId, kind, rev) VALUES (${userId}, 'folders', 1)
    ON CONFLICT (userId, kind) DO UPDATE SET rev = rev + 1`
  const row = await tx.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM UserListRevision WHERE userId = ${userId} AND kind = 'folders'`
  return row[0].rev
}
export async function readFolderListRev(db: PrismaClient | Prisma.TransactionClient, userId: string): Promise<number> {
  const row = await db.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM UserListRevision WHERE userId = ${userId} AND kind = 'folders'`
  return row[0]?.rev ?? 0
}
export function folderScope(userId: string, listRev: number) { return { folderVersion: 1, rev: listRev, userId } }
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

/** Section 8 item 8: folder creation replay. The same (owner, clientId) always
 * resolves to the SAME folder, so an uncertain acknowledgement retried after a
 * process loss can never duplicate the rail. A reused clientId with different
 * instructions is a 409, and a folder deleted after creation is a 410.
 * Runs on the CALLER's transaction client - never opens its own. */
export const FOLDER_CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/
export function folderRequestHash(name: string, emoji: string): string {
  return createHash('sha256').update(JSON.stringify(['folder', name, emoji])).digest('hex')
}
export async function folderCreateOnce<T>(
  tx: Prisma.TransactionClient, owner: string, clientId: string | null, requestHash: string,
  create: (tx: Prisma.TransactionClient) => Promise<T>,
): Promise<{ result: T; replayed: boolean; listRev: number }> {
  if (clientId) {
    const prior = await tx.folderCreationOperation.findUnique({
      where: { userId_clientId: { userId: owner, clientId } },
      select: { requestHash: true, folderId: true },
    })
    if (prior) {
      if (prior.requestHash !== requestHash)
        throw new FolderError(409, 'This folder ID was already used for different instructions.')
      const folder = await tx.folder.findUnique({ where: { id: prior.folderId } })
      if (!folder) throw new FolderError(410, 'This folder was created, but was subsequently deleted.')
      return { result: folder as unknown as T, replayed: true, listRev: await readFolderListRev(tx, owner) }
    }
  }
  const result = await create(tx)
  const folderId = (result as { id: string }).id
  if (clientId) {
    await tx.folderCreationOperation.create({ data: { userId: owner, clientId, requestHash, folderId } })
  }
  return { result, replayed: false, listRev: await bumpFolderListRev(tx, owner) }
}

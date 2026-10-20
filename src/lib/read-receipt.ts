import type { PrismaClient } from '../../prisma/generated-client'

export class ReadReceiptError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

export function receiptRecipients(owner: string, members: string[], preferences: string | null): string[] {
  let visible = true
  try { visible = JSON.parse(preferences ?? '{}')?.readReceipts !== false } catch { /* default prefs */ }
  // Other devices belonging to the reader must still clear their unread badge.
  return [...new Set(visible ? [owner, ...members] : [owner])]
}

/** A displayed message is a server-validated boundary, never a phone clock. */
export async function advanceReadReceipt(db: PrismaClient, room: string, owner: string, messageId?: string) {
  return db.$transaction(async tx => {
    const participant = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: owner, conversationId: room } },
      select: { id: true, lastReadAt: true },
    })
    if (!participant) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
    const message = await tx.message.findFirst({
      where: { conversationId: room, ...(messageId ? { id: messageId } : {}) },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { createdAt: true },
    })
    if (messageId && !message) throw new ReadReceiptError(404, 'The displayed message is no longer in this conversation.')
    const boundary = message?.createdAt ?? participant.lastReadAt
    // A late response from an older screen/device must not move the watermark backwards.
    await tx.conversationParticipant.updateMany({
      where: { id: participant.id, lastReadAt: { lt: boundary } }, data: { lastReadAt: boundary },
    })
    const updated = await tx.conversationParticipant.update({
      where: { id: participant.id }, data: { manualUnread: false }, select: { lastReadAt: true },
    })
    return updated.lastReadAt
  })
}

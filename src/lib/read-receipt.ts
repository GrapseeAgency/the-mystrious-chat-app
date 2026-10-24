import { privacyPreferenceEnabled } from './privacy-preferences.ts'
import type { PrismaClient } from '../../prisma/generated-client'

export class ReadReceiptError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

export function receiptRecipients(owner: string, members: string[], preferences: string | null | undefined): string[] {
  const visible = privacyPreferenceEnabled(preferences, 'readReceipts')
  // Other devices belonging to the reader must still clear their unread badge.
  return [...new Set(visible ? [owner, ...members] : [owner])]
}

/** A displayed message is a server-validated boundary, never a phone clock. */
export async function advanceReadReceipt(db: PrismaClient, room: string, owner: string, messageId?: string) {
  try {
    return await db.$transaction(async tx => {
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } },
        select: { id: true, lastReadAt: true },
      })
      if (!participant) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const message = await tx.message.findFirst({
        where: { conversationId: room, ...(messageId ? { id: messageId } : {}) },
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        select: { createdAt: true, deletedAt: true, expiresAt: true, parentId: true,
          parent: { select: { conversationId: true, deletedAt: true, expiresAt: true, parentId: true } } },
      })
      const now = new Date()
      const parent = message?.parent
      const unavailableParent = message?.parentId !== null && (!parent || parent.conversationId !== room ||
        parent.parentId !== null || parent.deletedAt !== null || (parent.expiresAt !== null && parent.expiresAt <= now))
      // Explicit viewport receipts need the same live canonical-root boundary as
      // thread reads. Legacy/manual mark-all remains a distinct deliberate action.
      if (messageId && (!message || message.deletedAt !== null ||
          (message.expiresAt !== null && message.expiresAt <= now) || unavailableParent)) {
        throw new ReadReceiptError(404, 'The displayed message is no longer available in this conversation.')
      }
      const boundary = message?.createdAt ?? participant.lastReadAt
      // A late response from an older screen/device must not move the watermark backwards.
      await tx.conversationParticipant.updateMany({
        where: { id: participant.id, lastReadAt: { lt: boundary } }, data: { lastReadAt: boundary },
      })
      // An older viewport receipt did not advance progress. It must not
      // erase a manual marker placed after newer progress was acknowledged.
      // Equal/newer anchors still need an explicit intent fence for all races.
      const clearManual = !messageId || boundary >= participant.lastReadAt
      const updated = await tx.conversationParticipant.update({
        where: { id: participant.id }, data: clearManual ? { manualUnread: false } : {}, select: { lastReadAt: true },
      })
      return updated.lastReadAt
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      throw new ReadReceiptError(409, 'Read state changed or is busy. Retry the displayed boundary.')
    }
    throw error
  }
}

/** Membership and the marker write are one snapshot; acknowledge the actual
 * committed value, not a generic 2xx or an echoed request body. */
export async function setManualUnread(db: PrismaClient, room: string, owner: string, on: boolean) {
  try {
    return await db.$transaction(async tx => {
      if (!await tx.conversation.findUnique({ where: { id: room }, select: { id: true } })) {
        throw new ReadReceiptError(404, 'Conversation not found.')
      }
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } }, select: { id: true },
      })
      if (!participant) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const updated = await tx.conversationParticipant.update({
        where: { id: participant.id }, data: { manualUnread: on }, select: { manualUnread: true },
      })
      return { ok: true, unreadReceiptVersion: 1, conversationId: room, userId: owner, manualUnread: updated.manualUnread }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      throw new ReadReceiptError(409, 'Unread state changed or is busy. Refresh before retrying.')
    }
    throw error
  }
}

import { randomUUID } from 'node:crypto'
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

/** Capture once per logical visible boundary. Retries must not acquire newer intent. */
export async function captureReadIntent(db: PrismaClient, room: string, owner: string) {
  try {
    return await db.$transaction(async tx => {
      const member = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } }, select: { id: true },
      })
      if (!member) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const state = await tx.readIntentState.upsert({ where: { participantId: member.id },
        create: { participantId: member.id, revision: randomUUID() }, update: {}, select: { revision: true } })
      return { readIntentVersion: 2, conversationId: room, userId: owner, readIntent: `${state.revision}.${member.id}` }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      throw new ReadReceiptError(409, 'Read intent is busy. Retry without changing the visible boundary.')
    throw error
  }
}

/** A displayed message is a server-validated boundary, never a phone clock. */
export async function advanceReadReceipt(db: PrismaClient, room: string, owner: string, messageId?: string, readIntent?: string) {
  try {
    return await db.$transaction(async tx => {
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } },
        select: { id: true, lastReadAt: true },
      })
      if (!participant) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const state = await tx.readIntentState.findUnique({ where: { participantId: participant.id } })
      const ownsIntent = state !== null && readIntent !== undefined && `${state?.revision}.${participant.id}` === readIntent
      if (messageId && readIntent !== undefined && !readIntent.endsWith(`.${participant.id}`))
        throw new ReadReceiptError(410, 'This observation belongs to a retired membership.')
      // A stale explicit action must not clear a newer manual choice. A visible
      // receipt may still advance real progress, but never clears that choice.
      if (!messageId && readIntent !== undefined && !ownsIntent)
        throw new ReadReceiptError(409, 'A newer read/unread action won. Refresh before trying again.')
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
      // Persist only the identity that passed the visibility check. Equal-time
      // neighbours and older gaps are intentionally not covered by this write.
      if (messageId && readIntent !== undefined) {
        await tx.messageReadObservation.upsert({
          where: { participantId_messageId: { participantId: participant.id, messageId } },
          create: { participantId: participant.id, messageId, observedAt: now, source: 'visible' },
          // A migrated exclusion is not evidence until an actual observation.
          update: { source: 'visible', observedAt: now },
        })
      } else if (!messageId && ownsIntent) {
        // Explicit mark-all is a snapshot of actual identities, NOT a timestamp
        // range that also consumes messages arriving afterwards at the same time.
        await tx.$executeRaw`
          INSERT INTO MessageReadObservation (participantId, messageId, observedAt, source)
          SELECT ${participant.id}, m.id, ${now}, 'all' FROM Message m
          LEFT JOIN Message p ON p.id = m.parentId
          WHERE m.conversationId = ${room} AND m.deletedAt IS NULL
            AND (m.expiresAt IS NULL OR m.expiresAt > ${now})
            AND (m.parentId IS NULL OR (p.conversationId = m.conversationId AND p.parentId IS NULL
              AND p.deletedAt IS NULL AND (p.expiresAt IS NULL OR p.expiresAt > ${now})))
          ON CONFLICT(participantId, messageId) DO UPDATE SET source = CASE
            WHEN MessageReadObservation.source = 'legacy' THEN 'all' ELSE MessageReadObservation.source END
        `
      }
      const boundary = message?.createdAt ?? participant.lastReadAt
      // A late response from an older screen/device must not move the watermark backwards.
      await tx.conversationParticipant.updateMany({
        where: { id: participant.id, lastReadAt: { lt: boundary } }, data: { lastReadAt: boundary },
      })
      // Timestamp ordering alone cannot fence equal/newer delayed receipts.
      // Legacy unfenced reads may advance progress but cannot erase a manual dot.
      // Fresh reading authority, not message age, clears a manual reminder.
      // A newly opened older thread is still an intentional reading surface.
      const clearManual = ownsIntent
      if (!messageId && ownsIntent) await tx.readIntentState.update({
        where: { participantId: participant.id }, data: { revision: randomUUID() },
      })
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
export async function setManualUnread(db: PrismaClient, room: string, owner: string, on: boolean, readIntent?: string) {
  try {
    return await db.$transaction(async tx => {
      if (!await tx.conversation.findUnique({ where: { id: room }, select: { id: true } })) {
        throw new ReadReceiptError(404, 'Conversation not found.')
      }
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } }, select: { id: true },
      })
      if (!participant) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const state = await tx.readIntentState.findUnique({ where: { participantId: participant.id } })
      if (readIntent !== undefined && `${state?.revision}.${participant.id}` !== readIntent)
        throw new ReadReceiptError(409, 'A newer read/unread action won. Refresh before trying again.')
      // Rotate even for repeated desired values: this is a fresh explicit intent.
      await tx.readIntentState.upsert({ where: { participantId: participant.id },
        create: { participantId: participant.id, revision: randomUUID() }, update: { revision: randomUUID() } })
      const updated = await tx.conversationParticipant.update({
        where: { id: participant.id }, data: { manualUnread: on }, select: { manualUnread: true },
      })
      return { ok: true, unreadReceiptVersion: 2, readIntent, conversationId: room, userId: owner, manualUnread: updated.manualUnread }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      throw new ReadReceiptError(409, 'Unread state changed or is busy. Refresh before retrying.')
    }
    throw error
  }
}

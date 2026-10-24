import { privacyPreferenceEnabled } from './privacy-preferences.ts'
import type { PrismaClient } from '../../prisma/generated-client'
import { messageDisplayName } from './message-disclosure.ts'
import { clipUtf16 } from './text-limits.ts'

/** Only the delivery capability is required; no provider SDK is loaded here. */
type NotificationFanout = (recipients: string[], payload: {
  kind: 'message'; title: string; body: string; conversationId: string; messageId: string; expiresAt?: string;
}, options: { bodyFor: (userId: string) => string }) => Promise<unknown>

/** Re-read the committed message and CURRENT room recipients before a push.
 * Snapshot planning is atomic; provider delivery is not a durable retry outbox. */
export async function deliverMessageNotification(
  db: Pick<PrismaClient, '$transaction'>, fanout: NotificationFanout, messageId: string,
  clock: () => Date = () => new Date(),
): Promise<boolean> {
  const plan = await db.$transaction(async tx => {
    const message = await tx.message.findUnique({ where: { id: messageId }, select: {
      id: true, conversationId: true, senderId: true, content: true, kind: true,
      anon: true, anonAlias: true, viaAutomation: true, payload: true, viewOnce: true,
      deletedAt: true, expiresAt: true, parentId: true, sender: { select: { name: true } },
      parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
      conversation: { select: { name: true, isGroup: true, participants: { select: {
        userId: true, mutedUntil: true, user: { select: { preferences: true } },
      } } } },
    } })
    const now = clock()
    if (!message || message.deletedAt || (message.expiresAt && message.expiresAt <= now)) return null
    const root = message.parent
    if (message.parentId && (!root || root.conversationId !== message.conversationId || root.parentId ||
      root.deletedAt || (root.expiresAt && root.expiresAt <= now))) return null
    const blocked = await tx.userBlock.findMany({ where: { OR: [
      { blockerId: message.senderId }, { blockedId: message.senderId },
    ] }, select: { blockerId: true, blockedId: true } })
    const blockedIds = new Set(blocked.flatMap(row => [row.blockerId, row.blockedId]).filter(id => id !== message.senderId))
    const targets = message.conversation.participants.filter(row =>
      (row.userId !== message.senderId || message.viaAutomation) && !blockedIds.has(row.userId) &&
      (!row.mutedUntil || row.mutedUntil <= now))
    if (!targets.length) return null
    const sender = messageDisplayName(message)
    // No rich-payload text is copied into notifications. Single-use captions
    // are private even when a legacy record's kind is text instead of image.
    const deadlines = [message.expiresAt, message.parentId ? root?.expiresAt : null].filter((d): d is Date => d != null)
    const deadline = deadlines.length ? Math.min(...deadlines.map(d => d.getTime())) : null
    // Provider TTL cannot erase a notification already in the OS tray.
    const body = message.viewOnce ? 'View-once message' : deadline !== null ? 'Disappearing message' : message.kind === 'text'
      ? clipUtf16(message.content.trim(), 140) || 'New message'
      : ({ image: 'Photo', audio: 'Voice message', voice: 'Voice message', video: 'Video',
        sticker: 'Sticker', file: 'Document', location: 'Location', poll: 'Poll' } as Record<string, string>)[message.kind] ?? 'New message'
    const previews = new Map(targets.map(row =>
      [row.userId, privacyPreferenceEnabled(row.user.preferences, 'notifPreviews')] as const))
    return { targets: targets.map(row => row.userId), previews,
      deadline,
      payload: { kind: 'message' as const, conversationId: message.conversationId, messageId: message.id,
        ...(deadline !== null ? { expiresAt: new Date(deadline).toISOString() } : {}),
        title: message.conversation.isGroup && message.conversation.name ? `${sender} · ${message.conversation.name}` : sender,
        body: message.conversation.isGroup ? `${sender}: ${body}` : body } }
  }, { isolationLevel: 'Serializable' })
  if (!plan || (plan.deadline !== null && plan.deadline <= clock().getTime())) return false
  await fanout(plan.targets, plan.payload, { bodyFor: id => plan.previews.get(id) ? plan.payload.body : 'New message' })
  return true
}

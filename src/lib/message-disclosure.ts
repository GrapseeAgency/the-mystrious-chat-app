import type { ChatMessage, ReplySnippet } from './types'

/** A soft-deleted database row is a tombstone on the wire, not a hidden copy
 * of the original attachment/text that exports or another renderer can expose. */
export function redactDeletedMessage(message: ChatMessage): ChatMessage {
  if (!message.deletedAt) return message
  return { ...message, content: '', payload: null, replyTo: null, reactions: [],
    imagePath: null, audioPath: null, durationMs: null, filePath: null, fileName: null, fileSize: null,
    transcript: null, transcribedAt: null, pinnedAt: null, pinnedBy: null,
    linkUrl: null, linkPreview: null, poll: null, translations: [] }
}

export function quoteDisclosure(parent: { content: string; deletedAt: Date | null; expiresAt: Date | null; viewOnce: boolean },
  now: Date = new Date()): { content: string; deleted: boolean } {
  const deleted = parent.deletedAt !== null || (parent.expiresAt !== null && parent.expiresAt <= now)
  return { content: deleted ? '' : parent.viewOnce ? 'View-once message' : parent.content, deleted }
}

/** Quotes are independent cached disclosures: include their own privacy bounds,
 * never the represented account name for anonymous or machine-authored rows. */
export function quoteSnippet(parent: {
  id: string; content: string; deletedAt: Date | null; expiresAt: Date | null; viewOnce: boolean;
  anon: boolean; anonAlias: string | null; viaAutomation: boolean; payload: string | null;
  sender: { name: string }; conversationId: string; parentId: string | null;
  parent: { conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null } | null;
}, conversationId: string, now: Date = new Date()): ReplySnippet {
  const root = parent.parent
  const invalid = parent.conversationId !== conversationId || (parent.parentId !== null &&
    (!root || root.conversationId !== conversationId || root.parentId !== null || root.deletedAt !== null))
  const deadlines = [parent.expiresAt, parent.parentId ? root?.expiresAt : null].filter((d): d is Date => d != null)
  const expiresAt = deadlines.length ? new Date(Math.min(...deadlines.map(d => d.getTime()))) : null
  const disclosure = quoteDisclosure({ ...parent, expiresAt, deletedAt: invalid ? now : parent.deletedAt }, now)
  let senderName = parent.sender.name
  if (disclosure.deleted) senderName = 'Unavailable message'
  else if (parent.anon) senderName = parent.anonAlias?.trim() || 'Anonymous'
  else {
    let metadata: Record<string, unknown> = {}
    try {
      if (parent.payload && parent.payload.length <= 2048) {
        const parsed: unknown = JSON.parse(parent.payload)
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) metadata = parsed as Record<string, unknown>
      }
    } catch { /* malformed legacy metadata is not identity */ }
    if (parent.viaAutomation) {
      if (metadata.webhook === true) {
        const name = typeof metadata.webhookName === 'string'
          ? Array.from(metadata.webhookName.replace(/[\p{Cc}\p{Cf}]/gu, '')).slice(0, 40).join('').trim() : ''
        senderName = name ? `Webhook · ${name}` : 'Webhook'
      } else senderName = metadata.bot === true ? 'Bot' : 'Automation'
    } else if (metadata.webhook === true || metadata.bot === true) senderName = 'Unverified integration'
  }
  return { id: parent.id, ...disclosure, senderName, quoteVersion: 1,
    expiresAt: expiresAt?.toISOString() ?? null, viewOnce: parent.viewOnce }
}
